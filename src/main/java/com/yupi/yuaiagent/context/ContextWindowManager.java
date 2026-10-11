package com.yupi.yuaiagent.context;

import cn.hutool.core.util.StrUtil;
import com.yupi.yuaiagent.metrics.AgentRunMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 上下文窗口管理器（2026-10 上下文重构 · 阶段 2，对标 Claude Code 的运行层两级压缩）
 * <p>
 * <b>参考对象 / 其机制</b>：Claude Code 在接近上下文上限时先做 microcompact（清掉旧的工具输出，
 * 零 LLM 成本、需要时能重取），还不够才做 compact（LLM 摘要，不可逆，但压缩前把完整对话落盘 transcript）；
 * Anthropic 的 context editing（{@code clear_tool_uses_20250919}）把参数显式化：trigger 阈值、
 * keep 最近 3 个工具结果、{@code clear_at_least} 一次至少清够多少 token 才动手（防小碎步反复打穿 prompt cache）。
 * <p>
 * <b>本项目的取舍</b>：参数全量对齐（keep=3、clear-at-least=1500 token、trigger 用窗口比例），
 * 但阈值判断用轻量估算器而不是精确分词器（决策只需误差方向稳定，实测比值见阶段 4）；
 * 不做 {@code /resume}（只留证据不可恢复），不做跨会话的压缩缓存。
 * <p>
 * <b>两个层级都留证据</b>：微压缩与整体压缩都会丢原文，所以动手前各写一份 transcript
 * （{@code reason} 进文件名）。真机冒烟时发现只在二级压缩前落盘是不够的——那时被清掉的工具原文
 * 早已变成占位文案，快照里也就查不到它了。
 * <p>
 * <b>为什么不看"消息条数"</b>：一个工具步固定 +3 条消息，但一步可能产生 32KB（网页正文）也可能 5 个字；
 * 按条数触发必然"该压不压、不该压乱压"——旧实现（{@code optimizeContextWindowIfNeeded}）的问题就在这里，
 * 它保留为 {@code mode=legacy} 的回滚目标。
 * <p>
 * <b>无状态</b>：本类不持有 Agent、不缓存消息；每次调用的临时状态由调用方通过 {@link ManageRequest}
 * 与 {@link RunState} 传入——同一个 Bean 可以安全地被每请求 new 出来的 Agent 共享。
 */
@Component
@Slf4j
public class ContextWindowManager {

    /**
     * 记忆块 SystemMessage 的识别前缀（{@code MemoryContext.toPromptSection()} 的第一行）。
     * 压缩时它必须<b>原文保留</b>：旧实现把它一起摘要了，压完只剩转述，
     * 「跨会话记得你是谁」的能力在第一个长任务里就失效了。
     */
    static final String MEMORY_SECTION_PREFIX = "[Session Metadata]";

    /** 摘要块前缀（与旧实现的 SUMMARY_PREFIX 保持一致，便于人读日志） */
    static final String SUMMARY_PREFIX = "[Context Summary]\n";

    /** 微压缩替换文案：必须写清"是什么工具、如何重新拿到"，对标 Claude Code 把截断文案当接口文档的做法 */
    static final String CLEARED_PLACEHOLDER_TEMPLATE =
            "[工具结果已清理以节省上下文] 工具 %s 的返回内容已被移除。如仍需要，请重新调用 %s"
                    + "（readFile 可用 offset/limit 缩小范围，searchWeb 可换更精确的关键词）。";

    /**
     * 从工具参数里提取路径。键名按各工具真实的 {@code @ToolParam} 参数名来：
     * readFile/writeFile/generatePDF 是 {@code fileName}，downloadResource 是 {@code url}，
     * 另兼容 {@code path}/{@code filePath}/{@code source} 这类常见写法。
     */
    private static final Pattern FILE_PATH_PATTERN = Pattern.compile(
            "\"(?:fileName|filePath|path|url|source)\"\\s*:\\s*\"([^\"]+)\"");

    private static final Set<String> FILE_TOOLS = Set.of(
            "readFile", "writeFile", "generatePDF", "downloadResource");

    private final ContextProperties properties;

    private final TranscriptWriter transcriptWriter;

    public ContextWindowManager(ContextProperties properties, TranscriptWriter transcriptWriter) {
        this.properties = properties;
        this.transcriptWriter = transcriptWriter;
    }

