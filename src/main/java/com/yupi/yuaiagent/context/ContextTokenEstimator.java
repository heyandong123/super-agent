package com.yupi.yuaiagent.context;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;

/**
 * 轻量 token 估算器（不引入 tokenizer 依赖）
 * <p>
 * 为什么不用真正的 tokenizer：压缩决策发生在「调用 LLM 之前」，只需要误差方向稳定
 * （宁可早压、不可晚压），不需要精确值；而引一个 BPE tokenizer 会给本项目增加
 * 依赖体积与启动开销。估算误差用 A/B 评测里「估算值 / 实测 promptTokens」的中位比值校准。
 * <p>
 * 口径（experience-based）：
 * <ul>
 *   <li>CJK（中日韩）字符 ≈ 0.6 token/字</li>
 *   <li>ASCII 字符 ≈ 1/4 token/字符（4 字符 1 token）</li>
 *   <li>其他字符（emoji、西里尔、阿拉伯文等）≈ 0.5 token/字符</li>
 *   <li>再乘安全系数 1.1（宁早勿晚）</li>
 * </ul>
 * <p>
 * 另提供一组「上界口径」（{@link #estimateUpperBound}）：用于必须保证不超过服务端硬上限的场景
 * （如 DashScope 嵌入单条 2048 token）。上界口径刻意高估——低估会让文档超限，
 * 换来的是 400 + 重试风暴（本项目实测过：一次超长归档要 143~146 秒才失败）。
 */
public final class ContextTokenEstimator {

    /** CJK 字符权重：1 个汉字 ≈ 0.6 token */
    private static final double CJK_WEIGHT = 0.6;

    /** ASCII 字符：4 字符 ≈ 1 token */
    private static final double ASCII_CHARS_PER_TOKEN = 4;

    /** 其他字符权重（emoji / 西里尔 / 阿拉伯文等） */
    private static final double OTHER_WEIGHT = 0.5;

    /** 安全系数：估算值整体放大 10%，倾向「早一点压缩」 */
    private static final double SAFETY_MULTIPLIER = 1.1;

    /** 上界口径的 ASCII 密度：3 字符 ≈ 1 token（比经验值更保守） */
    private static final double UPPER_ASCII_CHARS_PER_TOKEN = 3;

    /**
     * 精确口径用的分词器（CL100K_BASE）——与 {@code TokenCountBatchingStrategy} 同源，
     * 所以「用精确口径截断到 N」就等于「一定过得了 batching 策略」。
     * registry 的 BPE 词表是懒加载 + 内部缓存，静态持有只初始化一次。
     */
    private static final EncodingRegistry ENCODING_REGISTRY = Encodings.newDefaultEncodingRegistry();

    private static final Encoding EXACT_ENCODING = ENCODING_REGISTRY.getEncoding(EncodingType.CL100K_BASE);

    private ContextTokenEstimator() {
    }

    /**
     * 估算文本的 token 数（预算口径：用于判断"要不要压缩"）。
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        double[] counts = countByBucket(text);
        double tokens = counts[0] * CJK_WEIGHT
                + counts[1] / ASCII_CHARS_PER_TOKEN
                + counts[2] * OTHER_WEIGHT;
        return (int) Math.ceil(tokens * SAFETY_MULTIPLIER);
    }

    /**
     * 估算文本 token 数的上界（比经验口径保守：CJK/其他按 1 token/字符、ASCII 按 3 字符/token）。
     * <p>
     * 注意：它只是「更保守的估算」，<b>不是真的上界</b>——实测在中文语料上仍比 CL100K 实测低约 10%
     * （中文常见字在 CL100K 里常常是 1~2 token/字）。所以真正卡硬上限的截断请用
     * {@link #truncateToExactTokens}，本方法只用于「差不多够保守」的软场景。
     */
    public static int estimateUpperBound(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        double[] counts = countByBucket(text);
        double tokens = counts[0] + counts[1] / UPPER_ASCII_CHARS_PER_TOKEN + counts[2];
        return (int) Math.ceil(tokens);
    }

    /**
     * 精确计数（CL100K_BASE 分词器，与 {@code TokenCountBatchingStrategy} 同一套口径）。
     * <p>
     * 什么时候必须用精确口径而不是估算口径：截断到「硬上限」的场景。实测
     * （见 {@code TokenEstimatorCalibrationTest}）估算器的上界口径在中文语料上仍低于
     * CL100K 实测约 10%，于是「按 1800 上界口径截断」的文本实测约 2000 token，过不了
     * 2048×(1-10%) 的 batching 闸门 —— 文档被拒绝并被 fail-open 静默丢弃：
     * 不烧钱了，但记忆也没了（本项目的真实事故复盘）。
     * <p>
     * 取舍：决策场景（要不要压缩）用估算口径（快、零成本）；硬上限场景一律用精确口径。
     */
    public static int countTokensExact(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return EXACT_ENCODING.countTokens(text);
    }

