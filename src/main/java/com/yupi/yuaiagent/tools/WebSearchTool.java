/*
package com.yupi.yuaiagent.tools;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

*/
/**
 * 网页搜索工具
 *//*

public class WebSearchTool {

    // SearchAPI 的搜索接口地址
    private static final String SEARCH_API_URL = "https://www.searchapi.io/api/v1/search";

    private final String apiKey;

    public WebSearchTool(String apiKey) {
        this.apiKey = apiKey;
    }

    @Tool(description = "Search for information from Baidu Search Engine")
    public String searchWeb(
            @ToolParam(description = "Search query keyword") String query) {
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("q", query);
        paramMap.put("api_key", apiKey);
        paramMap.put("engine", "baidu");
        try {
            String response = HttpUtil.get(SEARCH_API_URL, paramMap);
            // 取出返回结果的前 5 条
            JSONObject jsonObject = JSONUtil.parseObj(response);
            // 提取 organic_results 部分
            JSONArray organicResults = jsonObject.getJSONArray("organic_results");
            List<Object> objects = organicResults.subList(0, 5);
            // 拼接搜索结果为字符串
            String result = objects.stream().map(obj -> {
                JSONObject tmpJSONObject = (JSONObject) obj;
                return tmpJSONObject.toString();
            }).collect(Collectors.joining(","));
            return result;
        } catch (Exception e) {
            return "Error searching Baidu: " + e.getMessage();
        }
    }
}
*/
package com.yupi.yuaiagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 联网搜索工具类（为大模型提供实时获取外部信息的能力）
 */
public class WebSearchTool {

    // 复用 OkHttpClient 实例，避免频繁创建销毁连接带来的性能损耗
    private final OkHttpClient client = new OkHttpClient();
    // 使用 Jackson 解析 JSON 数据
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 注意：多数第三方搜索 API 需要鉴权，如果你的接口需要 API Key，请在这里配置
    private final String API_KEY;
    public WebSearchTool(String searchApiKey){
        this.API_KEY = searchApiKey;
    }

    @Tool(description = "Search the web for real-time information and news")
    public String searchWeb(
            @ToolParam(description = "The search query or keywords") String query) {

        // 1. 构建带有动态查询参数的 URL
        HttpUrl.Builder urlBuilder = HttpUrl.get("https://www.searchapi.io/api/v1/search").newBuilder();
        urlBuilder.addQueryParameter("engine", "baidu");
        urlBuilder.addQueryParameter("q", query);
        // 如果接口需要鉴权，取消下面这行的注释并填入你的 Key
        urlBuilder.addQueryParameter("api_key", API_KEY);

        Request request = new Request.Builder()
                .url(urlBuilder.build())
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return "Web search failed with HTTP status: " + response.code();
            }

            // 2. 获取原始的 JSON 字符串
            String responseBody = response.body().string();

            // 3. 解析 JSON 树
            JsonNode rootNode = objectMapper.readTree(responseBody);
            JsonNode organicResults = rootNode.path("organic_results");

            // 如果没有找到结果集，返回明确的提示让大模型知晓
            if (organicResults.isMissingNode() || !organicResults.isArray() || organicResults.isEmpty()) {
                return "No search results found for the query.";
            }

            // 4. 提取关键信息并拼接成纯文本，喂给大模型
            StringBuilder resultBuilder = new StringBuilder();
            resultBuilder.append("Here are the search results:\n\n");

            // 遍历搜索结果，限制最多只取前 5 条，防止消耗过多 Token
            int count = 0;
            for (JsonNode result : organicResults) {
                if (count >= 5) break;

                String title = result.path("title").asText("No Title");
                String snippet = result.path("snippet").asText("No Snippet");
                String link = result.path("link").asText("No Link");

                resultBuilder.append("Title: ").append(title).append("\n");
                resultBuilder.append("Snippet: ").append(snippet).append("\n");
                resultBuilder.append("Link: ").append(link).append("\n");
                resultBuilder.append("---\n");

                count++;
            }

            return resultBuilder.toString();

        } catch (Exception e) {
            // 异常兜底，将错误信息返回给大模型
            return "Error occurred while executing web search: " + e.getMessage();
        }
    }
}