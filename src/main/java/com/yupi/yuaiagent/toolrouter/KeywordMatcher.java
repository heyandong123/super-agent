package com.yupi.yuaiagent.toolrouter;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 路由关键词匹配工具。
 *
 * <p>路由上下文（用户消息 + 最近几条消息）是自然语言，英文关键词必须按词边界匹配。
 * 直接用 contains 会踩两个真实存在的坑：
 * <ul>
 *   <li>"file" 命中记忆注入的 "[User Profile]" → readFile/writeFile 每一轮都被恒定暴露；</li>
 *   <li>"ls" 命中 "tools"、"results" → 终端工具无端暴露，把模型往危险的通用命令上引。</li>
 * </ul>
 * 中文关键词没有词边界概念，含符号的关键词（"http://"）也无边界可谈，这两类仍走子串匹配。
 *
 * <p>注意：调用方需保证 text 已按 {@code Locale.ROOT} 转小写，关键词表里也都是小写。
 */
public final class KeywordMatcher {

    /**
     * 词边界正则缓存。关键词表是常量，编译结果可以长期复用（每轮路由会匹配十几个关键词）。
     */
    private static final Map<String, Pattern> WORD_PATTERNS = new ConcurrentHashMap<>();

    private KeywordMatcher() {
    }

    /**
     * 英文/数字关键词按词边界匹配，其余（中文、含符号）按子串匹配。
     */
    public static boolean containsAnyWord(String text, String... keywords) {
        if (text == null) {
            return false;
        }
        return Arrays.stream(keywords).anyMatch(keyword -> matchesWord(text, keyword));
    }

    /**
     * 纯子串匹配。用于工具名（"amap_around_search" 这类标识符本来就该靠子串命中）
     * 与工具返回文本（错误信息里的 "http status: 429" 等，宽松一点更安全）。
     */
    public static boolean containsSubstring(String text, String... keywords) {
        if (text == null) {
            return false;
        }
        return Arrays.stream(keywords).anyMatch(text::contains);
    }

    private static boolean matchesWord(String text, String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return false;
        }
        if (!isAsciiAlphanumeric(keyword)) {
            return text.contains(keyword);
        }
        Pattern pattern = WORD_PATTERNS.computeIfAbsent(keyword, KeywordMatcher::compileWordPattern);
        return pattern.matcher(text).find();
    }

    /**
     * 边界用 {@code [a-z0-9]} 而不是 {@code \b}：下划线、连字符、点号都算分隔符，
     * 这样 "report.pdf"、"file_name" 仍能命中 pdf / file。
     * 允许一个可选的复数 s："read the files"、"nearby places" 照样命中。
     */
    private static Pattern compileWordPattern(String keyword) {
        return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(keyword) + "s?(?![a-z0-9])");
    }

    private static boolean isAsciiAlphanumeric(String keyword) {
        for (int i = 0; i < keyword.length(); i++) {
            char c = keyword.charAt(i);
            boolean asciiAlphanumeric = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (!asciiAlphanumeric) {
                return false;
            }
        }
        return true;
    }
}