    /**
     * 按精确口径把文本截断到不超过 {@code maxTokens} 个 token（含后缀开销）。
     * <p>
     * 实现用「字符下标二分 + tokenize 校验」：BPE 的 token 边界不一定落在字符边界上，
     * 逐 token 拼接容易切碎 UTF-8 字节序列，二分则天然只在字符边界上取子串。
     */
    public static String truncateToExactTokens(String text, int maxTokens, String suffix) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        if (countTokensExact(text) <= maxTokens) {
            return text;
        }
        String safeSuffix = suffix == null ? "" : suffix;
        int budget = maxTokens - countTokensExact(safeSuffix);
        if (budget <= 0) {
            return safeSuffix;
        }
        // 二分下标按「码点」而不是 char 走：emoji 之类的代理对一旦被切一半，
        // jtokkit 会直接抛 "Input is not UTF-8"。用码点索引天然只落在字符边界上。
        int low = 0;
        int high = text.codePointCount(0, text.length());
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            int charIndex = text.offsetByCodePoints(0, mid);
            if (countTokensExact(text.substring(0, charIndex)) <= budget) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return text.substring(0, text.offsetByCodePoints(0, low)) + safeSuffix;
    }

    /**
     * 估算单条消息的 token 数。
     * <p>
     * 关键细节：{@link ToolResponseMessage} 必须按 {@code getResponses()} 渲染——
     * 它的 {@code getText()} 恒为空串（字节码里是 {@code AbstractMessage.<init>(TOOL, "", metadata)}），
     * 早先的压缩器就是因为用 getText() 渲染历史，导致工具输出在摘要里全部丢失。
     */
    public static int estimate(Message message) {
        if (message == null) {
            return 0;
        }
        if (message instanceof ToolResponseMessage toolResponseMessage) {
            int total = 0;
            for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                total += estimate(response.name());
                total += estimate(response.responseData());
            }
            return total;
        }
        if (message instanceof AssistantMessage assistantMessage) {
            int total = estimate(assistantMessage.getText());
            for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                total += estimate(toolCall.name());
                total += estimate(toolCall.arguments());
            }
            return total;
        }
        return estimate(message.getText());
    }

    /**
     * 估算整段消息列表的 token 数。
     */
    public static int estimate(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Message message : messages) {
            total += estimate(message);
        }
        return total;
    }

    /**
     * 把文本截断到不超过 {@code maxTokens} 个 token（按上界口径计算，保证不超服务端硬上限）。
     *
     * @param text      原文
     * @param maxTokens 允许的最大 token 数
     * @param suffix    截断时追加的提示后缀（如"…（归档已截断）"），其自身开销也计入预算
     * @return 原文本（未超限）或截断后的文本
     */
    public static String truncateToTokens(String text, int maxTokens, String suffix) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        if (estimateUpperBound(text) <= maxTokens) {
            return text;
        }
        String safeSuffix = suffix == null ? "" : suffix;
        int budget = maxTokens - estimateUpperBound(safeSuffix);
        if (budget <= 0) {
            // 预算连后缀都放不下：直接返回后缀（保证不超限）
            return safeSuffix;
        }
        int cutIndex = 0;
        double cost = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            double charCost = upperBoundCost(codePoint);
            if (cost + charCost > budget) {
                break;
            }
            cost += charCost;
            i += Character.charCount(codePoint);
            cutIndex = i;
        }
        return text.substring(0, cutIndex) + safeSuffix;
    }

    /**
     * 单个码点的上界开销（与 {@link #estimateUpperBound} 同口径）。
     */
    private static double upperBoundCost(int codePoint) {
        if (codePoint < 128) {
            return 1 / UPPER_ASCII_CHARS_PER_TOKEN;
        }
        return 1;
    }

    /**
     * 按桶统计字符数：[0]=CJK、[1]=ASCII、[2]=其他（按码点计，emoji 等代理对算 1 个字符）。
     */
    private static double[] countByBucket(String text) {
        double cjk = 0;
        double ascii = 0;
        double other = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (codePoint < 128) {
                ascii++;
            } else if (isCjk(codePoint)) {
                cjk++;
            } else {
                other++;
            }
        }
        return new double[]{cjk, ascii, other};
    }

    /**
     * 是否 CJK 字符（汉字 / 平假名 / 片假名 / 谚文）。
     * 用 {@link Character.UnicodeScript} 判定而不是硬编码码点区间：
     * 扩展区汉字（如 𠀀）与假名、谚文都能正确归类。
     */
    private static boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }
}
