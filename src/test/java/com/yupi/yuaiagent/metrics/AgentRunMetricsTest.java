package com.yupi.yuaiagent.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentRunMetrics 离线单测（2026-10 上下文重构 · 阶段 1 埋点）。
 * <p>
 * 只测纯计算：累加口径、派生指标、日志行的 JSON 形状。真实 usage 采集在
 * {@link UsageMetricsAdvisorTest} 里用 mock 的链验证，端到端 token 数字靠阶段 4 的 A/B。
 */
@Tag("unit")
class AgentRunMetricsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("LLM 调用：token 累加 + 耗时累加 + 估算值累加")
    void recordsLlmCalls() {
        AgentRunMetrics metrics = new AgentRunMetrics("yuManus", "conv-1", "full");
        metrics.recordLlmCall(1000, 900, 120, 2_000_000_000L);
        metrics.recordLlmCall(500, 400, 80, 1_000_000_000L);

        assertEquals(2, metrics.getLlmCalls());
        assertEquals(1300, metrics.getPromptTokens());
        assertEquals(200, metrics.getCompletionTokens());
        assertEquals(1500, metrics.getEstimatedPromptTokens());
        assertEquals(3000L, metrics.getLlmMillis(), "耗时按毫秒累加");
        assertEquals(0, metrics.getUsageMissingCalls());
    }

    @Test
    @DisplayName("usage 缺失单独计数，绝不当成 0 token 混进平均值")
    void missingUsageIsCountedSeparately() {
        AgentRunMetrics metrics = new AgentRunMetrics("yuManus", "conv-1", "full");
        metrics.recordLlmCall(800, null, null, 1_000_000L);
        metrics.recordLlmCall(800, 700, 50, 1_000_000L);

        assertEquals(2, metrics.getLlmCalls());
        assertEquals(1, metrics.getUsageMissingCalls());
        assertEquals(700, metrics.getPromptTokens(), "缺失的那次不能贡献 token 数");
        // 比值仍有分母>0 时才算得出来：实测 700 / 估算 1600
        assertEquals(2.286, metrics.tokenEstimateRatio(), 0.01);
    }

    @Test
    @DisplayName("无实测 token 时估算比值返回 0（表示「测不出来」而不是「比值是 0」）")
    void tokenEstimateRatioIsZeroWhenNoUsage() {
        AgentRunMetrics metrics = new AgentRunMetrics("yuManus", "conv-1", "full");
        metrics.recordLlmCall(500, null, null, 1_000_000L);
        assertEquals(0d, metrics.tokenEstimateRatio());
    }

    @Test
    @DisplayName("工具调用：按名分桶 + 重复调用率")
    void recordsToolCallsAndDuplicates() {
        AgentRunMetrics metrics = new AgentRunMetrics("yuManus", "conv-1", "full");
        metrics.recordToolCall("readFile");
        metrics.recordToolCall("readFile");
        metrics.recordToolCall("writeFile");
        metrics.recordDuplicateToolCall();

        assertEquals(3, metrics.getToolCalls());
        assertEquals(2, metrics.getToolCallsByName().get("readFile"));
        assertEquals(1, metrics.getToolCallsByName().get("writeFile"));
        assertEquals(1, metrics.getDuplicateToolCalls());
        assertEquals(0.333, metrics.duplicateToolCallRate(), 0.001);
    }

    @Test
    @DisplayName("没有工具调用时重复率返回 0，不除零")
    void duplicateRateIsZeroWithoutToolCalls() {
        AgentRunMetrics metrics = new AgentRunMetrics("yuManus", "conv-1", "full");
        assertEquals(0d, metrics.duplicateToolCallRate());
    }

    @Test
    @DisplayName("单次调用 prompt 峰值：取最大而不是累加（总量看不出「该压不压」）")
    void tracksPeakPromptTokens() {
        AgentRunMetrics metrics = new AgentRunMetrics("yuManus", "conv-1", "budget");
        metrics.recordLlmCall(1000, 2200, 100, 1_000_000L);
        metrics.recordLlmCall(1000, 7400, 100, 1_000_000L);
        metrics.recordLlmCall(1000, 3100, 100, 1_000_000L);

        assertEquals(12700, metrics.getPromptTokens(), "总量是累加");
        assertEquals(7400, metrics.getMaxPromptTokens(), "峰值是取最大");
        // usage 缺失的那次不能污染峰值（第 4 参传 null 时直接返回，峰值不动）
        metrics.recordLlmCall(1000, null, null, 1_000_000L);
        assertEquals(7400, metrics.getMaxPromptTokens());
    }

    @Test
    @DisplayName("压缩事件：微压缩累加被清理的 token 数；压缩失败单独计 fallback")
    void recordsCompactionEvents() {
        AgentRunMetrics metrics = new AgentRunMetrics("yuManus", "conv-1", "budget");
        metrics.recordMicrocompaction(1800);
        metrics.recordMicrocompaction(900);
        metrics.recordCompaction(false);
        metrics.recordCompaction(true);

        assertEquals(2, metrics.getMicrocompactions());
        assertEquals(2700, metrics.getClearedToolResultTokens());
        assertEquals(2, metrics.getCompactions());
        assertEquals(1, metrics.getCompactionFallback());
    }

    @Test
    @DisplayName("流式指标：首字时间幂等、增量帧数累加（2026-10 流式轮）")
    void recordsStreamingLatency() {
        AgentRunMetrics metrics = new AgentRunMetrics("yuManus", "conv-1", "full");
        assertEquals(-1, metrics.getFirstVisibleMs(), "没发生流式时是 -1（「没测到」而不是「0ms」）");
        assertEquals(0, metrics.getDeltaFrames());

        metrics.recordTextDelta();
        metrics.recordFirstVisible();
        long first = metrics.getFirstVisibleMs();
        metrics.recordTextDelta();
        metrics.recordFirstVisible();

        assertTrue(first >= 0);
        assertEquals(first, metrics.getFirstVisibleMs(), "首字时间只记第一次：后续增量不能把它推后");
        assertEquals(2, metrics.getDeltaFrames());
    }

    @Test
    @DisplayName("finish 写入结果与总耗时（异常路径也要有数字）")
    void finishWritesOutcomeAndElapsed() {
        AgentRunMetrics metrics = new AgentRunMetrics("yuManus", "conv-1", "full");
        assertEquals("running", metrics.getOutcome());
        metrics.finish("FINISHED", 4);

        assertEquals("FINISHED", metrics.getOutcome());
        assertEquals(4, metrics.getSteps());
        assertTrue(metrics.getEndToEndMs() >= 0);
    }

    @Test
    @DisplayName("日志行：AGENT_METRICS 前缀 + 可被 Jackson 解析 + 关键字段与派生指标齐全")
    void toLogLineIsPrefixedParseableJson() throws Exception {
        AgentRunMetrics metrics = new AgentRunMetrics("yuManus", "conv-1", "budget");
        metrics.recordLlmCall(1000, 900, 120, 5_000_000L);
        metrics.recordToolCall("searchWeb");
        metrics.recordMicrocompaction(500);
        metrics.finish("FINISHED", 3);

        String line = metrics.toLogLine();
        assertTrue(line.startsWith(AgentRunMetrics.LOG_PREFIX), "日志行必须带前缀，grep AGENT_METRICS 才捞得到");
        // 前缀之后必须是合法 JSON——`grep AGENT_METRICS | jq` 是这行日志的既定用法
        JsonNode json = MAPPER.readTree(line.substring(AgentRunMetrics.LOG_PREFIX.length()));
        assertEquals("yuManus", json.get("agent").asText());
        assertEquals("budget", json.get("mode").asText());
        assertEquals("FINISHED", json.get("outcome").asText());
        assertEquals(3, json.get("steps").asInt());
        assertEquals(900, json.get("promptTokens").asInt());
        assertEquals(1, json.get("toolCalls").asInt());
        assertEquals(1, json.get("microcompactions").asInt());
        assertEquals("searchWeb", json.get("toolCallsByName").fieldNames().next());
        // 流式轮新增：没走流式时 firstVisibleMs = -1（能在日志里区分「没测到」与「0ms」）
        assertEquals(-1, json.get("firstVisibleMs").asLong());
        assertEquals(0, json.get("deltaFrames").asInt());
        assertNotNull(json.get("runId"));
        assertEquals(8, json.get("runId").asText().length(), "runId 是短 ID，用于串日志");
        // 2026-10-11 单用户单会话重构：userId 从指标里整体删除（没有用户体系，它恒为 "default"）。
        // 这条断言防「删了又加回来」——日志字段是面试口径里可展示的可观测性面，不能回退。
        assertEquals("conv-1", json.get("conversationId").asText(), "conversationId 保留：仅用于日志追踪");
        assertNull(json.get("userId"), "指标日志不应再出现 userId（单用户后是死字段）");
    }
}
