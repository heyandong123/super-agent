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

    @Tool(description = "Scrape the content of a web page")
    public String scrapeWebPage(@ToolParam(description = "URL of the web page to scrape") String url) {
        try {
            Document document = Jsoup.connect(url).get();
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
