package com.yupi.yuaiagent.tools;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.List;

/**
 * 联网搜索工具类（为大模型提供实时获取外部信息的能力）
 * <p>
 * 多引擎降级链：百度（免费免 key，中文结果最好）→ Bing（免费免 key）→ SearchAPI（付费，需 api-key）。
 * 前一个引擎失败时自动降级到下一个，并记录实际使用的引擎，便于观测各引擎的可用性。
 */
@Slf4j
public class WebSearchTool {

    /**
     * 浏览器 User-Agent，避免被搜索引擎当作爬虫拦截
     */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    /**
     * 单次搜索最多返回的结果条数，防止消耗过多 Token
     */
    private static final int MAX_RESULTS = 5;

    // 复用 OkHttpClient 实例，避免频繁创建销毁连接带来的性能损耗
    private final OkHttpClient client = new OkHttpClient();
    // 使用 Jackson 解析 JSON 数据
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String searchApiKey;

    public WebSearchTool(String searchApiKey) {
        this.searchApiKey = searchApiKey;
    }

    @Tool(description = "Search the web for real-time information and news")
    public String searchWeb(
            @ToolParam(description = "The search query or keywords") String query) {
        // 免费链路优先：百度 → Bing；SearchAPI 兜底（有 key 时）
        String result = searchBaidu(query);
        if (result != null) {
            return result;
        }
        result = searchBing(query);
        if (result != null) {
            return result;
        }
        if (StrUtil.isNotBlank(searchApiKey)) {
            result = searchViaSearchApi(query);
            if (result != null) {
                return result;
            }
        }
        return "All search providers failed for the query: " + query;
    }

    /**
     * 百度网页搜索（免费、免 key、中文结果质量最好）。
     * 结果容器: div.result.c-container；标题: h3 a；摘要: div.c-abstract。
     */
    private String searchBaidu(String query) {
        try {
            HttpUrl url = HttpUrl.get("https://www.baidu.com/s").newBuilder()
                    .addQueryParameter("wd", query)
                    .build();
            Request request = new Request.Builder().url(url)
                    .header("User-Agent", USER_AGENT)
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.warn("百度搜索请求失败(HTTP {})，降级下一引擎", response.code());
                    return null;
                }
                Document doc = Jsoup.parse(response.body().string());
                List<String> lines = new ArrayList<>();
                for (Element container : doc.select("div.result.c-container")) {
                    Element titleLink = container.selectFirst("h3 a");
                    if (titleLink == null || StrUtil.isBlank(titleLink.text())) {
                        continue;
                    }
                    Element abstractEl = container.selectFirst("div.c-abstract");
                    String snippet = abstractEl != null ? abstractEl.text() : "";
                    lines.add("Title: " + titleLink.text() + "\n"
                            + "Snippet: " + snippet + "\n"
                            + "Link: " + titleLink.attr("abs:href"));
                    if (lines.size() >= MAX_RESULTS) {
                        break;
                    }
                }
                if (lines.isEmpty()) {
                    log.warn("百度搜索结果为空（可能被反爬拦截），降级下一引擎");
                    return null;
                }
                log.info("searchWeb 使用引擎: 百度, 返回 {} 条结果", lines.size());
                return "Here are the search results from Baidu:\n\n" + String.join("\n---\n", lines);
            }
        } catch (Exception e) {
            log.warn("百度搜索异常，降级下一引擎: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Bing 网页搜索（免费、免 key，作为百度的备用引擎）。
     * 结果容器: li.b_algo；标题: h2 a；摘要: p。
     */
    private String searchBing(String query) {
        try {
            HttpUrl url = HttpUrl.get("https://cn.bing.com/search").newBuilder()
                    .addQueryParameter("q", query)
                    .build();
            Request request = new Request.Builder().url(url)
                    .header("User-Agent", USER_AGENT)
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.warn("Bing 搜索请求失败(HTTP {})，降级下一引擎", response.code());
                    return null;
                }
                Document doc = Jsoup.parse(response.body().string());
                List<String> lines = new ArrayList<>();
                for (Element item : doc.select("li.b_algo")) {
                    Element titleLink = item.selectFirst("h2 a");
                    if (titleLink == null || StrUtil.isBlank(titleLink.text())) {
                        continue;
                    }
                    Element snippetEl = item.selectFirst("p");
                    String snippet = snippetEl != null ? snippetEl.text() : "";
                    lines.add("Title: " + titleLink.text() + "\n"
                            + "Snippet: " + snippet + "\n"
                            + "Link: " + titleLink.attr("abs:href"));
                    if (lines.size() >= MAX_RESULTS) {
                        break;
                    }
                }
                if (lines.isEmpty()) {
                    log.warn("Bing 搜索结果为空，降级下一引擎");
                    return null;
                }
                log.info("searchWeb 使用引擎: Bing, 返回 {} 条结果", lines.size());
                return "Here are the search results from Bing:\n\n" + String.join("\n---\n", lines);
            }
        } catch (Exception e) {
            log.warn("Bing 搜索异常，降级下一引擎: {}", e.getMessage());
            return null;
        }
    }

    /**
     * SearchAPI 付费引擎（返回结构化 JSON，结果最干净；额度耗尽时由免费引擎兜底）
     */
    private String searchViaSearchApi(String query) {
        try {
            HttpUrl.Builder urlBuilder = HttpUrl.get("https://www.searchapi.io/api/v1/search").newBuilder();
            urlBuilder.addQueryParameter("engine", "baidu");
            urlBuilder.addQueryParameter("q", query);
            urlBuilder.addQueryParameter("api_key", searchApiKey);
            Request request = new Request.Builder().url(urlBuilder.build()).build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.warn("SearchAPI 请求失败(HTTP {})，全部引擎失败", response.code());
                    return null;
                }
                JsonNode rootNode = objectMapper.readTree(response.body().string());
                JsonNode organicResults = rootNode.path("organic_results");
                if (organicResults.isMissingNode() || !organicResults.isArray() || organicResults.isEmpty()) {
                    return null;
                }
                StringBuilder resultBuilder = new StringBuilder();
                resultBuilder.append("Here are the search results:\n\n");
                int count = 0;
                for (JsonNode result : organicResults) {
                    if (count >= MAX_RESULTS) break;
                    String title = result.path("title").asText("No Title");
                    String snippet = result.path("snippet").asText("No Snippet");
                    String link = result.path("link").asText("No Link");
                    resultBuilder.append("Title: ").append(title).append("\n");
                    resultBuilder.append("Snippet: ").append(snippet).append("\n");
                    resultBuilder.append("Link: ").append(link).append("\n");
                    resultBuilder.append("---\n");
                    count++;
                }
                log.info("searchWeb 使用引擎: SearchAPI, 返回 {} 条结果", count);
                return resultBuilder.toString();
            }
        } catch (Exception e) {
            log.warn("SearchAPI 调用异常: {}", e.getMessage());
            return null;
        }
    }
}