    /** 是否处于预算模式（ToolCallAgent 据此在两级实现间分流，legacy 路径一行不改） */
    public boolean isBudgetMode() {
        return properties.isBudgetMode();
    }

    ContextProperties getProperties() {
        return properties;
    }

    /**
     * 按 token 预算管理上下文（唯一的对外入口）。
     *
     * @param messages 当前消息列表（会被原地修改：微压缩是"替换某条消息"，不重建列表）
     * @param request  本次调用的上下文（摘要器、原始任务、身份、步数、指标）
     * @return 处理结果：新的消息列表 + 是否发生了一级/二级压缩 + 释放的 token 估算值
     */
    public Result manage(List<Message> messages, ManageRequest request) {
        if (messages == null || messages.isEmpty() || !isBudgetMode()) {
            return new Result(messages, false, false, 0);
        }
        int window = Math.max(properties.getWindowTokens(), 1);
        int overhead = properties.getStaticOverheadTokens();
        int estimate = ContextTokenEstimator.estimate(messages) + overhead;

        // ---- 一级：微压缩（零 LLM 成本，可重取）----
        boolean microcompacted = false;
        int freedTokens = 0;
        if (properties.isMicrocompactEnabled()
                && estimate >= window * properties.getMicrocompactTriggerRatio()) {
            freedTokens = microcompact(messages, request);
            if (freedTokens > 0) {
                microcompacted = true;
                estimate = ContextTokenEstimator.estimate(messages) + overhead;
                recordMicrocompaction(request.getMetrics(), freedTokens);
                log.info("{} 微压缩：清理旧工具结果，释放约 {} token，当前上下文约 {} token（窗口 {}）",
                        request.getAgentName(), freedTokens, estimate, window);
            }
        }

        // ---- 二级：整体压缩（一次 LLM，不可逆，先落盘）----
        boolean compacted = false;
        if (estimate >= window * properties.getCompactTriggerRatio()
                && request.getRunState().canCompact(properties, request.getStep())) {
            compacted = compact(messages, request);
            if (compacted) {
                estimate = ContextTokenEstimator.estimate(messages) + overhead;
                request.getRunState().recordCompaction(request.getStep());
                log.info("{} 上下文压缩完成：压缩后约 {} token（窗口 {}），消息数 {}",
                        request.getAgentName(), estimate, window, messages.size());
            }
        }
        return new Result(messages, microcompacted, compacted, freedTokens);
    }

    // ==================== 一级：微压缩 ====================

