package com.yupi.yuaiagent.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * token 估算器离线回归（2026-10 上下文重构）：
 * 口径正确性 + 单调性 + ToolResponseMessage 特判（getText() 恒空串这个大坑）+ 截断不越界。
 */
@Tag("unit")
class ContextTokenEstimatorTest {

    @Test
    @DisplayName("空文本估算为 0")
    void emptyTextIsZero() {
        assertEquals(0, ContextTokenEstimator.estimate(""));
        assertEquals(0, ContextTokenEstimator.estimate((String) null));
        assertEquals(0, ContextTokenEstimator.estimateUpperBound(""));
    }

    @Test
    @DisplayName("纯 ASCII：4 字符 ≈ 1 token × 1.1 安全系数")
    void asciiEstimation() {
        // 400 个 ASCII 字符 → 100 token → ×1.1 = 110（浮点 110.00000000000001，故给 1 的容差）
        assertEquals(110.0, ContextTokenEstimator.estimate("a".repeat(400)), 1.0);
    }

    @Test
    @DisplayName("纯中文：1 汉字 ≈ 0.6 token × 1.1 安全系数")
    void cjkEstimation() {
        // 100 个汉字 → 60 token → ×1.1 = 66（同样给 1 的容差吸收浮点误差）
        assertEquals(66.0, ContextTokenEstimator.estimate("汉".repeat(100)), 1.0);
    }

    @Test
    @DisplayName("混合文本按桶分别计权，且随长度单调不减")
    void mixedTextAndMonotonicity() {
        String shortText = "请帮我搜索 Spring AI 的最新版本";
        String longText = shortText.repeat(5);
        int estimated = ContextTokenEstimator.estimate(shortText);
        assertTrue(ContextTokenEstimator.estimate(longText) > estimated,
                "更长的文本估算值必须更大");
        // 手算口径：CJK 10 字（10×0.6=6.0）+ ASCII 11 字符（11/4=2.75）→ 8.75 ×1.1 = 9.625 → 10。
        // 给一个宽带（8~15）而不是精确值：这里校验的是「量级正确」，精度由 A/B 的实测比值校准。
        assertTrue(estimated >= 8 && estimated <= 15,
                "「请帮我搜索 Spring AI 的最新版本」应在 8~15 token 量级，实际 " + estimated);
    }

    @Test
    @DisplayName("ToolResponseMessage 必须按 getResponses() 估算——它的 getText() 恒为空串")
    void toolResponseMessageIsEstimatedFromResponses() {
        ToolResponseMessage toolResponse = new ToolResponseMessage(List.of(
                new ToolResponseMessage.ToolResponse("call-1", "readFile", "x".repeat(400))));
        // 先钉住这个坑本身：文本为空，内容只在 responses 里
        assertEquals("", toolResponse.getText(), "ToolResponseMessage.getText() 恒为空串——按它渲染历史会丢掉全部工具输出");
        // 400 ASCII 字符 → 100 token → ×1.1 = 110，另加工具名
        assertTrue(ContextTokenEstimator.estimate((org.springframework.ai.chat.messages.Message) toolResponse) >= 110,
                "工具返回内容必须计入估算");
    }

    @Test
    @DisplayName("整段消息列表：包含工具调用参数与用户/助手文本")
    void messageListEstimation() {
        AssistantMessage assistant = new AssistantMessage(
                "我来读一下文件",
                java.util.Map.of(),
                List.of(new AssistantMessage.ToolCall("call-1", "function", "readFile", "{\"path\":\"a.txt\"}")));
        List<Message> messages = List.of(
                new UserMessage("请读取 a.txt 并总结"),
                assistant,
                new ToolResponseMessage(List.of(
                        new ToolResponseMessage.ToolResponse("call-1", "readFile", "文件内容".repeat(50))))
        );
        int total = ContextTokenEstimator.estimate(messages);
        int sum = messages.stream().mapToInt(ContextTokenEstimator::estimate).sum();
        assertEquals(sum, total, "列表估算应等于逐条之和");
        assertTrue(total > ContextTokenEstimator.estimate(messages.get(0)), "工具结果应显著抬高总量");
    }

    @Test
    @DisplayName("上界口径 ≥ 预算口径（截断必须用上界，否则可能超服务端硬上限）")
    void upperBoundIsNotSmallerThanEstimate() {
        for (String sample : new String[]{"hello world", "这是一段中文内容", "mixed 混合 内容 😀", "a".repeat(1000)}) {
            assertTrue(ContextTokenEstimator.estimateUpperBound(sample) >= ContextTokenEstimator.estimate(sample),
                    "上界口径必须不小于预算口径: " + sample.substring(0, Math.min(10, sample.length())));
        }
    }

    @Test
    @DisplayName("截断：结果不超过预算，且带截断后缀")
    void truncateToTokens() {
        String text = "汉".repeat(5000);
        String truncated = ContextTokenEstimator.truncateToTokens(text, 1800, "…（已截断）");
        assertTrue(ContextTokenEstimator.estimateUpperBound(truncated) <= 1800,
                "截断后必须落在预算内（按上界口径校验）");
        assertTrue(truncated.endsWith("…（已截断）"), "截断后应带后缀");
        assertTrue(truncated.length() < text.length(), "确实截短了");
    }

    @Test
    @DisplayName("截断：未超预算时原样返回，不改内容")
    void truncateKeepsShortTextIntact() {
        String text = "短文本";
        assertEquals(text, ContextTokenEstimator.truncateToTokens(text, 1800, "…"));
    }

    @Test
    @DisplayName("精确截断：任意语料（含 emoji 代理对）截断后 CL100K 计数都不超预算")
    void exactTruncationNeverExceedsBudget() {
        for (String sample : new String[]{
                "汉".repeat(5000),                                   // 纯中文：启发式口径会低估的那种
                "hello world ".repeat(2000),                          // 纯 ASCII
                "混合 mixed 😀🎉🚀 内容 ".repeat(1000),                  // emoji（代理对）混排
                "🇨🇳 旗帜序列与组合字符 é ".repeat(500)}) {              // 更刁钻的码点组合
            int budget = 800;
            String truncated = ContextTokenEstimator.truncateToExactTokens(sample, budget, "…（截断）");
            int actual = ContextTokenEstimator.countTokensExact(truncated);
            assertTrue(actual <= budget,
                    "精确口径截断后必须 ≤" + budget + "，实际 " + actual);
            assertTrue(truncated.length() <= sample.length(), "截断只会变短");
        }
    }
}
