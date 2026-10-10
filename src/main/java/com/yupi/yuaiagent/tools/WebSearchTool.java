package com.yupi.yuaiagent.tools;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 联网搜索工具类（为大模型提供实时获取外部信息的能力）
 * <p>
 * 多引擎降级链（2026-10 工具重构）：Tavily（主链路，面向 LLM 的搜索 API，直接返回干净摘要，
 * 需 api-key，读配置项 yuai.api-keys.tavily）→ 百度（免费免 key，中文兜底）→ Bing（免费免 key）。
 * 前一个引擎失败时自动降级到下一个，并记录实际使用的引擎，便于观测各引擎的可用性。
 * 原 SearchAPI 引擎因 key 泄露弃用（见 CLAUDE.md 事故记录），连同配置一并移除。
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
    // 2026-10 工具重构：显式超时护栏（连接 10s / 读取 15s / 整次调用 20s），保证在某引擎缓慢时
    // 仍能在外层「单次工具调用 30s 超时」内完成降级，而不是被一个卡住的引擎拖死
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build();
    // 使用 Jackson 解析 JSON 数据
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Tavily API Key（密钥集中管理：真值只写 application-local.yml 的 yuai.api-keys.tavily）。
     * 空白时跳过 Tavily 主链路，直接用免费引擎兜底（百度 → Bing）。
     */
    private final String tavilyApiKey;

    public WebSearchTool(String tavilyApiKey) {
        this.tavilyApiKey = tavilyApiKey;
    }

    // 重构轮（2026-10）：描述写明返回结构（至多 5 条，Title/Snippet/Link）与查询建议（短关键词而非整句）
    @Tool(description = """
            Search the web for real-time information, news, weather, places, etc.
            Returns up to 5 results, each with Title / Snippet / Link.
            Use short keyword queries rather than full sentences. When you already have a specific URL,
            use scrapeWebPage instead to read its content.
            """)
    public String searchWeb(
            @ToolParam(description = "Search keywords, short and specific (e.g. 北京 天气 今天)") String query) {
        // 降级链（2026-10 重构）：Tavily 主链路（有 key 时）→ 百度 → Bing 免费兜底
        String result = searchTavily(query);
        if (result != null) {
            return result;
        }
        result = searchBaidu(query);
        if (result != null) {
            return result;
        }
        result = searchBing(query);
        if (result != null) {
            return result;
        }
        return "All search providers failed for the query: " + query;
    }

    /**
     * Tavily 搜索（主链路，2026-10 接入）：面向 LLM 的搜索 API，直接返回适合喂给模型的正文摘要，
     * 省去解析搜索引擎 HTML 的脆弱环节（百度/Bing 的 DOM 结构随时会变，且可能被反爬拦截）。
     * 接口：POST https://api.tavily.com/search，Bearer 鉴权，结果在 results[]（title/content/url）。
     */
    private String searchTavily(String query) {
        if (StrUtil.isBlank(tavilyApiKey)) {
            return null;
        }
        try {
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "query", query,
                    "max_results", MAX_RESULTS,
                    "search_depth", "basic",
                    "include_answer", false));
            Request request = new Request.Builder()
                    .url("https://api.tavily.com/search")
                    .header("Authorization", "Bearer " + tavilyApiKey)
                    .post(RequestBody.create(requestBody, MediaType.parse("application/json; charset=utf-8")))
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.warn("Tavily 请求失败(HTTP {})，降级下一引擎（401/403 通常意味着 key 无效）", response.code());
                    return null;
                }
                JsonNode results = objectMapper.readTree(response.body().string()).path("results");
                if (results.isMissingNode() || !results.isArray() || results.isEmpty()) {
                    log.warn("Tavily 结果为空，降级下一引擎");
                    return null;
                }
                List<String> lines = new ArrayList<>();
                for (JsonNode result : results) {
                    if (lines.size() >= MAX_RESULTS) {
                        break;
                    }
                    lines.add("Title: " + result.path("title").asText("No Title") + "\n"
                            + "Snippet: " + result.path("content").asText("No Snippet") + "\n"
                            + "Link: " + result.path("url").asText("No Link"));
                }
                log.info("searchWeb 使用引擎: Tavily, 返回 {} 条结果", lines.size());
                return "Here are the search results from Tavily:\n\n" + String.join("\n---\n", lines);
            }
        } catch (Exception e) {
            log.warn("Tavily 搜索异常，降级下一引擎: {}", e.getMessage());
            return null;
        }
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
}
