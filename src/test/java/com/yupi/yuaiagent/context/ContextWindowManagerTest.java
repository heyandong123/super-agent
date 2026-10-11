package com.yupi.yuaiagent.context;

import com.yupi.yuaiagent.metrics.AgentRunMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文预算模式离线单测（2026-10 上下文重构 · 阶段 2）。
 * <p>
 * 本阶段最重要的测试：压缩是「不可逆 + 花钱」的操作，语义必须能用假摘要器离线验证——
 * 否则每改一行都要烧真实 LLM 调用（阶段 4 的 A/B 只负责给数字，不负责保证语义）。
 * <p>
 * 覆盖：阈值分流 / 微压缩的三条自我约束（keep / clear_at_least / 去重）/ 摘要输入含工具输出（P3 回归）/
 * 受保护项重注入 / 轮次边界对齐 / 失败回落保住"最近"（P5 回归）/ 防 thrashing / transcript 落盘与裁剪。
 * <p>
 * 阈值在测试里被刻意做小（window=1000、无常驻开销 → 350 微压缩 / 500 压缩），
 * 好让"这条消息到底是 800 字符还是 1000 字符"决定走哪条分支。
 */
@Tag("unit")
class ContextWindowManagerTest {

    /** 基准配置：窗口 1000 token、零常驻开销，于是微压缩阈值 350、整体压缩阈值 500 */
    private ContextProperties properties(String mode) {
        ContextProperties properties = new ContextProperties();
        properties.setMode(mode);
        properties.setWindowTokens(1000);
        properties.setStaticOverheadTokens(0);
        properties.setMicrocompactTriggerRatio(0.35);
        properties.setCompactTriggerRatio(0.5);
        properties.setMicrocompactClearAtLeastTokens(100);
        properties.setCompactKeepMessages(4);
        properties.setSummaryFallbackKeepChars(1500);
        properties.setTranscriptEnabled(false);
        return properties;
    }

    /** 只测一级微压缩时用：把二级门槛抬到不可能触发，保证断言看到的是"清工具结果"而不是"摘要" */
    private ContextProperties microcompactOnlyProperties() {
        ContextProperties properties = properties("budget");
        properties.setCompactTriggerRatio(100.0);
        return properties;
    }

    private ContextWindowManager manager(ContextProperties properties, Path transcriptDir) {
        properties.setTranscriptDir(transcriptDir.toString());
        return new ContextWindowManager(properties, new TranscriptWriter(properties));
    }

    /** 一条带结果的工具步：assistant(toolCall) + toolResponse */
    private List<Message> toolStep(String toolName, String callId, String result, String arguments) {
        return List.of(
                new AssistantMessage("", Map.of(),
                        List.of(new AssistantMessage.ToolCall(callId, "function", toolName, arguments))),
                new ToolResponseMessage(List.of(
                        new ToolResponseMessage.ToolResponse(callId, toolName, result))));
    }

    private static String bigResult(int chars) {
        return "x".repeat(chars);
    }