    /**
     * 把"除最近 keep 条之外"的工具结果替换成占位文案。
     * <p>
     * 三条自我约束（都来自对标对象的既有教训）：
     * ① 只清理成"整条消息"的粒度——半清会让模型看到自相矛盾的历史；
     * ② 至少清够 {@code clear-at-least} token 才动手，否则白打穿 prompt cache 前缀；
     * ③ 已经清过的、以及 doTerminate 的结果不重复处理（后者是任务清单，校验要用）。
     *
     * @return 释放的 token 估算值（0 表示本次没动手）
     */
    private int microcompact(List<Message> messages, ManageRequest request) {
        List<Integer> candidates = new ArrayList<>();
        int toolResultSeen = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (!(messages.get(i) instanceof ToolResponseMessage toolResponse)) {
                continue;
            }
            // 最近 keep 条工具结果不动：刚用过的输出最可能马上被引用
            toolResultSeen++;
            if (toolResultSeen <= properties.getMicrocompactKeepToolResults()) {
                continue;
            }
            if (isAllClearedOrTerminate(toolResponse)) {
                continue;
            }
            candidates.add(i);
        }
        if (candidates.isEmpty()) {
            return 0;
        }
        // 候选按"从旧到新"处理
        candidates.sort(Integer::compareTo);
        int clearable = 0;
        for (int index : candidates) {
            clearable += clearableTokens((ToolResponseMessage) messages.get(index));
        }
        if (clearable < properties.getMicrocompactClearAtLeastTokens()) {
            // clear_at_least：清不出足够多就别清——清了也只省一点，却要付出一次 cache 失效
            return 0;
        }
        // 微压缩同样是"丢原文"：被清掉的工具输出，之后只有这份快照还留着
        //（真机冒烟发现：若只在二级压缩前落盘，快照里早就已经是占位文案了）
        writeTranscript(request, "microcompact", messages);
        int freed = 0;
        for (int index : candidates) {
            ToolResponseMessage original = (ToolResponseMessage) messages.get(index);
            int before = ContextTokenEstimator.estimate(original);
            ToolResponseMessage cleared = clearToolResponses(original);
            messages.set(index, cleared);
            freed += Math.max(before - ContextTokenEstimator.estimate(cleared), 0);
        }
        return freed;
    }

    /** 该消息是否"没有可清理的内容"：全部已被清理过，或全部是 doTerminate（任务清单，校验要用） */
    private boolean isAllClearedOrTerminate(ToolResponseMessage message) {
        for (ToolResponseMessage.ToolResponse response : message.getResponses()) {
            String data = String.valueOf(response.responseData());
            if (data.startsWith(CLEARED_MARKER) || "doTerminate".equals(response.name())) {
                continue;
            }
            return false;
        }
        return true;
    }

    private int clearableTokens(ToolResponseMessage message) {
        int total = 0;
        for (ToolResponseMessage.ToolResponse response : message.getResponses()) {
            if (String.valueOf(response.responseData()).startsWith(CLEARED_MARKER)) {
                continue;
            }
            total += Math.max(ContextTokenEstimator.estimate(String.valueOf(response.responseData()))
                    - ContextTokenEstimator.estimate(placeholderFor(response.name())), 0);
        }
        return total;
    }

    /**
     * 重建一条工具结果消息：只换 responseData，保留 id 与 name。
     * <p>
     * 保留 id 是硬要求：OpenAI 兼容接口按 {@code tool_call_id} 关联 assistant 的 tool_calls，
     * 对不上就是 400（"tool_call_id not found"）。
     */
    private ToolResponseMessage clearToolResponses(ToolResponseMessage message) {
        List<ToolResponseMessage.ToolResponse> cleared = new ArrayList<>();
        for (ToolResponseMessage.ToolResponse response : message.getResponses()) {
            String data = String.valueOf(response.responseData());
            if (data.startsWith(CLEARED_MARKER) || "doTerminate".equals(response.name())) {
                cleared.add(response);
                continue;
            }
            cleared.add(new ToolResponseMessage.ToolResponse(
                    response.id(), response.name(), placeholderFor(response.name())));
        }
        return new ToolResponseMessage(cleared, message.getMetadata());
    }

    private static String placeholderFor(String toolName) {
        return String.format(CLEARED_PLACEHOLDER_TEMPLATE, toolName, toolName);
    }

    /** 占位文案的识别标记（判断"这条是否已经清过"的唯一依据） */
    static final String CLEARED_MARKER = "[工具结果已清理以节省上下文]";

    // ==================== 二级：整体压缩 ====================

    /**
     * 摘要把前段历史压成一条 SystemMessage，并把"受保护的内容"重新注入。
     *
     * @return 是否真的压了（摘要器整体失败且回落也没拿到内容时返回 false）
     */
    private boolean compact(List<Message> messages, ManageRequest request) {
        int keep = Math.max(properties.getCompactKeepMessages(), 1);
        int splitIndex = Math.max(1, messages.size() - keep);
        // 轮次边界对齐：尾部（recent）的第一条不能是 ToolResponseMessage——孤立工具结果会让
        // OpenAI 兼容接口找不到对应的 tool_call_id 而直接报 400。
        // 对齐方向是**往前退**（而不是往后推）：退一格正好落在发起该调用的 AssistantMessage 上，
        // 于是 recent 以"assistant(tool_call) + toolResponse"这一对合法开头；
        // 往后推则是在丢消息，极端情况（尾部全是工具结果）还会推到表尾、压不动任何东西。
        while (splitIndex > 1 && messages.get(splitIndex) instanceof ToolResponseMessage) {
            splitIndex--;
        }
        List<Message> head = new ArrayList<>(messages.subList(0, splitIndex));
        List<Message> recent = new ArrayList<>(messages.subList(splitIndex, messages.size()));

        Message protectedSystemMessage = findMemorySystemMessage(messages);
        Message originalTask = findOriginalTask(messages, request);
        List<Message> toSummarize = new ArrayList<>();
        for (Message message : head) {
            if (message == protectedSystemMessage || message == originalTask) {
                continue;
            }
            toSummarize.add(message);
        }

        // 没有可摘要的内容（前段只剩记忆块 / 原始任务这些"要原文保留"的项）→ 直接放弃。
        // 这一步必须在落盘之前：真机冒烟时发现，短会话里每一轮都会走到这里（尾部 keep 条几乎覆盖全部消息），
        // 若先落盘就会为"根本没发生的压缩"留下一份 transcript，还会按份数上限把真正的证据挤掉。
        if (toSummarize.isEmpty()) {
            log.debug("{} 无可摘要内容（前段均为受保护项），跳过本次压缩", request.getAgentName());
            return false;
        }

        // 先落盘再丢内容：压缩是不可逆的，证据要在动手之前留好
        writeTranscript(request, "compact", messages);

        SummaryResult summary = summarize(toSummarize, request);
        if (StrUtil.isBlank(summary.text())) {
            return false;
        }
        recordCompaction(request.getMetrics(), summary.fallback());
        List<String> files = extractRecentFilePaths(messages);

        List<Message> rebuilt = new ArrayList<>();
        if (protectedSystemMessage != null) {
            rebuilt.add(protectedSystemMessage);
        }
        rebuilt.add(new SystemMessage(SUMMARY_PREFIX + summary.text() + renderRecentFiles(files)));
        if (originalTask != null) {
            rebuilt.add(originalTask);
        }
        rebuilt.addAll(recent);
        messages.clear();
        messages.addAll(rebuilt);
        return true;
    }

    /**
     * 摘要：与主对话<b>同一个系统提示</b>（对标 Claude Code 的"同系统提示 + 同历史 + 末尾一条指令"），
     * 而不是另写一套英文压缩提示——换系统提示等于换人格，摘要出来的东西和主对话关心的不是一回事。
     * <p>
     * 渲染必须按消息类型分支：{@link ToolResponseMessage} 取 {@code getResponses()}
     * （它的 {@code getText()} 恒为空串，旧实现就是因此把工具输出全丢了的）。
     */
    private SummaryResult summarize(List<Message> messages, ManageRequest request) {
        if (messages.isEmpty()) {
            return new SummaryResult("", true);
        }
        String historyText = renderForSummary(messages);
        try {
            String summary = request.getSummarizer().summarize(historyText);
            if (StrUtil.isNotBlank(summary)) {
                return new SummaryResult(truncateChars(summary.trim(), properties.getSummaryMaxChars()), false);
            }
        } catch (Exception e) {
            log.warn("{} 生成上下文摘要失败，改用尾部截断: {}", request.getAgentName(), e.getMessage());
        }
        // 失败回落：保留"最近"的内容（旧实现截最老的 1200 字符——恰好丢掉模型最需要的"我刚才在干什么"）
        return new SummaryResult(tail(historyText, properties.getSummaryFallbackKeepChars()), true);
    }

    /** 摘要结果：文本 + 是否走了失败回落（回落到尾部截断也要记指标，否则"压缩失败率"永远看不见） */
    private record SummaryResult(String text, boolean fallback) {
    }

    /**
     * 把消息渲染成摘要器的输入文本（每条截断，防止一条 32KB 的工具结果把摘要请求本身撑爆）。
     * <p>
     * <b>public 是刻意的</b>：legacy（默认模式）路径的摘要器也调它——P3（{@code getText()} 空串）
     * 是两条路径共有的 bug，只修 budget 等于把已知缺陷留在默认路径上；共用同一个渲染器之后，
     * A/B 里两组的差异才只剩"触发策略"这一个变量。
     */
    public String renderForSummary(List<Message> messages) {
        StringBuilder builder = new StringBuilder();
        int itemMax = properties.getSummaryInputItemMaxChars();
        for (Message message : messages) {
            String rendered = renderForSummary(message);
            builder.append(String.valueOf(message.getMessageType())).append(": ")
                    .append(truncateChars(rendered, itemMax)).append('\n');
        }
        return builder.toString();
    }

    private String renderForSummary(Message message) {
        if (message instanceof ToolResponseMessage toolResponseMessage) {
            StringBuilder builder = new StringBuilder();
            for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                builder.append("[tool ").append(response.name()).append("] ")
                        .append(String.valueOf(response.responseData())).append(' ');
            }
            return builder.toString().trim();
        }
        if (message instanceof AssistantMessage assistantMessage) {
            StringBuilder builder = new StringBuilder(StrUtil.blankToDefault(assistantMessage.getText(), ""));
            for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                builder.append(" [call ").append(toolCall.name()).append(' ').append(toolCall.arguments()).append(']');
            }
            return builder.toString().trim();
        }
        return StrUtil.blankToDefault(message.getText(), "");
    }

    /** 第一条记忆块 SystemMessage（原文保留，压缩后重新注入） */
    private Message findMemorySystemMessage(List<Message> messages) {
        for (Message message : messages) {
            if (message instanceof SystemMessage && StrUtil.isNotBlank(message.getText())
                    && message.getText().startsWith(MEMORY_SECTION_PREFIX)) {
                return message;
            }
        }
        return null;
    }

    /**
     * 原始任务（压缩后重新注入，模型永远看得见最初目标）。
     * <p>
     * <b>2026-10-11 修法</b>：优先按调用方传入的本轮任务原文（{@code ManageRequest.originalTask}，
     * 来自 {@code BaseAgent.currentUserPrompt}）在消息里定位<b>真实对象</b>——压缩的
     * "跳过受保护消息"与"重新注入"都用引用相等判断，必须返回列表里的原对象而不是新建一条。
     * 从后往前找：单会话恢复后，同一段文字可能既在恢复的历史里、又是本轮任务（用户复读），
     * 最后一条才是本轮。
     * <p>
     * 兜底扫描"第一条非注入 UserMessage"仅服务调用方未传任务原文的场景（测试/旧路径）——
     * 恢复历史注入后它可能扫到上一次会话的历史轮次，所以生产路径必须传原文。
     */
    private Message findOriginalTask(List<Message> messages, ManageRequest request) {
        String task = request.getOriginalTask();
        if (StrUtil.isNotBlank(task)) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                Message message = messages.get(i);
                if (message instanceof UserMessage && task.equals(message.getText())) {
                    return message;
                }
            }
        }
        for (Message message : messages) {
            if (message instanceof UserMessage && !isInjectedStepPrompt(message.getText())) {
                return message;
            }
        }
        return null;
    }

    /**
     * 是否是注入的"下一步提示"而非用户真话。
     * 判据：以 {@code [} 开头（工具的 RAG 增强提示是"参考资料:"开头）。
     * 这里刻意宽松——判错只会多保留一条消息，不会丢原始任务。
     */
    private boolean isInjectedStepPrompt(String text) {
        return StrUtil.isBlank(text) || text.startsWith("参考资料:");
    }

    /** 从历史里的工具调用参数提取"最近用过的文件/资源路径"（去重，取最后 N 个） */
    List<String> extractRecentFilePaths(List<Message> messages) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (Message message : messages) {
            if (!(message instanceof AssistantMessage assistantMessage)) {
                continue;
            }
            for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                if (!FILE_TOOLS.contains(toolCall.name())) {
                    continue;
                }
                Matcher matcher = FILE_PATH_PATTERN.matcher(StrUtil.blankToDefault(toolCall.arguments(), ""));
                if (matcher.find()) {
                    paths.add(matcher.group(1));
                }
            }
        }
        List<String> all = new ArrayList<>(paths);
        int keep = Math.max(properties.getCompactKeepRecentFiles(), 0);
        return keep == 0 || all.size() <= keep ? all : all.subList(all.size() - keep, all.size());
    }

    /**
     * 重注入最近文件<b>路径</b>（不回读内容）。
     * 取舍：Claude Code 压缩后会重读最近 5 个文件，本项目只给路径——回读要花 I/O 与 token，
     * 而模型需要时完全可以自己再调一次 readFile（这也让"重取"路径始终有人走、不会腐烂）。
     */
    private String renderRecentFiles(List<String> files) {
        if (files.isEmpty()) {
            return "";
        }
        return "\n[Recently used files]\n- " + String.join("\n- ", files);
    }

    /** 按字符数截断（摘要与渲染用；token 级硬上限见 {@link ContextTokenEstimator#truncateToExactTokens}） */
    private static String truncateChars(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, Math.max(maxChars, 0)) + "…（已截断）";
    }

    /** 保留尾部（"最近"的内容才是模型当下需要的） */
    private static String tail(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return "…（前文已省略）" + text.substring(text.length() - Math.max(maxChars, 0));
    }

    // ---- 指标写入：metrics 可能为 null（评测/测试场景没挂埋点），一律 fail-open ----

    /**
     * 落盘一份 transcript（两个层级共用）：任何"要丢原文"的动作之前先留证据。
     * <p>
     * {@code reason} 进文件名，事后能分辨这份快照对应哪次操作：
     * {@code microcompact} = 清旧工具输出（被清掉的原文只在这里），{@code compact} = LLM 摘要前的全量快照。
     */
    private void writeTranscript(ManageRequest request, String reason, List<Message> messages) {
        if (transcriptWriter == null) {
            return;
        }
        if (transcriptWriter.write(request.getConversationId(),
                request.getStep(), reason, messages) != null) {
            recordTranscriptWrite(request.getMetrics());
        }
    }

    private static void recordMicrocompaction(AgentRunMetrics metrics, int freedTokens) {
        if (metrics != null) {
            metrics.recordMicrocompaction(freedTokens);
        }
    }

    private static void recordCompaction(AgentRunMetrics metrics, boolean fallback) {
        if (metrics != null) {
            metrics.recordCompaction(fallback);
        }
    }

    private static void recordTranscriptWrite(AgentRunMetrics metrics) {
        if (metrics != null) {
            metrics.recordTranscriptWrite();
        }
    }

    /** 摘要器：ToolCallAgent 用主对话的 ChatClient 实现；测试里换成假实现就能离线跑全套算法 */
    @FunctionalInterface
    public interface Summarizer {
        String summarize(String historyText);
    }

    /**
     * 单次 run 的压缩状态（防抖动用）。由 Agent 持有、每 run 重置——
     * 放在本类会让单例 Bean 带上跨请求状态（"不要给 Agent 加共享状态"的同一条纪律）。
     */
    public static class RunState {

        private int compactions;

        private int lastCompactionStep = Integer.MIN_VALUE;

        /** 是否允许再压：次数上限 + 最小间隔步数（对标 Claude Code 的防 thrashing） */
        boolean canCompact(ContextProperties properties, int step) {
            if (compactions >= properties.getCompactMaxPerRun()) {
                return false;
            }
            return lastCompactionStep == Integer.MIN_VALUE
                    || step - lastCompactionStep >= properties.getCompactMinStepsBetween();
        }

        void recordCompaction(int step) {
            compactions++;
            lastCompactionStep = step;
        }

        public int getCompactions() {
            return compactions;
        }
    }

    /** 一次 manage 调用的输入 */
    public static class ManageRequest {

        private final Summarizer summarizer;

        private final String agentName;

        /**
         * 本轮任务原文（{@code BaseAgent.currentUserPrompt}）。
         * 2026-10-11 取代原来的 userId 字段：压缩要"重新注入原始任务"，而单会话恢复后
         * 消息列表里的第一条 UserMessage 可能是上一次会话的历史轮次（见 findOriginalTask）。
         */
        private final String originalTask;

        private final String conversationId;

        private final int step;

        private final RunState runState;

        private final AgentRunMetrics metrics;

        public ManageRequest(Summarizer summarizer, String agentName, String originalTask, String conversationId,
                             int step, RunState runState, AgentRunMetrics metrics) {
            this.summarizer = summarizer;
            this.agentName = StrUtil.blankToDefault(agentName, "agent");
            this.originalTask = originalTask;
            this.conversationId = conversationId;
            this.step = step;
            this.runState = runState == null ? new RunState() : runState;
            this.metrics = metrics;
        }

        public Summarizer getSummarizer() {
            return summarizer;
        }

        public String getAgentName() {
            return agentName;
        }

        public String getOriginalTask() {
            return originalTask;
        }

        public String getConversationId() {
            return conversationId;
        }

        public int getStep() {
            return step;
        }

        public RunState getRunState() {
            return runState;
        }

        public AgentRunMetrics getMetrics() {
            return metrics;
        }
    }

    /** 一次 manage 调用的结果 */
    public record Result(List<Message> messages, boolean microcompacted, boolean compacted, int freedTokens) {
    }
}
