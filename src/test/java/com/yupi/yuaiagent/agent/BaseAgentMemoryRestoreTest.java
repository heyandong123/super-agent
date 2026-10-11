package com.yupi.yuaiagent.agent;

import com.yupi.yuaiagent.agent.model.AgentState;
import com.yupi.yuaiagent.context.ContextProperties;
import com.yupi.yuaiagent.memory.ConversationTurn;
import com.yupi.yuaiagent.memory.FileConversationHistoryRepository;
import com.yupi.yuaiagent.memory.FileRecentSummaryRepository;
import com.yupi.yuaiagent.memory.FileUserProfileRepository;
import com.yupi.yuaiagent.memory.MemoryAssembler;
import com.yupi.yuaiagent.memory.MemoryHistoryProperties;
import com.yupi.yuaiagent.memory.MemoryUpdateService;
import com.yupi.yuaiagent.memory.SessionMetadataResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 近场历史恢复的离线钉死测试（2026-10-11 单用户单会话重构）。
 * <p>
 * <b>为什么用「钉死」这个词</b>：恢复的条数与 legacy 压缩阈值之间存在算术耦合——
 * 默认恢复 6 轮 = 12 条消息，加记忆 SystemMessage + 本轮用户消息 + nextStepPrompt
 * 恰好 15 条，而压缩触发条件是<b>严格大于</b> 15。也就是说：纯聊天与单步任务
 * <b>不因恢复而付出压缩代价</b>，恢复 7 轮则首查即 16 条、必然触发。
 * 这个平衡要被测试固定住，任何一方（阈值 / 默认轮数 / 恢复条数）单独改动都必须让测试变红。
 * <p>
 * 覆盖四条不变量：
 * <ol>
 *   <li>消息顺序 [记忆 SM → 2N 条历史 → 本轮用户消息]，且恢复严格 2N 条、无包装头；</li>
 *   <li>首查 size ≤ 阈值（用 ToolCallAgent.MAX_CONTEXT_MESSAGES 真常量断言）；</li>
 *   <li>memoryWriteEnabled=false 不恢复也不写；</li>
 *   <li>失败轮（ERROR）不写 history（但不影响画像/摘要既有行为）。</li>
 * </ol>
 */
@Tag("unit")
class BaseAgentMemoryRestoreTest {

    private static final String CURRENT_TASK = "帮我查一下今天的安排";

    @TempDir
    Path tempDir;

    private FileConversationHistoryRepository historyRepository;

    private MemoryUpdateService memoryUpdateService;

    @BeforeEach
    void setUp() {
        historyRepository = new FileConversationHistoryRepository(tempDir.toFile(), 200);
        memoryUpdateService = mock(MemoryUpdateService.class);
    }

    /** 造一个真实的 MemoryAssembler（真文件仓库 + 真配置对象），只对 LLM/向量部分不做要求 */
    private MemoryAssembler assembler(int restoreTurns, int maxCharsPerMessage) {
        MemoryHistoryProperties historyProperties = new MemoryHistoryProperties();
        historyProperties.setRestoreTurns(restoreTurns);
        historyProperties.setMaxCharsPerMessage(maxCharsPerMessage);
        return new MemoryAssembler(
                new SessionMetadataResolver(),
                new FileUserProfileRepository(tempDir.toFile()),
                new FileRecentSummaryRepository(tempDir.toFile()),
                historyRepository,
                new ContextProperties(),
                historyProperties);
    }

    private StubAgent agent(int restoreTurns, int maxCharsPerMessage) {
        StubAgent agent = new StubAgent(false);
        agent.setMaxSteps(1);
        agent.setMemoryAssembler(assembler(restoreTurns, maxCharsPerMessage));
        agent.setMemoryUpdateService(memoryUpdateService);
        return agent;
    }

    private void seedHistory(int turns) throws Exception {
        for (int i = 1; i <= turns; i++) {
            historyRepository.append(new ConversationTurn(
                    "2026-10-11T10:0" + i + ":00+08:00",
                    "历史问题" + i,
                    "历史回答" + i,
                    "stub-agent"));
        }
    }

    @Test
    @DisplayName("恢复顺序与条数：[记忆 SM → 2N 条历史 → 本轮用户消息]，严格 2N 条、无包装头")
    void restoresHistoryBetweenMemoryBlockAndCurrentPrompt() throws Exception {
        seedHistory(6);

        StubAgent agent = agent(6, 1200);
        agent.run(CURRENT_TASK);
        List<Message> messages = agent.snapshot();

        // 1（记忆块）+ 2N（历史）+ 1（本轮用户消息）
        assertEquals(14, messages.size(), "恢复必须是严格 2N 条原文，不许加任何包装 Head");

        assertInstanceOf(SystemMessage.class, messages.get(0), "第一条是记忆块（画像/摘要/元数据）");
        assertTrue(messages.get(0).getText().startsWith("[Session Metadata]"),
                "记忆块内容应由 MemoryAssembler 渲染，实际: " + messages.get(0).getText());

        for (int turn = 0; turn < 6; turn++) {
            int index = 1 + turn * 2;
            Message user = messages.get(index);
            Message assistant = messages.get(index + 1);
            assertInstanceOf(UserMessage.class, user, "第 " + (turn + 1) + " 轮应是 User/Assistant 消息对");
            assertInstanceOf(AssistantMessage.class, assistant);
            assertEquals("历史问题" + (turn + 1), user.getText(), "历史顺序即文件时间序");
            assertEquals("历史回答" + (turn + 1), assistant.getText());
        }

        Message last = messages.get(messages.size() - 1);
        assertInstanceOf(UserMessage.class, last);
        assertEquals(CURRENT_TASK, last.getText(), "本轮用户消息必须排在恢复的历史之后（模型才把历史当「背景」）");
    }

