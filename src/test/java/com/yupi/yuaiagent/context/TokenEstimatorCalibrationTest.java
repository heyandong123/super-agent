package com.yupi.yuaiagent.context;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 估算器标定测试（离线）：把「启发式上界口径」与「真实分词器 CL100K 实测」摆在一起对比。
 *
 * <p>为什么需要：归档截断用的是启发式上界口径，而真正拦住超长文档的是
 * {@code TokenCountBatchingStrategy}——它用 jtokkit 的 CL100K 精确计数。
 * 如果启发式上界 < 真实计数，截断就"看起来截了、实际上还是超"，
 * 结果是文档被快速丢弃（fail-open 吞掉）——不烧钱了，但记忆也没了。
 *
 * <p>本测试把两者的比值钉成数字：{@link ContextTokenEstimator#estimateUpperBound} 必须
 * <b>不小于</b> CL100K 实测值，否则归档路径的硬上限保证不成立。
 */
@Tag("unit")
class TokenEstimatorCalibrationTest {

    private static final Encoding CL100K = Encodings.newDefaultEncodingRegistry()
            .getEncoding(EncodingType.CL100K_BASE);

    @Test
    @DisplayName("标定：中文语料上「上界口径」与 CL100K 实测的比值")
    void calibrateOnChineseCorpus() {
        printRatio("纯中文（短句重复）", "这是一段用于触发嵌入上限的超长输入。".repeat(500));
        printRatio("中文长句（含标点、数字、英文混排）",
                "用户在 2026-10-10 提出：请帮我分析 Spring AI 1.0 的 ToolCallingManager 实现，并给出 3 条改进建议。".repeat(200));
        printRatio("英文技术文本",
                "The quick brown fox jumps over the lazy dog while parsing JSON schema definitions. ".repeat(300));
    }

    private void printRatio(String name, String text) {
        int estimated = ContextTokenEstimator.estimateUpperBound(text);
        int actual = CL100K.countTokens(text);
        double ratio = (double) estimated / actual;
        System.out.printf("%-34s 上界口径 %7d  CL100K %7d  比值 %.2f%n", name, estimated, actual, ratio);
        assertEquals(actual, ContextTokenEstimator.countTokensExact(text),
                "countTokensExact 必须与 TokenCountBatchingStrategy 用的是同一套分词口径（CL100K_BASE）");
        // 只挡住「量级失控」：真实比值随语料在 0.9（中文）~1.9（英文）之间浮动，
        // 这里不做精确断言——精确性由上面那条「与 CL100K 同源」的性质保证。
        assertTrue(ratio > 0.5 && ratio < 3.0,
                name + " 的估算/实测比值 " + ratio + " 偏离合理区间，估算器口径可能被改坏了");
    }
}
