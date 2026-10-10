package com.yupi.yuaiagent.tools;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 网页抓取工具
 */
public class WebScrapingTool {

    private static final int MAX_SCRAPE_LENGTH = 3000;

    /** 连接/读取超时（毫秒）：显式设置，避免慢站点把一次工具调用拖满外层 30s 上限 */
    private static final int TIMEOUT_MS = 10_000;

    /** 浏览器 UA：部分站点对无 UA 的请求直接拒绝或返回空内容 */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    // 重构轮（2026-10）：描述写明输入前提（已知 URL）与能力边界（不渲染 JS、需登录页面会失败），减少误选
    @Tool(description = """
            Fetch a web page and return its visible text (truncated to 3000 characters).
            Use it for a specific URL you already have, e.g. one of the links returned by searchWeb.
            Cannot render JavaScript-heavy pages; pages requiring login will fail.
            """)
    public String scrapeWebPage(@ToolParam(description = "Full URL of the page to fetch (starts with http:// or https://)")
                                String url) {
        try {
            Document document = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT_MS)
                    .get();
            String text = document.body().text();
            if (text.length() > MAX_SCRAPE_LENGTH) {
                text = text.substring(0, MAX_SCRAPE_LENGTH) + "...(truncated)";
            }
            return text;
        } catch (Exception e) {
            return "Error scraping web page: " + e.getMessage();
        }
    }
}
