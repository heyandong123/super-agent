package com.yupi.yuaiagent.stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式聚合器离线单测（2026-10 流式轮）。
 * <p>
 * 全程用合成 chunk 喂流，不起 HTTP、不碰 LLM：验证聚合结果的形状与阻塞路径一致
 * （文本拼接、toolCalls 捕获、metadata/usage 保留），以及失败语义（中途异常原样上抛、
 * 已回调的增量不回滚）。真实逐 token 行为由 {@code StreamingLatencyAbEvalTest}（门控）与冒烟验证。
 */
@Tag("unit")
class StreamingChatAggregatorTest {

    private static ChatResponse textChunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static ChatResponse toolChunk(String id, String name, String arguments) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("", Map.of(),
                List.of(new AssistantMessage.ToolCall(id, "function", name, arguments))))));
    }

    /** usage-only 末帧：只有 metadata、没有 choices（getResult() 会返回 null） */
    private static ChatResponse usageOnlyChunk() {
        return new ChatResponse(List.of(),
                ChatResponseMetadata.builder().usage(new DefaultUsage(900, 120)).build());
    }

    @Test
    @DisplayName("纯文本流：全文按到达顺序拼接，增量逐段回调，空文本块跳过")
    void aggregatesTextAndEmitsDeltasInOrder() {
        List<String> deltas = new ArrayList<>();

        ChatResponse response = StreamingChatAggregator.collectBlocking(
                Flux.just(textChunk("你"), textChunk(""), textChunk("好"), textChunk(null)),
                deltas::add);

        assertEquals("你好", response.getResult().getOutput().getText());
        assertEquals(List.of("你", "好"), deltas, "增量按到达顺序回调，空/ null 文本不发");
        assertFalse(response.getResult().getOutput().hasToolCalls());
        assertNotNull(response.getResult(), "即使全空文本，聚合结果也必须带一个 result（与阻塞路径一致）");
    }

    /**
     * usage 帧后面还跟着普通块（真实流里 usage 帧是最后一块，但"最后一块才有 usage"不是可以依赖的前提：
     * 任何一块的 metadata 都自带一份 EmptyUsage）。这条用例踩到过一个真 bug——
     * 聚合器原来按「后一块覆盖前一块」取元数据，结果 900 token 被后续工具块的 EmptyUsage 擦成 0，
     * 而 token 数为 0 在埋点里恰好表示「没测到」，会静默污染 A/B 的 token 对比。
     */
    @Test
    @DisplayName("文本 + 工具整块：toolCalls 完整保留（act() 依赖它）；usage 帧之后再有普通块也不能把 token 擦成 0")
    void keepsToolCallsFromMergedChunk() {
        List<String> deltas = new ArrayList<>();

        ChatResponse response = StreamingChatAggregator.collectBlocking(
                Flux.just(
                        textChunk("我来读一下文件"),
                        usageOnlyChunk(),
                        toolChunk("call-1", "readFile", "{\"path\":\"tmp/a.txt\"}")),
                deltas::add);

        AssistantMessage output = response.getResult().getOutput();
        assertEquals("我来读一下文件", output.getText());
        assertTrue(output.hasToolCalls());
        assertEquals(1, output.getToolCalls().size());
        assertEquals("call-1", output.getToolCalls().get(0).id());
        assertEquals("readFile", output.getToolCalls().get(0).name());
        assertEquals("{\"path\":\"tmp/a.txt\"}", output.getToolCalls().get(0).arguments());
        // usage 挂在 usage-only 末帧上（stream_options.include_usage 的形态），必须被保留
        assertEquals(900, response.getMetadata().getUsage().getPromptTokens());
        assertEquals(List.of("我来读一下文件"), deltas, "usage-only 帧不该产生增量");
    }

    @Test
    @DisplayName("空流：返回空文本 + 空 toolCalls 的响应，不抛异常（与阻塞路径拿到空补全一致）")
    void emptyStreamYieldsEmptyResponse() {
        List<String> deltas = new ArrayList<>();

        ChatResponse response = StreamingChatAggregator.collectBlocking(Flux.empty(), deltas::add);

        assertNotNull(response.getResult());
        assertEquals("", response.getResult().getOutput().getText());
        assertFalse(response.getResult().getOutput().hasToolCalls());
        assertTrue(deltas.isEmpty());
    }

    @Test
    @DisplayName("中途异常：原样上抛、绝不返回半成品；已回调的增量不回滚（think 气泡留半截可接受）")
    void midStreamErrorPropagatesAndKeepsEmittedDeltas() {
        List<String> deltas = new ArrayList<>();

        RuntimeException error = assertThrows(RuntimeException.class, () -> StreamingChatAggregator.collectBlocking(
                Flux.concat(Flux.just(textChunk("前半")), Flux.error(new IllegalStateException("boom"))),
                deltas::add));

        assertEquals("boom", error.getMessage());
        assertEquals(List.of("前半"), deltas, "出错前已到达的增量已经发给用户，不回滚");
    }

    @Test
    @DisplayName("同 id 的工具调用分片：arguments 更长者胜（防御上游改成逐片透传的兜底规则）")
    void splitToolCallArgumentsLongerWins() {
        ChatResponse response = StreamingChatAggregator.collectBlocking(
                Flux.just(
                        toolChunk("call-1", "writeFile", "{\"path\":\"tmp/"),
                        toolChunk("call-1", "writeFile", "{\"path\":\"tmp/a.txt\",\"content\":\"hi\"}")),
                null);

        assertEquals(1, response.getResult().getOutput().getToolCalls().size(), "同 id 必须归并成一条");
        assertEquals("{\"path\":\"tmp/a.txt\",\"content\":\"hi\"}",
                response.getResult().getOutput().getToolCalls().get(0).arguments());
    }

    @Test
    @DisplayName("id 缺失的工具调用：按出现次序各自独立，不许误合并")
    void nullIdToolCallsAreNotMerged() {
        ChatResponse response = StreamingChatAggregator.collectBlocking(
                Flux.just(
                        toolChunk(null, "readFile", "{\"path\":\"a\"}"),
                        toolChunk(null, "writeFile", "{\"path\":\"b\"}")),
                null);

        assertEquals(2, response.getResult().getOutput().getToolCalls().size());
    }

    @Test
    @DisplayName("不传增量回调也能工作（只聚合不推送）")
    void worksWithoutDeltaConsumer() {
        ChatResponse response = StreamingChatAggregator.collectBlocking(Flux.just(textChunk("静默聚合")), null);
        assertEquals("静默聚合", response.getResult().getOutput().getText());
    }
}