    /** 6 个各带 1000 字符工具结果的历史（≈1500 token，稳定跨过两个阈值） */
    private List<Message> longHistory() {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(ContextWindowManager.MEMORY_SECTION_PREFIX
                + "\n[User Profile]\nnickname=小帅"));
        messages.add(new UserMessage("请把资料整理成文件并保存"));
        for (int i = 1; i <= 6; i++) {
            messages.addAll(toolStep("readFile", "call-" + i, bigResult(1000), "{\"fileName\":\"doc" + i + ".txt\"}"));
        }
        return messages;
    }

    /**
     * 默认 helper 传 {@code originalTask=null}：走"第一条非注入 UserMessage"的兜底扫描
     * （这些用例的消息列表里只有一条任务 UserMessage，兜底扫描同样准确）。
     * 生产路径（BaseAgent 传 currentUserPrompt）的主定位分支由
     * {@link #originalTaskIsLocatedByExactText()} 单测钉住。
     */
    private ContextWindowManager.ManageRequest request(ContextWindowManager.Summarizer summarizer, int step,
                                                       ContextWindowManager.RunState runState,
                                                       AgentRunMetrics metrics) {
        return new ContextWindowManager.ManageRequest(
                summarizer, "yuManus", null, "conv-1", step, runState, metrics);
    }

    /** 断言摘要器绝不该被调用（一级就够了 / 压根没到阈值） */
    private static ContextWindowManager.Summarizer unusedSummarizer() {
        return history -> {
            throw new AssertionError("本用例不该触发摘要调用");
        };
    }

    private static String responseData(List<Message> messages, int index) {
        return ((ToolResponseMessage) messages.get(index)).getResponses().get(0).responseData();
    }

    // ==================== 模式与阈值 ====================

    @Test
    @DisplayName("legacy 模式：原样返回，一条消息都不动（回滚目标必须零副作用）")
    void legacyModeIsNoOp(@TempDir Path dir) {
        ContextWindowManager manager = manager(properties("legacy"), dir);
        List<Message> messages = longHistory();
        List<Message> snapshot = new ArrayList<>(messages);

        ContextWindowManager.Result result = manager.manage(messages,
                request(unusedSummarizer(), 3, new ContextWindowManager.RunState(), null));

        assertFalse(manager.isBudgetMode());
        assertFalse(result.microcompacted());
        assertFalse(result.compacted());
        assertEquals(snapshot, messages, "legacy 下消息列表必须完全不变");
    }

    @Test
    @DisplayName("未达阈值：不动手（按 token 预算判断，不是按消息条数）")
    void belowThresholdDoesNothing(@TempDir Path dir) {
        ContextWindowManager manager = manager(properties("budget"), dir);
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(ContextWindowManager.MEMORY_SECTION_PREFIX));
        messages.add(new UserMessage("你好"));
        List<Message> snapshot = new ArrayList<>(messages);

        manager.manage(messages, request(unusedSummarizer(), 1, new ContextWindowManager.RunState(), null));

        assertEquals(snapshot, messages);
    }

    // ==================== 一级：微压缩 ====================

    @Test
    @DisplayName("微压缩：清旧工具结果、保留最近 3 条与 doTerminate，且不重建列表、不丢 id")
    void microcompactClearsOldToolResults(@TempDir Path dir) {
        ContextWindowManager manager = manager(microcompactOnlyProperties(), dir);
        List<Message> messages = new ArrayList<>(longHistory());
        // 末尾追加一条 doTerminate 结果：它是待校验的任务清单，永远不许清
        messages.addAll(toolStep("doTerminate", "call-term", bigResult(2000), "{\"taskChecklist\":\"done\"}"));
        int sizeBefore = messages.size();

        ContextWindowManager.Result result = manager.manage(messages,
                request(unusedSummarizer(), 3, new ContextWindowManager.RunState(), null));

        assertTrue(result.microcompacted(), "应触发微压缩");
        assertFalse(result.compacted(), "本用例关了二级压缩，不该出现摘要");
        assertTrue(result.freedTokens() > 0);
        assertEquals(sizeBefore, messages.size(), "微压缩是原地替换，不重建列表");

        // 保留窗口 = 最近 3 条工具结果（doTerminate @15、call-6 @13、call-5 @11），
        // 再往前的 call-4/3/2/1（@9/@7/@5/@3）才是清理对象
        for (int index : new int[]{3, 5, 7, 9}) {
            String data = responseData(messages, index);
            assertTrue(data.startsWith(ContextWindowManager.CLEARED_MARKER),
                    "index " + index + " 的旧工具结果应被清理");
            assertTrue(data.contains("readFile"), "占位文案必须带工具名，模型才知道怎么重取");
        }
        // id 必须原样保留：OpenAI 兼容接口按 tool_call_id 关联，对不上就是 400
        assertEquals("call-1", ((ToolResponseMessage) messages.get(3)).getResponses().get(0).id());
        assertEquals("readFile", ((ToolResponseMessage) messages.get(3)).getResponses().get(0).name());
        // 最近的 3 条工具结果与 doTerminate 清单保持原文
        for (int index : new int[]{11, 13}) {
            assertEquals(bigResult(1000), responseData(messages, index), "最近 3 条工具结果不许清");
        }
        assertEquals(bigResult(2000), responseData(messages, messages.size() - 1),
                "doTerminate 的清单是校验依据，必须保留");
    }

    @Test
    @DisplayName("两级分工：清掉旧的大块工具输出就够时，绝不触发要花钱且不可逆的摘要")
    void microcompactAloneSuffices(@TempDir Path dir) {
        ContextProperties properties = properties("budget");   // 真实阈值：350 / 500
        ContextWindowManager manager = manager(properties, dir);
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(ContextWindowManager.MEMORY_SECTION_PREFIX));
        messages.add(new UserMessage("读几份文档，然后总结"));
        // 长会话的典型形状：体积集中在早期的几次大调用上，而它们恰恰最不需要原文。
        // 前两步各 2000 字符（保留窗口之外，可清），最近三步是小输出（保留窗口之内，不动）
        messages.addAll(toolStep("readFile", "call-1", bigResult(2000), "{\"fileName\":\"big1.txt\"}"));
        messages.addAll(toolStep("readFile", "call-2", bigResult(2000), "{\"fileName\":\"big2.txt\"}"));
        for (int i = 3; i <= 5; i++) {
            messages.addAll(toolStep("readFile", "call-" + i, "ok-" + i, "{\"fileName\":\"small" + i + ".txt\"}"));
        }

        ContextWindowManager.Result result = manager.manage(messages,
                request(unusedSummarizer(), 3, new ContextWindowManager.RunState(), null));

        assertTrue(result.microcompacted(), "应触发一级微压缩");
        assertTrue(result.freedTokens() > 500, "清掉两块大输出，释放量应当可观");
        assertFalse(result.compacted(), "一级已把上下文压回阈值之下，二级就不该动手");
        assertTrue(responseData(messages, 3).startsWith(ContextWindowManager.CLEARED_MARKER));
        assertTrue(responseData(messages, 5).startsWith(ContextWindowManager.CLEARED_MARKER));
        assertEquals("ok-5", responseData(messages, messages.size() - 1), "最近 3 条工具结果保持原文");
    }

    @Test
    @DisplayName("clear_at_least：清不出足够多就一点都不清（小碎步清理只会白打穿 prompt cache）")
    void microcompactRespectsClearAtLeast(@TempDir Path dir) {
        ContextProperties properties = microcompactOnlyProperties();
        properties.setMicrocompactClearAtLeastTokens(100_000);
        ContextWindowManager manager = manager(properties, dir);
        List<Message> messages = new ArrayList<>(longHistory());
        List<Message> snapshot = new ArrayList<>(messages);

        ContextWindowManager.Result result = manager.manage(messages,
                request(unusedSummarizer(), 3, new ContextWindowManager.RunState(), null));

        assertFalse(result.microcompacted());
        assertEquals(snapshot, messages, "达不到 clear_at_least 就一点都不许清");
    }

    @Test
    @DisplayName("已清理过的工具结果不会被重复处理（占位文案不会被再截一次）")
    void alreadyClearedResultsAreSkipped(@TempDir Path dir) {
        ContextWindowManager manager = manager(microcompactOnlyProperties(), dir);
        List<Message> messages = new ArrayList<>(longHistory());
        ContextWindowManager.RunState runState = new ContextWindowManager.RunState();
        AgentRunMetrics metrics = new AgentRunMetrics("yuManus", "conv-1", "budget");

        assertTrue(manager.manage(messages, request(unusedSummarizer(), 3, runState, metrics)).microcompacted());
        int freedFirst = metrics.getClearedToolResultTokens();
        String afterFirst = responseData(messages, 3);

        // 第二轮：唯一还没清的候选（@9）已在保留窗口内，候选集为空 → 什么都不该发生
        assertFalse(manager.manage(messages, request(unusedSummarizer(), 4, runState, metrics)).microcompacted());
        assertEquals(afterFirst, responseData(messages, 3), "已清理的占位文案不该被二次处理");
        assertEquals(freedFirst, metrics.getClearedToolResultTokens(), "释放量不该重复累加");
        assertEquals(1, metrics.getMicrocompactions());
    }

    // ==================== 二级：整体压缩 ====================

    @Test
    @DisplayName("压缩：受保护项（记忆块 / 原始任务）原文重注入，摘要块在最前，工具输出进了摘要输入")
    void compactionReinjectsProtectedItems(@TempDir Path dir) {
        ContextProperties properties = properties("budget");
        properties.setMicrocompactEnabled(false);
        ContextWindowManager manager = manager(properties, dir);
        List<Message> messages = new ArrayList<>(longHistory());
        AtomicReference<String> summaryInput = new AtomicReference<>();

        ContextWindowManager.Result result = manager.manage(messages,
                request(history -> {
                    summaryInput.set(history);
                    return "SUMMARY";
                }, 3, new ContextWindowManager.RunState(), null));

        assertTrue(result.compacted());
        // 记忆块原文在最前：旧实现把它一起摘要了，"跨会话记得你是谁"在第一个长任务里就失效
        assertTrue(messages.get(0) instanceof SystemMessage);
        assertTrue(messages.get(0).getText().startsWith(ContextWindowManager.MEMORY_SECTION_PREFIX));
        assertTrue(messages.get(0).getText().contains("nickname=小帅"));
        // 摘要块
        assertTrue(messages.get(1) instanceof SystemMessage);
        assertTrue(messages.get(1).getText().startsWith(ContextWindowManager.SUMMARY_PREFIX));
        assertTrue(messages.get(1).getText().contains("SUMMARY"));
        // 原始任务原文
        assertTrue(messages.get(2) instanceof UserMessage);
        assertEquals("请把资料整理成文件并保存", messages.get(2).getText());
        // 最近文件路径重注入（只给路径，不回读内容）
        assertTrue(messages.get(1).getText().contains("doc6.txt"), "最近用过的文件应被重注入");

        // P3 回归：摘要输入里必须有工具输出（旧实现用 getText() 渲染，工具输出恒为空串）
        assertNotNull(summaryInput.get());
        assertTrue(summaryInput.get().contains("x".repeat(50)), "摘要输入必须包含工具返回内容");
        assertTrue(summaryInput.get().contains("readFile"), "摘要输入应标注是哪个工具的输出");
    }

    @Test
    @DisplayName("原始任务按传入原文精确定位：恢复历史里有同文本时取最后一条，且是列表里的原对象")
    void originalTaskIsLocatedByExactText(@TempDir Path dir) {
        ContextProperties properties = properties("budget");
        properties.setMicrocompactEnabled(false);
        ContextWindowManager manager = manager(properties, dir);
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(ContextWindowManager.MEMORY_SECTION_PREFIX));
        // 恢复的历史轮次：用户上一次也说过同样的话（"复读"场景）
        messages.add(new UserMessage("请把资料整理成文件并保存"));
        messages.add(new AssistantMessage("好的，上一轮已整理过"));
        // 本轮任务：与历史文本完全相同——只有"从后往前找"才拿得到这一条
        messages.add(new UserMessage("请把资料整理成文件并保存"));
        for (int i = 1; i <= 6; i++) {
            messages.addAll(toolStep("readFile", "call-" + i, bigResult(1000), "{\"fileName\":\"doc" + i + ".txt\"}"));
        }
        Message expectedOriginalTask = messages.get(3);

        // 直接构造请求并传入本轮任务原文（生产路径：BaseAgent.getCurrentUserPrompt()）
        AtomicReference<String> summaryInput = new AtomicReference<>();
        ContextWindowManager.ManageRequest request = new ContextWindowManager.ManageRequest(
                history -> {
                    summaryInput.set(history);
                    return "SUMMARY";
                }, "yuManus", "请把资料整理成文件并保存",
                "conv-1", 3, new ContextWindowManager.RunState(), null);
        assertTrue(manager.manage(messages, request).compacted());

        // 重注入的是列表里的<b>原对象</b>（引用相等）——压缩的"跳过受保护消息"与"重注入"
        // 都靠引用判断，新建一条同文本的消息会骗过这两处逻辑
        assertSame(expectedOriginalTask, messages.get(2),
                "应定位到本轮任务（最后一条同文本 UserMessage）并原样重注入");
        // 历史里那条同文本消息是普通内容：参与摘要，而不是被当成原始任务保护起来
        assertNotNull(summaryInput.get());
        assertTrue(summaryInput.get().contains("好的，上一轮已整理过"),
                "历史轮次（含同文本的 user 消息、其后的 assistant 回复）应进摘要输入");
    }

    @Test
    @DisplayName("压缩后尾部不以孤立的工具结果开头（轮次边界对齐，否则接口报 tool_call_id 找不到）")
    void compactionAlignsTurnBoundary(@TempDir Path dir) {
        ContextProperties properties = properties("budget");
        properties.setMicrocompactEnabled(false);
        properties.setCompactKeepMessages(1);   // 尾部只剩 1 条 → 切点必然落在工具结果上，考验对齐逻辑
        ContextWindowManager manager = manager(properties, dir);
        List<Message> messages = new ArrayList<>(longHistory());

        assertTrue(manager.manage(messages,
                request(history -> "SUMMARY", 3, new ContextWindowManager.RunState(), null)).compacted());

        // 压缩后的结构：[记忆][摘要][原始任务][...recent]
        assertTrue(messages.get(2) instanceof UserMessage);
        assertFalse(messages.get(3) instanceof ToolResponseMessage,
                "recent 段的第一条不能是孤立工具结果，必须回退到发起调用的 assistant 消息");
        for (Message message : messages) {
            if (message instanceof ToolResponseMessage toolResponse) {
                String id = toolResponse.getResponses().get(0).id();
                assertTrue(messages.stream().anyMatch(m -> m instanceof AssistantMessage assistant
                                && assistant.getToolCalls().stream().anyMatch(call -> call.id().equals(id))),
                        "工具结果 " + id + " 缺少对应的 tool_call（接口会直接 400）");
            }
        }
    }

    @Test
    @DisplayName("短会话：前段只剩受保护项 → 拒绝压缩（压了也不省 token），且不为没发生的压缩白写 transcript")
    void refusesCompactionWithNothingToSummarize(@TempDir Path dir) throws Exception {
        ContextProperties properties = properties("budget");
        properties.setMicrocompactEnabled(false);
        properties.setTranscriptEnabled(true);
        ContextWindowManager manager = manager(properties, dir);
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(ContextWindowManager.MEMORY_SECTION_PREFIX + "\n[User Profile]"));
        messages.add(new UserMessage("搜一下再写文件"));
        messages.add(new UserMessage("[下一步指令]"));   // think() 注入的 nextStepPrompt
        // 体积很大 → 稳定越过压缩阈值，但尾部 keep 条已经把全部内容盖住，前段只剩"要原文保留"的两项
        messages.addAll(toolStep("searchWeb", "call-1", bigResult(6000), "{\"query\":\"spring ai\"}"));
        AgentRunMetrics metrics = new AgentRunMetrics("yuManus", "conv-1", "budget");

        ContextWindowManager.Result result = manager.manage(messages,
                request(unusedSummarizer(), 2, new ContextWindowManager.RunState(), metrics));

        assertFalse(result.compacted(), "没有可摘要的内容，压缩只会白花一次 LLM 调用");
        assertEquals(5, messages.size(), "拒绝压缩时消息列表必须原样");
        assertFalse(Files.exists(dir.resolve("conv-1")),
                "被拒绝的压缩不该留下 transcript：它占着份数配额，会把真正的证据挤掉（真机冒烟发现）");
        assertEquals(0, metrics.getTranscriptWrites());
        assertEquals(0, metrics.getCompactions());
    }

    @Test
    @DisplayName("摘要失败回落：保住最近的内容，而不是最老的（P5 回归）")
    void summaryFallbackKeepsRecentContent(@TempDir Path dir) {
        ContextProperties properties = properties("budget");   // summaryFallbackKeepChars = 1500
        properties.setMicrocompactEnabled(false);
        ContextWindowManager manager = manager(properties, dir);
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(ContextWindowManager.MEMORY_SECTION_PREFIX));
        messages.add(new UserMessage("原始任务"));
        for (int i = 1; i <= 6; i++) {
            messages.addAll(toolStep("readFile", "call-" + i,
                    "MARKER-" + i + "-" + bigResult(800), "{\"fileName\":\"doc" + i + ".txt\"}"));
        }
        AgentRunMetrics metrics = new AgentRunMetrics("yuManus", "conv-1", "budget");

        boolean compacted = manager.manage(messages, request(history -> {
            throw new IllegalStateException("模拟摘要调用失败");
        }, 3, new ContextWindowManager.RunState(), metrics)).compacted();

        assertTrue(compacted, "摘要失败也要保住最近上下文，而不是干脆放弃压缩");
        String summaryBlock = messages.get(1).getText();
        assertTrue(summaryBlock.contains("MARKER-4"), "回落内容必须含被摘要段里最新的记录");
        assertFalse(summaryBlock.contains("MARKER-1-"), "被摘要段里最老的记录应当丢掉（旧实现恰好相反）");
        assertTrue(summaryBlock.startsWith(ContextWindowManager.SUMMARY_PREFIX));
        // 失败回落也要记进指标，否则"压缩失败率"永远看不见
        assertEquals(1, metrics.getCompactions());
        assertEquals(1, metrics.getCompactionFallback());
        // recent 段原样保留（尾部 keep 条从不参与压缩）
        assertTrue(messages.stream().anyMatch(m -> m instanceof ToolResponseMessage tool
                && String.valueOf(tool.getResponses().get(0).responseData()).contains("MARKER-6")),
                "recent 段的原文必须保留");
    }

    @Test
    @DisplayName("防 thrashing：最小间隔步数内不重复压缩，次数上限后不再压")
    void compactionThrottling(@TempDir Path dir) {
        ContextProperties properties = properties("budget");
        properties.setMicrocompactEnabled(false);
        properties.setCompactMinStepsBetween(2);
        properties.setCompactMaxPerRun(2);
        ContextWindowManager manager = manager(properties, dir);
        ContextWindowManager.RunState runState = new ContextWindowManager.RunState();
        AtomicInteger summaryCalls = new AtomicInteger();
        ContextWindowManager.Summarizer summarizer = history -> {
            summaryCalls.incrementAndGet();
            return "SUMMARY";
        };

        assertTrue(manager.manage(new ArrayList<>(longHistory()),
                request(summarizer, 3, runState, null)).compacted(), "首次压缩");
        assertFalse(manager.manage(new ArrayList<>(longHistory()),
                request(summarizer, 4, runState, null)).compacted(), "距上次压缩只隔 1 步，不该再压");
        assertTrue(manager.manage(new ArrayList<>(longHistory()),
                request(summarizer, 5, runState, null)).compacted(), "隔够 2 步可以再压（第 2 次）");
        assertFalse(manager.manage(new ArrayList<>(longHistory()),
                request(summarizer, 9, runState, null)).compacted(), "已达单 run 上限，不再压缩");
        assertEquals(2, summaryCalls.get(), "摘要调用次数应等于实际压缩次数（不该有白花的调用）");
    }

    // ==================== transcript ====================

    @Test
    @DisplayName("压缩前先落盘 transcript（不可逆操作前留证据），并按会话裁剪份数")
    void transcriptIsWrittenBeforeCompaction(@TempDir Path dir) throws Exception {
        ContextProperties properties = properties("budget");
        properties.setMicrocompactEnabled(false);
        properties.setTranscriptEnabled(true);
        properties.setTranscriptKeepPerConversation(2);
        ContextWindowManager manager = manager(properties, dir);

        for (int i = 0; i < 4; i++) {
            assertTrue(manager.manage(new ArrayList<>(longHistory()),
                    request(history -> "SUMMARY", 3 + i, new ContextWindowManager.RunState(), null)).compacted());
        }

        // 2026-10-11 单用户单会话：目录去掉 userId 层，只剩 transcripts/{conversationId}
        Path conversationDir = dir.resolve("conv-1");
        assertTrue(Files.isDirectory(conversationDir), "transcript 目录应存在");
        List<Path> files;
        try (var stream = Files.list(conversationDir)) {
            files = stream.sorted().toList();
        }
        assertEquals(2, files.size(), "每会话只保留最近 2 份");
        // 文件名形如 step-005-<epochMs>-compact.jsonl：零填充的步数前缀即时间序，裁剪按它排序
        assertTrue(files.get(0).getFileName().toString().startsWith("step-005-"), "最旧的应被裁掉");
        assertTrue(files.get(1).getFileName().toString().startsWith("step-006-"), "最新的应保留");
        String content = Files.readString(files.get(files.size() - 1));
        assertTrue(content.contains("readFile"), "transcript 里必须有工具调用记录");
        assertTrue(content.contains("x".repeat(50)), "transcript 必须含工具返回内容（不是 getText() 的空串）");
    }

    @Test
    @DisplayName("微压缩也先留证据：被清掉的工具原文只有这份快照还留着")
    void microcompactWritesTranscriptBeforeClearing(@TempDir Path dir) throws Exception {
        ContextProperties properties = microcompactOnlyProperties();
        properties.setTranscriptEnabled(true);
        ContextWindowManager manager = manager(properties, dir);
        List<Message> messages = new ArrayList<>(longHistory());

        assertTrue(manager.manage(messages,
                request(unusedSummarizer(), 2, new ContextWindowManager.RunState(), null)).microcompacted());

        Path conversationDir = dir.resolve("conv-1");
        List<Path> files;
        try (var stream = Files.list(conversationDir)) {
            files = stream.toList();
        }
        assertEquals(1, files.size(), "一次微压缩对应一份快照");
        assertTrue(files.get(0).getFileName().toString().endsWith("-microcompact.jsonl"), "文件名要能看出是哪级操作");
        assertTrue(Files.readString(files.get(0)).contains("x".repeat(1000)),
                "快照必须是被清掉之前的原文（只在二级压缩前落盘的话，这里早已是占位文案）");
    }

    @Test
    @DisplayName("transcript 关闭时不落盘，也不影响压缩")
    void transcriptCanBeDisabled(@TempDir Path dir) {
        ContextProperties properties = properties("budget");
        properties.setMicrocompactEnabled(false);
        ContextWindowManager manager = manager(properties, dir);

        ContextWindowManager.Result result = manager.manage(new ArrayList<>(longHistory()),
                request(history -> "SUMMARY", 3, new ContextWindowManager.RunState(), null));

        assertTrue(result.compacted());
        assertFalse(Files.exists(dir.resolve("conv-1")), "关闭时不该建目录");
    }

    // ==================== 其他 ====================

    @Test
    @DisplayName("提取最近文件路径：只认文件类工具，去重并保留最后 N 个")
    void extractsRecentFilePaths(@TempDir Path dir) {
        ContextWindowManager manager = manager(properties("budget"), dir);
        List<Message> messages = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            messages.addAll(toolStep("readFile", "call-" + i, "ok", "{\"fileName\":\"doc" + i + ".txt\"}"));
        }
        messages.addAll(toolStep("searchWeb", "call-s", "ok", "{\"query\":\"doc8\"}"));

        List<String> paths = manager.extractRecentFilePaths(messages);

        assertEquals(5, paths.size(), "默认只留最近 5 个");
        assertEquals("doc3.txt", paths.get(0));
        assertEquals("doc7.txt", paths.get(4));
    }

    @Test
    @DisplayName("渲染器两条路径共用：工具输出与工具调用参数都进渲染结果（legacy 默认路径也吃这条）")
    void renderForSummaryIsSharedWithLegacyPath(@TempDir Path dir) {
        // legacy 模式下 manager 不参与触发，但它的渲染器仍被 ToolCallAgent.summarizeMessages 调用：
        // P3（getText() 恒为空串）是两条路径共有的 bug，修复也必须共有，否则默认路径依旧在摘要空串。
        ContextWindowManager manager = manager(properties("legacy"), dir);

        String rendered = manager.renderForSummary(
                toolStep("readFile", "call-1", "文件正文：向量检索的三个要点", "{\"fileName\":\"doc1.txt\"}"));

        assertTrue(rendered.contains("文件正文：向量检索的三个要点"), "工具输出必须进渲染结果");
        assertTrue(rendered.contains("readFile"), "渲染结果要标注工具名");
        assertTrue(rendered.contains("doc1.txt"), "工具调用参数也要在，模型才知道读过哪个文件");
    }

    @Test
    @DisplayName("manage 原地改动并返回同一份列表引用（调用方 setMessageList 不会丢内容）")
    void returnsSameListReference(@TempDir Path dir) {
        ContextWindowManager manager = manager(properties("budget"), dir);
        List<Message> messages = new ArrayList<>(longHistory());
        ContextWindowManager.Result result = manager.manage(messages,
                request(history -> "SUMMARY", 3, new ContextWindowManager.RunState(), null));
        assertSame(messages, result.messages());
    }
}
