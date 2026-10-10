package com.yupi.yuaiagent.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 联网搜索工具免费引擎链路测试（真实网络，无 LLM 成本）
 * 验证：无 api-key 时百度/Bing 免费链路可用；返回结果含标题与链接
 */
class WebSearchToolTest {

    private final WebSearchTool webSearchTool = new WebSearchTool("");

    @Test
    @DisplayName("免费引擎链路：中文查询能返回结果")
    void testFreeEngineChineseQuery() {
        String result = webSearchTool.searchWeb("AI 新闻");
        assertFalse(result.contains("All search providers failed"), "免费引擎应至少有一个可用: " + result);
        assertTrue(result.contains("Title:"), "结果应包含标题");
        assertTrue(result.contains("Link:"), "结果应包含链接");
        System.out.println("=== 免费搜索链路结果预览 ===");
        System.out.println(result.substring(0, Math.min(600, result.length())));
    }

    @Test
    @DisplayName("免费引擎链路：英文查询能返回结果")
    void testFreeEngineEnglishQuery() {
        String result = webSearchTool.searchWeb("Spring AI framework");
        assertFalse(result.contains("All search providers failed"), "免费引擎应至少有一个可用");
        assertTrue(result.contains("Title:"));
        System.out.println("=== 英文查询结果预览 ===");
        System.out.println(result.substring(0, Math.min(400, result.length())));
    }
}
