package com.yupi.yuaiagent.toolrouter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 路由关键词匹配的回归测试（纯单测，不需要 Spring 上下文）。
 */
@Tag("unit")
class KeywordMatcherTest {

    @Test
    @DisplayName("file 不再命中 profile：记忆注入的 [User Profile] 曾让 readFile/writeFile 每轮恒定暴露")
    void shouldNotMatchKeywordInsideAnotherWord() {
        assertFalse(KeywordMatcher.containsAnyWord("[user profile]\nrelationshipstatus=单身", "file"));
        assertTrue(KeywordMatcher.containsAnyWord("请把结果保存到 file.txt", "file"));
        assertTrue(KeywordMatcher.containsAnyWord("read the files", "file"));
    }

    @Test
    @DisplayName("ls 不再命中 tools / results：短关键词曾让终端工具无端暴露")
    void shouldNotMatchShortKeywordInsideWord() {
        assertFalse(KeywordMatcher.containsAnyWord("list all tools", "ls"));
        assertFalse(KeywordMatcher.containsAnyWord("no results found", "ls"));
        assertTrue(KeywordMatcher.containsAnyWord("ls -la", "ls"));
    }

    @Test
    @DisplayName("下划线/连字符/点号都算词边界：file_name 与 report.pdf 仍能命中")
    void shouldTreatPunctuationAsWordBoundary() {
        assertTrue(KeywordMatcher.containsAnyWord("打开 file_name.txt", "file"));
        assertTrue(KeywordMatcher.containsAnyWord("导出 report.pdf 给我", "pdf"));
    }

    @Test
    @DisplayName("中文关键词仍走子串匹配")
    void shouldKeepSubstringMatchingForChinese() {
        assertTrue(KeywordMatcher.containsAnyWord("帮我保存这个文件", "文件"));
        assertFalse(KeywordMatcher.containsAnyWord("帮我保存这个", "文件"));
    }

    @Test
    @DisplayName("含符号的关键词（如 http://）走子串匹配")
    void shouldKeepSubstringMatchingForSymbolKeywords() {
        assertTrue(KeywordMatcher.containsAnyWord("打开 http://example.com 看看", "http://"));
    }

    @Test
    @DisplayName("子串匹配保留给工具名与工具返回文本")
    void shouldKeepSubstringMatchingForIdentifiers() {
        assertTrue(KeywordMatcher.containsSubstring("amap_around_search", "around", "nearby"));
        assertTrue(KeywordMatcher.containsSubstring("tools_list", "ls"));
        assertTrue(KeywordMatcher.containsSubstring("no search results", "no search results"));
    }

    @Test
    @DisplayName("空文本与空关键词不抛异常")
    void shouldHandleBlankInput() {
        assertFalse(KeywordMatcher.containsAnyWord(null, "file"));
        assertFalse(KeywordMatcher.containsSubstring(null, "file"));
        assertFalse(KeywordMatcher.containsAnyWord("任意文本", ""));
    }
}
