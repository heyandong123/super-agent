package com.yupi.yuaiagent.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 单次 Agent 运行的度量快照（2026-10 上下文/记忆重构 · 阶段 1 埋点）
 * <p>
 * 为什么需要它：本项目此前**零埋点**——没有 token 计数、没有耗时、没有压缩事件，
 * 于是「优化有没有效」只能靠感觉。参照 Anthropic《Writing effective tools for agents》
 * 第 8 条（采集 runtime / 调用次数 / token / 错误），也参照 Claude Code 的「先有数据再改机制」：
 * 每个决策点先能看见，再谈优化。没有数字的优化不算完成（CLAUDE.md 终极目标第 2 条）。
 * <p>
 * 输出两种形态：① {@link #toLogLine()} 每 run 一行结构化日志，`grep AGENT_METRICS` 后可直接
 * 交给 jq；② 对象本身，供评测类直接读（{@code BaseAgent.getRunMetrics()}），零文件依赖。
 * <p>
 * 线程模型：字段全部由「跑这一步的 agent 线程」写（advisor 与 act() 都在同一线程上），
 * run 结束后由调用方（评测/日志）读——写入方单线程，故不加锁；跨线程可见性由
 * {@code BaseAgent.runMetrics} 字段的 volatile 保证。
 */
@Getter
public class AgentRunMetrics {

    /** 日志前缀：每 run 一行 {@code AGENT_METRICS {json}}，方便 grep + jq */
    public static final String LOG_PREFIX = "AGENT_METRICS ";

    private static final ObjectMapper JSON = new ObjectMapper();

    // --- 身份与耗时 ---

    /** 短 runId：一次 run 一个，便于把同一 run 的多行日志串起来 */
    private final String runId = UUID.randomUUID().toString().substring(0, 8);

    private final String agentName;

    /** 请求级会话 ID：仅用于日志追踪（2026-10-11 起记忆不再按会话寻址，它的语义只剩"这一行日志属于哪个请求"） */
    private final String conversationId;

    /** 工具暴露模式（full / dynamic）：A/B 对比与线上排查都要靠它区分 */
    private final String mode;

    private final long startNanos = System.nanoTime();

    private String outcome = "running";

    private long endToEndMs;

    private int steps;

    // --- LLM 调用（UsageMetricsAdvisor 写入）---

    private int llmCalls;

    /** 实测 prompt token（来自 ChatResponse 的 usage）；供应商没返回 usage 时保持 0 */
    private int promptTokens;

    /**
     * 单次调用的 prompt token 峰值。
     * <p>
     * 为什么单独记：总量看不出"该压不压"——旧机制按消息条数压缩，完全可能在消息只有 12 条时
     * 把单次 prompt 顶到窗口的 90%（几条大工具输出就够），而压缩一次都没触发。
     * 峰值/窗口 才是"会不会真的溢出"的判据，也是 A/B 里证明预算模式价值的那个数字。
     */
    private int maxPromptTokens;

    private int completionTokens;

    /** usage 缺失的调用次数：缺失率高了说明该模型/该路径拿不到用量，别拿 0 当"省了 token" */
    private int usageMissingCalls;

    /** 调用前用估算器算的 prompt token（与实测成对，用于标定估算口径） */
    private int estimatedPromptTokens;

    /** LLM 调用累计耗时：端到端里有多少是等模型，一眼看清 */
    private long llmMillis;

    // --- 工具调用（ToolCallAgent 写入）---

    private int toolCalls;

    private int toolRetries;

    /** 工具返回以 "Error" 开头的次数（软错误：工具没抛异常但明确返回了失败） */
    private int softErrors;

    /**
     * 重复工具调用次数：同一 run 内「工具名 + 规范化参数」重复出现。
     * 这是微压缩（清旧工具结果）的已知副作用——模型找不到结果就会重新调一遍，
     * 用它把副作用量化，而不是嘴上说"影响不大"。
     */
    private int duplicateToolCalls;

    private final Map<String, Integer> toolCallsByName = new LinkedHashMap<>();

    // --- 上下文管理事件（阶段 2 的 ContextWindowManager 写入，先占位让 JSON 形状稳定）---

    private int microcompactions;

    private int clearedToolResultTokens;

    private int compactions;

    /** 压缩失败回落次数（摘要调用失败，改用尾部截断） */
    private int compactionFallback;

    private int transcriptWrites;

    // --- 流式（2026-10 流式轮：BaseAgent 在「向客户端发内容帧」时写入）---

    /**
     * 首字可见耗时（ms）：run 起点 → 第一个「用户能看到的内容帧」。
     * <p>
     * 两种模式口径一致（都取"用户第一次看到内容"这一刻）：流式模式下是第一个文本增量
     * （边生成边到），阻塞模式下是第一条 process 事件（整步跑完才批量送出）。
     * 这正是本轮 A/B 的主指标——改造前它恒等于"整步耗时"，用户的观感就是"一直空白然后整段砸下来"。
     * <p>
     * -1 = 本 run 没有向客户端发过内容帧（{@code run()} 阻塞链路无 SSE）；
     * 记录幂等（只认第一次），多次调用不会覆盖。
     */
    private long firstVisibleMs = -1;

    /** 文本增量帧数（SSE {@code delta} 事件数）：量化流式实际粒度，也用于核对"流式真的发生过" */
    private int deltaFrames;

    public AgentRunMetrics(String agentName, String conversationId, String mode) {
        this.agentName = agentName;
        this.conversationId = conversationId;
        this.mode = mode;
    }

    /**
     * 记录一次 LLM 调用（{@link UsageMetricsAdvisor} 在 chain.nextCall 前后采集）。
     *
     * @param estimatedPromptToken 调用前的估算值（含工具 schema）
     * @param promptToken          实测 prompt token；拿不到（供应商未返回）时传 null
     * @param completionToken      实测 completion token；拿不到时传 null
     * @param elapsedNanos         本次调用耗时
     */
    public void recordLlmCall(int estimatedPromptToken, Integer promptToken, Integer completionToken, long elapsedNanos) {
        llmCalls++;
        llmMillis += elapsedNanos / 1_000_000;
        estimatedPromptTokens += Math.max(estimatedPromptToken, 0);
        if (promptToken == null || completionToken == null) {
            // usage 缺失不是"0 token"，必须单独计数，否则统计出来的平均 token 会虚假偏低
            usageMissingCalls++;
            return;
        }
        promptTokens += promptToken;
        maxPromptTokens = Math.max(maxPromptTokens, promptToken);
        completionTokens += completionToken;
    }

    /** 记录模型发起的一次工具调用（在 think() 解析出 toolCalls 时调用，代表"模型的意图"） */
    public void recordToolCall(String toolName) {
        toolCalls++;
        toolCallsByName.merge(toolName, 1, Integer::sum);
    }

    public void recordDuplicateToolCall() {
        duplicateToolCalls++;
    }

    /** 一次工具执行的重试（第 2、3 次尝试） */
    public void recordToolRetry() {
        toolRetries++;
    }

    public void recordSoftError() {
        softErrors++;
    }

    public void recordMicrocompaction(int clearedTokens) {
        microcompactions++;
        clearedToolResultTokens += Math.max(clearedTokens, 0);
    }

    public void recordCompaction(boolean fallback) {
        compactions++;
        if (fallback) {
            compactionFallback++;
        }
    }

    public void recordTranscriptWrite() {
        transcriptWrites++;
    }

    /**
     * 记录「第一个内容帧已发给客户端」的时刻（幂等，只认第一次）。
     * 调用点是 {@code BaseAgent.emitTextDelta}（流式：首个文本增量）与
     * {@code BaseAgent.runStream} 的 process 事件发送处（阻塞：整步结束时）——两条路径都覆盖，
     * A/B 才有可比的口径。
     */
    public void recordFirstVisible() {
        if (firstVisibleMs < 0) {
            firstVisibleMs = (System.nanoTime() - startNanos) / 1_000_000;
        }
    }

    /** 记录一帧文本增量（SSE delta 事件） */
    public void recordTextDelta() {
        deltaFrames++;
    }

    /** 收尾：写入结果与总耗时（在 finally 里调用，保证异常路径也有数字） */
    public void finish(String outcome, int steps) {
        this.outcome = outcome;
        this.steps = steps;
        this.endToEndMs = (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** 重复工具调用率：微压缩副作用的量化指标 */
    public double duplicateToolCallRate() {
        return toolCalls == 0 ? 0d : round((double) duplicateToolCalls / toolCalls);
    }

    /**
     * 估算/实测 token 比：标定估算口径用。
     * <p>
     * 稳定在 1 附近说明估算可信；持续 <1 说明在低估（该调大权重）；无 usage 时返回 0 表示"测不出来"。
     */
    public double tokenEstimateRatio() {
        return promptTokens == 0 ? 0d : round((double) estimatedPromptTokens / promptTokens);
    }

    /** 结构化日志行：{@code AGENT_METRICS {"runId":...}} */
    public String toLogLine() {
        return LOG_PREFIX + toJson();
    }

    /**
     * 手建有序 map 再交给 Jackson 序列化：比手拼 JSON 安全（转义交给 Jackson），
     * 又比直接序列化整个对象可控（字段顺序稳定、派生指标能一起出去）。
     * 序列化本身失败也不能影响主链路——回落成最小信息行。
     */
    public String toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("runId", runId);
        map.put("agent", agentName);
        map.put("mode", mode);
        map.put("conversationId", conversationId);
        map.put("outcome", outcome);
        map.put("endToEndMs", endToEndMs);
        map.put("llmMs", llmMillis);
        map.put("steps", steps);
        map.put("llmCalls", llmCalls);
        map.put("promptTokens", promptTokens);
        map.put("maxPromptTokens", maxPromptTokens);
        map.put("completionTokens", completionTokens);
        map.put("usageMissingCalls", usageMissingCalls);
        map.put("estimatedPromptTokens", estimatedPromptTokens);
        map.put("tokenEstimateRatio", tokenEstimateRatio());
        map.put("toolCalls", toolCalls);
        map.put("toolCallsByName", toolCallsByName);
        map.put("toolRetries", toolRetries);
        map.put("softErrors", softErrors);
        map.put("duplicateToolCalls", duplicateToolCalls);
        map.put("duplicateToolCallRate", duplicateToolCallRate());
        map.put("microcompactions", microcompactions);
        map.put("clearedToolResultTokens", clearedToolResultTokens);
        map.put("compactions", compactions);
        map.put("compactionFallback", compactionFallback);
        map.put("transcriptWrites", transcriptWrites);
        // 流式指标追加在尾部：字段顺序在已有键上保持稳定（既有 jq/脚本读法不受影响）
        map.put("firstVisibleMs", firstVisibleMs);
        map.put("deltaFrames", deltaFrames);
        try {
            return JSON.writeValueAsString(map);
        } catch (Exception e) {
            return "{\"runId\":\"" + runId + "\",\"agent\":\"" + agentName + "\",\"outcome\":\"" + outcome
                    + "\",\"endToEndMs\":" + endToEndMs + ",\"serializeError\":true}";
        }
    }

    private static double round(double value) {
        return Math.round(value * 1000d) / 1000d;
    }
}
