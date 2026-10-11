package com.yupi.yuaiagent.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * UsageMetricsAdvisor 离线单测（2026-10 上下文重构 · 阶段 1 埋点）。
 * <p>
 * 用 mock 的 {@link CallAdvisorChain} 代替真实 LLM：验证采集口径（usage 缺失、估算含工具 schema）
 * 与 fail-open（没 run 上下文、响应为空都不许抛异常）。真实 token 数字由阶段 4 的 A/B 产出。
 */
@Tag("unit")
class UsageMetricsAdvisorTest {

    private static ChatClientRequest request(List<Message> messages, ToolCallback... tools) {
        OpenAiChatOptions options = new OpenAiChatOptions();
        if (tools.length > 0) {
            options.setToolCallbacks(List.of(tools));
        }
        return new ChatClientRequest(new Prompt(messages, options), Map.of());
    }

    private static ChatClientResponse response(Integer promptTokens, Integer completionTokens) {
        ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder();
        if (promptTokens != null) {
            metadata.usage(new DefaultUsage(promptTokens, completionTokens));
        }
        return new ChatClientResponse(
                ChatResponse.builder()
                        .generations(List.of(new Generation(new AssistantMessage("ok"))))
                        .metadata(metadata.build())
                        .build(),
                Map.of());
    }

    /** 一个只有定义、不实际执行的工具，用来验证「工具 schema 计入估算」 */
    private static ToolCallback fakeTool(String name, int descriptionChars) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description("d".repeat(descriptionChars))
                        .inputSchema("{\"type\":\"object\"}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return "";
            }
        };
    }

    @Test
    @DisplayName("正常路径：实测 usage 与耗时都记进 metrics")
    void recordsUsageAndElapsed() {
        AgentRunMetrics metrics = new AgentRunMetrics("yuManus", "conv-1", "full");
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(response(900, 120));

        ChatClientResponse returned = new UsageMetricsAdvisor(() -> metrics)
                .adviseCall(request(List.of(new UserMessage("帮我搜索 Spring AI 最新版本"))), chain);

        assertTrue(returned.chatResponse() != null, "advisor 只观测，必须原样把响应传回去");
        assertEquals(1, metrics.getLlmCalls());
        assertEquals(900, metrics.getPromptTokens());
        assertEquals(120, metrics.getCompletionTokens());
        assertEquals(0, metrics.getUsageMissingCalls());
        assertTrue(metrics.getEstimatedPromptTokens() > 0, "调用前必须有一次估算（与实测成对，供阶段 4 标定）");
    }

    /**
     * 这条用例抓到过一个真 bug：Spring AI 没拿到 usage 时给的是 {@link EmptyUsage} 占位对象，
     * 它的 getPromptTokens() 返回的是 <b>0 而不是 null</b>，只判 null 会把「测不到」记成「0 token」。
     */
    @Test
    @DisplayName("usage 缺失（EmptyUsage 占位，返回 0 而非 null）计入 usageMissingCalls")
    void missingUsageIsCounted() {
        AgentRunMetrics metrics = new AgentRunMetrics("yuManus", "conv-1", "full");
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(response(null, null));

        new UsageMetricsAdvisor(() -> metrics)
                .adviseCall(request(List.of(new UserMessage("你好"))), chain);

        assertEquals(1, metrics.getLlmCalls());
        assertEquals(1, metrics.getUsageMissingCalls());
        assertEquals(0, metrics.getPromptTokens());
    }

    /**
     * 第三种「测不到」的形态（2026-10 流式轮新增）：流式响应没带 {@code stream_options.include_usage}
     * 时，框架聚合出的是 {@link DefaultUsage}{@code (0,0,0)}——既不是 null 也不是 EmptyUsage。
     * 只判类型的话，流式下的 token 采集会静默退化成"每次 0 token"，
     * 而 A/B 拿这个数字会得出"流式很省 token"的假结论（真实的 prompt token 不可能为 0）。
     */
    @Test
    @DisplayName("usage 为 0（流式未带 include_usage 时的 DefaultUsage(0,0,0)）也计入 usageMissingCalls")
    void zeroUsageIsCountedAsMissing() {
        AgentRunMetrics metrics = new AgentRunMetrics("yuManus", "conv-1", "full");
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(response(0, 0));

        new UsageMetricsAdvisor(() -> metrics)
                .adviseCall(request(List.of(new UserMessage("你好"))), chain);

        assertEquals(1, metrics.getLlmCalls());
        assertEquals(1, metrics.getUsageMissingCalls(), "0 是「没测到」而不是「真的很省」");
        assertEquals(0, metrics.getPromptTokens());
    }

    @Test
    @DisplayName("工具 schema 必须计入估算：实测 promptTokens 是含工具定义的")
    void toolSchemaTokensAreEstimated() {
        AgentRunMetrics withoutTools = new AgentRunMetrics("yuManus", "conv-1", "full");
        AgentRunMetrics withTools = new AgentRunMetrics("yuManus", "conv-1", "full");
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(response(100, 10));

        List<Message> messages = List.of(new UserMessage("读一下 tmp/a.txt"));
        new UsageMetricsAdvisor(() -> withoutTools).adviseCall(request(messages), chain);
        new UsageMetricsAdvisor(() -> withTools)
                .adviseCall(request(messages, fakeTool("readFile", 4000)), chain);

        assertTrue(withTools.getEstimatedPromptTokens() > withoutTools.getEstimatedPromptTokens(),
                "带 4000 字符描述的工具必须让估算值变高，否则标定出的结论会系统性偏低");
    }

    @Test
    @DisplayName("没有 run 上下文（metrics 为 null）时直接放行，不做任何采集")
    void nullMetricsPassesThrough() {
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ChatClientResponse expected = response(100, 10);
        when(chain.nextCall(any())).thenReturn(expected);

        ChatClientResponse returned = new UsageMetricsAdvisor(() -> null)
                .adviseCall(request(List.of(new UserMessage("hi"))), chain);

        assertSame(expected, returned, "应该原样返回下游响应");
    }

    @Test
    @DisplayName("fail-open：响应缺 metadata / chatResponse 为 null 都不许抛异常")
    void malformedResponseDoesNotThrow() {
        AgentRunMetrics metrics = new AgentRunMetrics("yuManus", "conv-1", "full");
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(new ChatClientResponse(null, Map.of()));

        assertDoesNotThrow(() -> new UsageMetricsAdvisor(() -> metrics)
                .adviseCall(request(List.of(new UserMessage("hi"))), chain));
        assertEquals(1, metrics.getLlmCalls(), "调用次数仍要记（调用确实发生了），只是拿不到 usage");
        assertEquals(1, metrics.getUsageMissingCalls());
    }

    @Test
    @DisplayName("advisor 元信息：名字与 order（与 MyLoggerAdvisor 同序，只观测不改请求）")
    void advisorMetadata() {
        UsageMetricsAdvisor advisor = new UsageMetricsAdvisor(() -> null);
        assertEquals("UsageMetricsAdvisor", advisor.getName());
        assertEquals(0, advisor.getOrder());
    }
}
