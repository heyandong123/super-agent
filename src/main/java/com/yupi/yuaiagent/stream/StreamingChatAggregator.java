package com.yupi.yuaiagent.stream;

import cn.hutool.core.util.StrUtil;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 流式响应聚合器（2026-10 流式轮）
 * <p>
 * <b>为什么不直接用框架的 {@code MessageAggregator}/{@code ChatClientMessageAggregator}</b>：
 * 读 spring-ai 1.0.0 源码确认，它的 {@code doOnComplete} 只重建
 * {@code new AssistantMessage(text, metadata)}——<b>不聚合 toolCalls</b>。而本项目的
 * {@code act()} 依赖 {@code toolCallChatResponse} 里完整的工具调用，拿聚合器的产物去执行工具
 * 会「无工具可执行」。所以聚合必须自建。
 * <p>
 * <b>为什么不需要拼工具参数分片</b>：{@code OpenAiApi.chatCompletionStream} 在 HTTP 层用
 * {@code windowUntil + merge} 把工具参数的分片压成「一整块完整 chunk」才向上游发出
 * （id/name/arguments 齐全、finishReason=TOOL_CALLS）。因此这里只需要<b>捕获</b>，
 * 「同 id 取更长的 arguments」只是防御未来上游改成逐片透传的兜底规则。
 * <p>
 * <b>消费方式</b>：{@code blockLast()} 在<b>调用线程</b>（agent worker）上同步收流——
 * 时序与阻塞 {@code .call()} 完全一致（调用返回时文本已收完），{@code act()} 之后照旧。
 * 不要在 Reactor 非阻塞线程上调用本类。
 * <p>
 * <b>失败语义</b>：流中途异常原样上抛，绝不返回「半成品聚合」，由 {@code think()} 既有的 catch
 * 兜住（与阻塞路径「调用失败即抛」一致）；已经回调出去的增量不回滚（think 气泡留半截属可接受，
 * final 阶段则由 {@code final} 事件的「终态覆盖」语义收口）。
 */
public final class StreamingChatAggregator {

    /** 文本增量回调；可为 null（只聚合不逐字推送） */
    private final Consumer<String> onTextDelta;

    private final StringBuilder text = new StringBuilder();

    /** 按工具调用 id 归并（LinkedHashMap 保持首次出现顺序） */
    private final Map<String, AssistantMessage.ToolCall> toolCalls = new LinkedHashMap<>();

    private ChatResponseMetadata responseMetadata;

    private ChatGenerationMetadata generationMetadata;

    private Map<String, Object> messageMetadata;

    /** id 缺失的工具调用的兜底序号（保证不误合并） */
    private int anonymousToolCallSeq;

    public StreamingChatAggregator(Consumer<String> onTextDelta) {
        this.onTextDelta = onTextDelta;
    }

    /**
     * 同步消费整条流并返回聚合后的完整响应（在调用线程上阻塞到流结束）。
     *
     * @param flux        {@code ChatClient.stream().chatResponse()} 产出的逐 chunk 流
     * @param onTextDelta 文本增量回调（顺序即到达顺序）；可为 null
     * @return 形状与阻塞路径一致的 ChatResponse（含完整 toolCalls），可直接喂给 {@code act()}
     */
    public static ChatResponse collectBlocking(Flux<ChatResponse> flux, Consumer<String> onTextDelta) {
        StreamingChatAggregator aggregator = new StreamingChatAggregator(onTextDelta);
        // doOnNext 里回调抛出的异常（如 SSE 断开）会作为错误信号中断整条流并原样从 blockLast 抛出，
        // 订阅随之取消 → 底层 HTTP 响应流关闭 → 模型侧停止生成
        flux.doOnNext(aggregator::accept).blockLast();
        return aggregator.build();
    }

    /** 逐 chunk 累加。包级可见：离线单测直接喂合成 chunk，不必起 HTTP */
    void accept(ChatResponse chunk) {
        if (chunk == null) {
            return;
        }
        captureMetadata(chunk.getMetadata());
        Generation generation = chunk.getResult();
        if (generation == null) {
            // usage-only 末帧：只有 metadata、没有 choices —— getResult() 会返回 null，必须挡在这里
            return;
        }
        if (generation.getMetadata() != null) {
            this.generationMetadata = generation.getMetadata();
        }
        AssistantMessage output = generation.getOutput();
        if (output == null) {
            return;
        }
        if (output.getMetadata() != null && !output.getMetadata().isEmpty()) {
            this.messageMetadata = output.getMetadata();
        }
        // 文本增量：getText() 可能为 null（纯工具块/心跳块），空串不发
        String delta = output.getText();
        if (StrUtil.isNotEmpty(delta)) {
            text.append(delta);
            if (onTextDelta != null) {
                onTextDelta.accept(delta);
            }
        }
        // 工具调用：正常路径是「一整块」（见类注释）；同 id 再遇时取参数更长的那份
        if (output.hasToolCalls()) {
            for (AssistantMessage.ToolCall toolCall : output.getToolCalls()) {
                mergeToolCall(toolCall);
            }
        }
    }

    /**
     * 捕获响应级元数据。
     * <p>
     * <b>不能简单取「最后一块的元数据」</b>：流式下每块都自带一份 metadata，而 usage 只在末帧
     * （{@code stream_options.include_usage}）里才有值，其余块的 {@code getUsage()} 是
     * {@link EmptyUsage}（{@code getPromptTokens() == 0}）。若按"后者覆盖前者"，
     * 一块紧跟在 usage 帧之后的普通块就能把 token 数擦成 0——离线单测
     * {@code keepsToolCallsFromMergedChunk} 正是踩到这个（900 → 0）。
     * <p>
     * 规则与框架自己的 {@code MessageAggregator} 一致：usage 取「最近一次有值的」。
     * 框架是逐字段判 {@code >0}，这里简化为整帧判定——OpenAI/DeepSeek 的 usage 三个字段同帧到达，
     * 不存在"prompt 在这帧、completion 在下帧"的情形。
     */
    private void captureMetadata(ChatResponseMetadata metadata) {
        if (metadata == null) {
            return;
        }
        if (this.responseMetadata == null || hasPositiveUsage(metadata.getUsage())) {
            this.responseMetadata = metadata;
        }
    }

    private static boolean hasPositiveUsage(Usage usage) {
        return usage != null && !(usage instanceof EmptyUsage) && usage.getPromptTokens() != null
                && usage.getPromptTokens() > 0;
    }

    private void mergeToolCall(AssistantMessage.ToolCall toolCall) {
        String key = StrUtil.isBlank(toolCall.id())
                ? toolCall.name() + "#" + (anonymousToolCallSeq++)
                : toolCall.id();
        AssistantMessage.ToolCall existing = toolCalls.get(key);
        if (existing == null || toolCall.arguments().length() > existing.arguments().length()) {
            toolCalls.put(key, toolCall);
        }
    }

    /** 重建聚合响应：形状与阻塞路径一致（3 参 AssistantMessage 的 media 恒为非 null 空列表） */
    private ChatResponse build() {
        AssistantMessage message = new AssistantMessage(
                text.toString(),
                messageMetadata == null ? Map.of() : messageMetadata,
                new ArrayList<>(toolCalls.values()));
        Generation generation = generationMetadata == null
                ? new Generation(message)
                : new Generation(message, generationMetadata);
        List<Generation> generations = List.of(generation);
        return responseMetadata == null
                ? new ChatResponse(generations)
                : new ChatResponse(generations, responseMetadata);
    }
}