    @Test
    @DisplayName("算术钉死：默认 6 轮时首查 size=15 恰好不触发压缩；7 轮必然触发")
    void restoreTurnsAlignWithLegacyCompactionThreshold() throws Exception {
        int defaultRestoreTurns = new MemoryHistoryProperties().getRestoreTurns();
        assertEquals(6, defaultRestoreTurns,
                "默认恢复轮数被改动——先读 MemoryHistoryProperties 的算术注释，再同步改这条测试");

        // 真实快照（step 之前）：1 记忆块 + 2N 历史 + 1 本轮用户消息
        seedHistory(defaultRestoreTurns);
        StubAgent agent = agent(defaultRestoreTurns, 1200);
        agent.run(CURRENT_TASK);
        int snapshotSize = agent.snapshot().size();
        assertEquals(defaultRestoreTurns * 2 + 2, snapshotSize);

        // 首查发生在 think() 里、追加 nextStepPrompt 之后（ToolCallAgent 的 legacy 压缩判定）
        int firstThinkSize = snapshotSize + 1;
        assertTrue(firstThinkSize <= ToolCallAgent.MAX_CONTEXT_MESSAGES,
                "首查 " + firstThinkSize + " 条必须 ≤ 阈值 " + ToolCallAgent.MAX_CONTEXT_MESSAGES
                        + "（触发条件是严格大于）——否则纯聊天与单步任务也会因恢复被压缩");
        assertTrue((defaultRestoreTurns + 1) * 2 + 3 > ToolCallAgent.MAX_CONTEXT_MESSAGES,
                "恢复 7 轮必然触发压缩——这正是默认值取 6 而不是 7 的原因");
    }

    @Test
    @DisplayName("超长历史按 max-chars-per-message 截断：不回放全文（每步 LLM 调用都要重复付费）")
    void oversizedHistoryIsTruncatedOnRestore() throws Exception {
        historyRepository.append(new ConversationTurn(
                "2026-10-11T10:01:00+08:00", "问题", "长".repeat(2000), "stub-agent"));

        StubAgent agent = agent(6, 100);
        agent.run(CURRENT_TASK);
        List<Message> messages = agent.snapshot();

        Message assistant = messages.get(2);
        assertInstanceOf(AssistantMessage.class, assistant);
        assertEquals(100 + "…（历史已截断）".length(), assistant.getText().length(),
                "截断必须发生在恢复注入之前，而不是等到上下文撑爆");
        assertTrue(assistant.getText().endsWith("…（历史已截断）"));
    }

    @Test
    @DisplayName("memoryWriteEnabled=false：不恢复历史、不写任何记忆（子 Agent 语义）")
    void disabledAgentNeitherRestoresNorWrites() throws Exception {
        seedHistory(6);

        StubAgent agent = agent(6, 1200);
        agent.setMemoryWriteEnabled(false);
        agent.run(CURRENT_TASK);
        List<Message> messages = agent.snapshot();

        assertEquals(2, messages.size(), "只有记忆块 + 本轮用户消息，没有历史前缀");
        assertInstanceOf(SystemMessage.class, messages.get(0));
        assertEquals(CURRENT_TASK, messages.get(1).getText());

        verify(memoryUpdateService, never()).recordAgentInteraction(anyString(), anyString(), anyString());
        verify(memoryUpdateService, never()).appendHistory(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("失败轮：画像/摘要照写（既有行为），但不追加 history（「出错了」不许回放进下一轮）")
    void failedRunSkipsHistoryButKeepsProfileAndSummary() throws Exception {
        StubAgent agent = new StubAgent(true);
        agent.setMaxSteps(1);
        agent.setMemoryAssembler(assembler(6, 1200));
        agent.setMemoryUpdateService(memoryUpdateService);

        String output = agent.run(CURRENT_TASK);

        assertTrue(output.startsWith("出错了："), "前置条件：本 run 确实失败了");
        assertTrue(agent.getLastRunMetrics().getOutcome().contains("ERROR"),
                "前置条件：指标里记录的是 ERROR（runFailed 快照的来源）");
        verify(memoryUpdateService, times(1)).recordAgentInteraction(anyString(), anyString(), anyString());
        verify(memoryUpdateService, never()).appendHistory(anyString(), anyString(), anyString());
    }

    /**
     * 最小可用 Agent：不碰 LLM、不碰工具。
     * <p>
     * 快照在 {@code step()} 里取——此时 run() 已完成「记忆注入 + 本轮用户消息」且还没走 cleanup
     * （cleanup 会清空 messageList，run 返回后什么都看不到）。
     */
    private static class StubAgent extends BaseAgent {

        private final boolean failOnStep;

        private List<Message> snapshot;

        private StubAgent(boolean failOnStep) {
            // 2026-10 流式轮：显式传 null = 不启用流式（本测试只关心记忆恢复顺序，钉在阻塞语义上）
            super(null);
            this.failOnStep = failOnStep;
            setName("stub-agent");
        }

        @Override
        public String step() {
            if (snapshot == null) {
                snapshot = List.copyOf(getMessageList());
            }
            if (failOnStep) {
                throw new RuntimeException("模拟步骤失败");
            }
            setState(AgentState.FINISHED);
            return "完成";
        }

        List<Message> snapshot() {
            return snapshot;
        }
    }
}
