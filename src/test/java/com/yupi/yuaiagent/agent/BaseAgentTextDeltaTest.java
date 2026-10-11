package com.yupi.yuaiagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.yuaiagent.agent.model.AgentState;
import com.yupi.yuaiagent.metrics.AgentRunMetrics;
import com.yupi.yuaiagent.stream.StreamDisconnectedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文本增量发射口离线单测（2026-10 流式轮）。
 * <p>
 * 覆盖三件容易出错的事：① delta 事件 JSON 的转义（一个没转义的引号就让前端整帧丢失）；
 * ② 发送失败必须「置 ERROR + 抛专用异常 + 清出口」——被吞掉的话断连后循环还会继续烧 token；
 * ③ 无出口（run() 阻塞链路/未安装）时是纯 no-op，绝不能影响既有链路。
 */
@Tag("unit")
class BaseAgentTextDeltaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 最小 Agent：不碰 LLM、不碰工具 */
    private static class StubAgent extends BaseAgent {
        StubAgent() {
            super(null);
            setName("stub-agent");
        }

        @Override
        public String step() {
            return "ok";
        }
    }

    @Test
    @DisplayName("delta 事件 JSON：字段齐全 + 控制字符正确转义（引号/换行/制表/\\u0001）")
    void deltaEventJsonIsEscapedAndParseable() throws Exception {
        StubAgent agent = new StubAgent();
        String json = agent.buildDeltaEventJson("think", 2, "他说：\"你好\"\n\t第二行\u0001结束");

        JsonNode node = MAPPER.readTree(json);
        assertEquals("delta", node.get("type").asText());
        assertEquals("think", node.get("scope").asText());
        assertEquals(2, node.get("step").asInt());
        // 转义对了才解析得回来：解析后的文本必须与原文逐字符一致
        assertEquals("他说：\"你好\"\n\t第二行\u0001结束", node.get("text").asText());
    }

    @Test
    @DisplayName("没有增量出口（run() 链路）：emitTextDelta 是 no-op，不抛异常、不记埋点")
    void emitWithoutSinkIsNoop() {
        StubAgent agent = new StubAgent();
        AgentRunMetrics metrics = agent.startRunMetrics();

        assertDoesNotThrow(() -> agent.emitTextDelta("think", "你好"));

        assertEquals(0, metrics.getDeltaFrames());
        assertEquals(-1, metrics.getFirstVisibleMs(), "没发出去就不该有首字时间");
    }

    @Test
    @DisplayName("有出口：增量转发给 sink、帧数累加、首字时间只记第一次")
    void emitForwardsAndRecordsFirstVisibleOnce() {
        StubAgent agent = new StubAgent();
        AgentRunMetrics metrics = agent.startRunMetrics();
        List<String> received = new ArrayList<>();
        agent.setTextDeltaSink((scope, text) -> received.add(scope + ":" + text));

        agent.emitTextDelta("think", "你");
        agent.emitTextDelta("think", "好");
        long firstVisibleAfterFirst = metrics.getFirstVisibleMs();
        agent.emitTextDelta("final", "！");

        assertEquals(List.of("think:你", "think:好", "final:！"), received);
        assertEquals(3, metrics.getDeltaFrames());
        assertTrue(firstVisibleAfterFirst >= 0, "首个增量必须记下首字时间（A/B 的主指标）");
        assertEquals(firstVisibleAfterFirst, metrics.getFirstVisibleMs(), "首字时间幂等，不被后续增量覆盖");
    }

    @Test
    @DisplayName("空文本不发：不产生帧、不调 sink")
    void emptyTextIsNotEmitted() {
        StubAgent agent = new StubAgent();
        AgentRunMetrics metrics = agent.startRunMetrics();
        List<String> received = new ArrayList<>();
        agent.setTextDeltaSink((scope, text) -> received.add(text));

        agent.emitTextDelta("think", "");
        agent.emitTextDelta("think", null);

        assertTrue(received.isEmpty());
        assertEquals(0, metrics.getDeltaFrames());
    }

    @Test
    @DisplayName("发送失败（客户端断开）：置 ERROR + 清出口 + 抛专用异常；之后不再尝试，也不计帧数")
    void sendFailureAbortsStreamAndStops() {
        StubAgent agent = new StubAgent();
        AgentRunMetrics metrics = agent.startRunMetrics();
        agent.setState(AgentState.RUNNING);
        agent.setTextDeltaSink((scope, text) -> {
            throw new IOException("Broken pipe");
        });

        // 抛专用类型：think()/step() 会对它单独放行，一路传到 runStream 的收尾逻辑
        StreamDisconnectedException ex = assertThrows(StreamDisconnectedException.class,
                () -> agent.emitTextDelta("think", "第一段"));
        assertEquals("Broken pipe", ex.getCause().getMessage());
        assertEquals(AgentState.ERROR, agent.getState(), "状态必须立刻挪出 RUNNING：循环条件是 state == RUNNING");
        assertNull(agent.getTextDeltaSink(), "出口要清掉：后续内部调用回落阻塞，不会再触发第二次失败");
        // 没送达就不计数：否则断连的 run 也会在报告里显示"流式正常"
        assertEquals(0, metrics.getDeltaFrames(), "发失败的帧不算送达");
        assertEquals(-1, metrics.getFirstVisibleMs());
        // 出口已清 → 再发是 no-op（fail-open），异常不再重复抛出
        assertDoesNotThrow(() -> agent.emitTextDelta("final", "第二段"));
    }

    @Test
    @DisplayName("cleanup 清空流式运行态：出口不泄漏到下一次 run / 收尾的记忆写入")
    void cleanupClearsStreamingState() {
        StubAgent agent = new StubAgent();
        agent.setTextDeltaSink((scope, text) -> {
        });
        agent.setMessageList(new ArrayList<>(List.of(new org.springframework.ai.chat.messages.UserMessage("x"))));

        agent.cleanup();

        assertNull(agent.getTextDeltaSink());
        assertTrue(agent.getMessageList().isEmpty());
    }
}
