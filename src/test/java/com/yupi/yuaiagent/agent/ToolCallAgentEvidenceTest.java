package com.yupi.yuaiagent.agent;

import com.yupi.yuaiagent.toolrouter.ToolRoutingProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具执行证据环形缓冲的离线单测（2026-10 上下文重构 · 阶段 2）。
 * <p>
 * 为什么要专门测它：{@code doTerminate} 的三方校验（原始任务 + 模型自述清单 + 工具执行证据）
 * 原来是从 {@code messageList} 倒扫 ToolResponseMessage 拼证据的，而上下文压缩会把工具结果
 * 替换成占位文案——压缩之后"证据"就变成了"[工具结果已清理以节省上下文]…"，
 * 于是长任务一旦触发压缩，终止校验必然误判"任务没做完"，模型被要求继续干活，
 * 形成"压缩 → 校验失败 → 再压缩"的活锁。
 * <p>
 * 修法是把证据写进与消息列表解耦的环形缓冲（工具刚执行完、结果还是原文时写入）。
 * 本测试直接喂消息进缓冲，断言：入缓冲、doTerminate 被排除、上限 8 条、内容不受压缩影响。
 */
@Tag("unit")
class ToolCallAgentEvidenceTest {

    /**
     * 空工具集 + full 暴露模式（默认）：构造器只会额外挂一个 searchMemory 元工具，全程离线。
     * 末位 streamingProperties 传 null：本测试不碰 think()，显式钉在「不启用流式」的阻塞语义上。
     */
    private ToolCallAgent newAgent() {
        return new ToolCallAgent(new ToolCallback[0], null, new ToolRoutingProperties(), null, null, null);
    }

    private ToolResponseMessage toolResponse(String name, String data) {
        return new ToolResponseMessage(List.of(
                new ToolResponseMessage.ToolResponse("call-" + name, name, data)));
    }

    @Test
    @DisplayName("空缓冲：返回明确的「无记录」提示（模型据此知道确实没执行过工具）")
    void emptyBufferHasExplicitHint() {
        assertEquals("（无工具执行记录）", newAgent().buildToolExecutionEvidence());
    }

    @Test
    @DisplayName("执行过的工具进证据：工具名 + 结果摘要，多行平铺（三方校验直接读它）")
    void recordsToolExecutions() {
        ToolCallAgent agent = newAgent();

        agent.recordToolExecutionEvidence(toolResponse("readFile", "文件内容：第一行\n第二行"));
        agent.recordToolExecutionEvidence(toolResponse("writeFile", "写入成功: tmp/a.txt"));

        String evidence = agent.buildToolExecutionEvidence();
        assertTrue(evidence.contains("- readFile: 文件内容：第一行 第二行"), "换行要被压平，证据才是「一行一条」");
        assertTrue(evidence.contains("- writeFile: 写入成功: tmp/a.txt"));
        assertEquals(2, evidence.lines().count());
    }

    @Test
    @DisplayName("doTerminate 不进证据：那是模型自述的清单，拿它当证据等于自己证明自己")
    void terminateCallsAreExcluded() {
        ToolCallAgent agent = newAgent();

        agent.recordToolExecutionEvidence(toolResponse("doTerminate", "{\"taskChecklist\":\"已写文件\"}"));

        assertEquals("（无工具执行记录）", agent.buildToolExecutionEvidence(),
                "终止调用本身就是被校验的对象，不能进证据链");
    }

    @Test
    @DisplayName("环形缓冲上限 8 条：只留最近的，防止证据随步数无限增长")
    void keepsOnlyRecentEvidence() {
        ToolCallAgent agent = newAgent();

        for (int i = 1; i <= 11; i++) {
            agent.recordToolExecutionEvidence(toolResponse("readFile", "result-" + i));
        }

        String evidence = agent.buildToolExecutionEvidence();
        assertEquals(8, evidence.lines().count());
        assertFalse(evidence.contains("result-1 "), "最早的记录应被挤出缓冲");
        assertTrue(evidence.contains("- readFile: result-11"), "最近的记录必须在");
    }

    @Test
    @DisplayName("超长结果被截断到 100 字符：证据是给校验用的摘要，不是第二份上下文")
    void truncatesLongOutput() {
        ToolCallAgent agent = newAgent();

        agent.recordToolExecutionEvidence(toolResponse("readFile", "y".repeat(500)));

        String evidence = agent.buildToolExecutionEvidence();
        assertEquals(100 + "...".length(), evidence.replace("- readFile: ", "").length());
        assertTrue(evidence.endsWith("..."));
    }

    @Test
    @DisplayName("空输出留占位而不是空行（「调了但没输出」与「没调用过」必须可区分）")
    void blankOutputGetsPlaceholder() {
        ToolCallAgent agent = newAgent();

        agent.recordToolExecutionEvidence(toolResponse("searchWeb", "   "));

        assertTrue(agent.buildToolExecutionEvidence().contains("- searchWeb: （无输出）"));
    }

    @Test
    @DisplayName("证据不受后续上下文压缩影响（压缩把消息列表里的结果换成占位符，证据仍是原文）")
    void evidenceSurvivesCompaction() {
        ToolCallAgent agent = newAgent();
        agent.recordToolExecutionEvidence(toolResponse("readFile", "关键结论：A 方案可行"));

        // 模拟微压缩：消息列表里的那条工具结果被替换成占位文案
        String placeholder = "[工具结果已清理以节省上下文] 工具 readFile 的返回内容已被移除。";
        List<Message> messageList = new ArrayList<>(List.of(
                new SystemMessage("[Session Metadata]"),
                new UserMessage("读一下方案"),
                toolResponse("readFile", placeholder)));
        agent.setMessageList(messageList);

        String evidence = agent.buildToolExecutionEvidence();
        assertTrue(evidence.contains("关键结论：A 方案可行"), "证据必须来自缓冲，而不是被压缩过的消息列表");
        assertFalse(evidence.contains("工具结果已清理"), "占位文案绝不能进证据链");
    }

    @Test
    @DisplayName("cleanup 清空证据：run 级状态不能跨请求残留（Agent 每请求 new，但 test 直接复用实例）")
    void cleanupResetsEvidence() {
        ToolCallAgent agent = newAgent();
        agent.recordToolExecutionEvidence(toolResponse("readFile", "上一次 run 的结果"));

        agent.cleanup();

        assertEquals("（无工具执行记录）", agent.buildToolExecutionEvidence());
    }
}
