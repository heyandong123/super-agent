package com.yupi.yuaiagent.metrics;

import com.yupi.yuaiagent.context.ContextTokenEstimator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Supplier;

/**
 * 用量采集 Advisor（2026-10 上下文重构 · 阶段 1 埋点）
 * <p>
 * 挂在每个 Agent 的 ChatClient 上（写法照抄同目录 {@code MyLoggerAdvisor}），在
 * {@code chain.nextCall} 前后采集：① 调用前的<b>估算</b> prompt token；② 调用后的
 * <b>实测</b> usage（{@code ChatResponse.getMetadata().getUsage()}）；③ 本次调用耗时。
 * <p>
 * 为什么估算和实测要成对记录：估算器是启发式的（不引 tokenizer），误差方向必须靠实测标定。
 * 实测（{@code TokenEstimatorCalibrationTest}）已证明启发式口径在中文上会低估约 10%，
 * 阶段 4 的 A/B 会用 {@code tokenEstimateRatio} 的中位比值来校准权重——没有成对数据就没法校准。
 * <p>
 * usage 缺失（供应商没返回、或流式未聚合到）不是 0 token，单独计入 {@code usageMissingCalls}，
 * 否则统计出的平均 token 会虚假偏低，把"测不到"误读成"很省"。
 */
@Slf4j
public class UsageMetricsAdvisor implements CallAdvisor, StreamAdvisor {

    /** 每 run 一个度量对象；run 开始前为 null（advisor 在 Agent 构造期创建，此时还没有 run） */
    private final Supplier<AgentRunMetrics> metricsSupplier;

    public UsageMetricsAdvisor(Supplier<AgentRunMetrics> metricsSupplier) {
        this.metricsSupplier = metricsSupplier;
    }

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        // 与 MyLoggerAdvisor 同为 0：顺序对本 advisor 无影响（只观测、不改请求）
        return 0;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain chain) {
        AgentRunMetrics metrics = metricsSupplier.get();
        if (metrics == null) {
            // 还没有 run 上下文（理论上不会发生：advisor 只在 run 内被调用）——直接放行，绝不影响主链路
            return chain.nextCall(chatClientRequest);
        }
        int estimated = estimatePromptTokens(chatClientRequest);
        long start = System.nanoTime();
        ChatClientResponse response = chain.nextCall(chatClientRequest);
        record(metrics, estimated, response, System.nanoTime() - start);
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest, StreamAdvisorChain chain) {
        AgentRunMetrics metrics = metricsSupplier.get();
        if (metrics == null) {
            return chain.nextStream(chatClientRequest);
        }
        int estimated = estimatePromptTokens(chatClientRequest);
        long start = System.nanoTime();
        // 流式：单帧拿不到 usage，等聚合出完整响应后再记（与 MyLoggerAdvisor 同一套聚合器）
        return new ChatClientMessageAggregator()
                .aggregateChatClientResponse(chain.nextStream(chatClientRequest),
                        response -> record(metrics, estimated, response, System.nanoTime() - start));
    }

    private void record(AgentRunMetrics metrics, int estimated, ChatClientResponse response, long elapsedNanos) {
        try {
            ChatResponse chatResponse = response == null ? null : response.chatResponse();
            Usage usage = chatResponse == null || chatResponse.getMetadata() == null
                    ? null : chatResponse.getMetadata().getUsage();
            metrics.recordLlmCall(estimated,
                    promptTokensOrNull(usage),
                    completionTokensOrNull(usage),
                    elapsedNanos);
        } catch (Exception e) {
            // 埋点永远不能拖垮业务：统计失败只记日志
            log.warn("LLM 用量采集失败（不影响主链路）: {}", e.getMessage());
        }
    }

    /**
     * 「测不到用量」有三种形态，都必须映射成 null（=计入 usageMissingCalls），不能落成 0：
     * <ol>
     *   <li>响应没有 metadata / usage 为 null（下游没实现）；</li>
     *   <li>Spring AI 的 {@link EmptyUsage} 占位对象——它的 {@code getPromptTokens()} 返回的是
     *       <b>0 而不是 null</b>（javap 实锤：字节码里是 {@code Integer.valueOf(0)}）。
     *       只判 null 会把"测不到"统计成"省了 token"，把平均值拉低——这正是
     *       {@code usageMissingCalls} 这个字段要防的事（单测 {@code missingUsageIsCounted} 抓到的坑）。</li>
     *   <li>（2026-10 流式轮新增）数值为 <b>0</b>：流式响应没带
     *       {@code stream_options.include_usage} 时，框架聚合出来的是 {@code DefaultUsage(0,0,0)}
     *       ——它既不是 null 也不是 EmptyUsage，只判类型同样会把"测不到"记成 0。
     *       真实聊天补全的 prompt token 不可能为 0（光系统提示词就 >0），这个判据无副作用。</li>
     * </ol>
     */
    private static Integer promptTokensOrNull(Usage usage) {
        if (usage == null || usage instanceof EmptyUsage) {
            return null;
        }
        Integer tokens = usage.getPromptTokens();
        return tokens == null || tokens == 0 ? null : tokens;
    }

    private static Integer completionTokensOrNull(Usage usage) {
        if (usage == null || usage instanceof EmptyUsage) {
            return null;
        }
        Integer tokens = usage.getCompletionTokens();
        return tokens == null || tokens == 0 ? null : tokens;
    }

    /**
     * 调用前的 prompt token 估算 = 消息内容 + 工具 schema。
     * <p>
     * 必须把工具 schema 算进去：实测的 promptTokens 是包含工具定义的（full 模式常驻 9 个工具
     * 约 1465 token/次，见 {@code ToolSchemaBudgetTest}），漏掉它会让估算比值系统性偏低，
     * 标定出来的结论就是错的。
     */
    private int estimatePromptTokens(ChatClientRequest request) {
        try {
            List<Message> messages = request.prompt().getInstructions();
            int tokens = ContextTokenEstimator.estimate(messages);
            if (request.prompt().getOptions() instanceof ToolCallingChatOptions toolOptions) {
                tokens += estimateToolSchemaTokens(toolOptions.getToolCallbacks());
            }
            return tokens;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 工具 schema 的 token 估算：description + inputSchema 的字符数按 4 字符/token（纯 ASCII JSON） */
    private int estimateToolSchemaTokens(List<ToolCallback> callbacks) {
        if (callbacks == null) {
            return 0;
        }
        int chars = 0;
        for (ToolCallback callback : callbacks) {
            ToolDefinition definition = callback.getToolDefinition();
            chars += definition.name().length();
            if (definition.description() != null) {
                chars += definition.description().length();
            }
            if (definition.inputSchema() != null) {
                chars += definition.inputSchema().length();
            }
        }
        return chars / 4;
    }
}
