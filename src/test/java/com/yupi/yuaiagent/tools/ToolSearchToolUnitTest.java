package com.yupi.yuaiagent.tools;

import com.yupi.yuaiagent.toolrouter.ToolRoutingProperties;
import com.yupi.yuaiagent.toolrouter.ToolVectorRouter;
import com.yupi.yuaiagent.toolrouter.ToolVectorRouterProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具搜索/目录元工具纯单元测试（Mockito mock 向量库，不依赖 Docker PG）
 */
@Tag("unit")
class ToolSearchToolUnitTest {

    private VectorStore vectorStore;

    private ToolCallback[] registry;

    private Set<String> discoveredTools;

    private ToolSearchTool toolSearchTool;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        registry = ToolCallbacks.from(
                new FakeDownloadTool(),
                new FakeSearchTool(),
                new FakeTerminateTool());
        // 本测试验证的正是动态调用机制的组件，需显式设为 dynamic 模式：
        // 默认 full 模式下 ToolVectorRouter.init() 会旁路早退，内存索引为空，关键词回退用例会失败
        ToolRoutingProperties routingProperties = new ToolRoutingProperties();
        routingProperties.setExposureMode("dynamic");
        ToolVectorRouter router = new ToolVectorRouter(
                vectorStore, registry, new ToolVectorRouterProperties(), routingProperties, jdbcTemplate);
        // 手动触发 init() 填充内存索引（单元测试无 Spring 容器，@PostConstruct 不会执行）
        router.init();
        discoveredTools = ConcurrentHashMap.newKeySet();
        toolSearchTool = new ToolSearchTool(router, registry, discoveredTools, routingProperties);
    }

    @Test
    @DisplayName("语义检索命中：结果直写 L2 会话缓存")
    void testSemanticHitWritesToDiscoveredTools() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(
                List.of(new Document("下载资源", Map.of("toolName", "downloadResource"))));

        String result = toolSearchTool.searchTools("下载图片资源");

        assertTrue(result.contains("找到 1 个相关工具"));
        assertTrue(result.contains("downloadResource"));
        assertTrue(discoveredTools.contains("downloadResource"));
        assertEquals(1, toolSearchTool.getCallCount());
        assertEquals(1, toolSearchTool.getHitCount());
    }

    @Test
    @DisplayName("语义检索未命中 → 阈值放宽后命中")
    void testRelaxedThresholdFallback() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenAnswer(invocation -> {
            SearchRequest request = invocation.getArgument(0);
            // 首轮阈值 0.2 返回空，放宽后阈值 0.1 命中
            if (request.getSimilarityThreshold() <= 0.1 + 1e-9) {
                return List.of(new Document("下载资源", Map.of("toolName", "downloadResource")));
            }
            return List.of();
        });

        String result = toolSearchTool.searchTools("下载文件");

        assertTrue(result.contains("downloadResource"));
        assertTrue(result.contains("相似度较低"));
        verify(vectorStore, times(2)).similaritySearch(any(SearchRequest.class));
    }

    @Test
    @DisplayName("语义检索全空 → 关键词回退命中")
    void testKeywordFallback() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        String result = toolSearchTool.searchTools("下载");

        assertTrue(result.contains("downloadResource"));
        assertTrue(discoveredTools.contains("downloadResource"));
    }

    @Test
    @DisplayName("语义命中不在本 Agent 注册表 → 白名单过滤后视为未命中")
    void testWhitelistFilter() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(
                List.of(new Document("生成PDF", Map.of("toolName", "generatePDF"))));

        String result = toolSearchTool.searchTools("导出 PDF 报告");

        assertTrue(result.contains("未找到"));
        assertTrue(discoveredTools.isEmpty());
    }

    @Test
    @DisplayName("语义命中 doTerminate → 被排除，视为未命中")
    void testExcludeTerminateTool() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(
                List.of(new Document("终止", Map.of("toolName", "doTerminate"))));

        String result = toolSearchTool.searchTools("结束任务");

        assertTrue(result.contains("未找到"));
    }

    @Test
    @DisplayName("三级降级全失败 → 结构化失败提示（引导 listTools）")
    void testAllMissReturnsStructuredHint() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        String result = toolSearchTool.searchTools("zzz 不存在的超能力");

        assertTrue(result.contains("未找到"));
        assertTrue(result.contains("listTools"));
        assertTrue(discoveredTools.isEmpty());
    }

    @Test
    @DisplayName("空 query → 返回用法说明")
    void testBlankQueryReturnsUsage() {
        String result = toolSearchTool.searchTools("  ");

        assertTrue(result.contains("用法"));
    }

    @Test
    @DisplayName("listTools 分页与页码钳制")
    void testListToolsPagination() {
        ListToolsTool listToolsTool = new ListToolsTool(registry, 2);

        String page1 = listToolsTool.listTools(1);
        assertTrue(page1.contains("downloadResource"));
        assertTrue(page1.contains("searchWeb"));
        assertFalse(page1.contains("doTerminate"));

        String page2 = listToolsTool.listTools(2);
        assertTrue(page2.contains("doTerminate"));

        // page=0 视为第 1 页，越界页码钳制到最后一页
        assertEquals(page1, listToolsTool.listTools(0));
        assertEquals(page2, listToolsTool.listTools(99));
    }

    @Test
    @DisplayName("listTools 排除元工具")
    void testListToolsExcludesMetaTools() {
        ToolCallback[] registryWithMeta = ToolCallbacks.from(
                new FakeDownloadTool(),
                new FakeSearchTool(),
                new FakeListToolsTool());
        ListToolsTool listToolsTool = new ListToolsTool(registryWithMeta, 10);

        String result = listToolsTool.listTools(1);

        assertTrue(result.contains("downloadResource"));
        assertFalse(result.contains("listTools"));
    }

    // ========== 测试用假工具 ==========

    static class FakeDownloadTool {
        @Tool(name = "downloadResource", description = "下载网络资源并保存到本地")
        public String downloadResource(@ToolParam(description = "资源地址") String url) {
            return "downloaded";
        }
    }

    static class FakeSearchTool {
        @Tool(name = "searchWeb", description = "搜索网页获取实时信息")
        public String searchWeb(@ToolParam(description = "搜索关键词") String query) {
            return "searched";
        }
    }

    static class FakeTerminateTool {
        @Tool(name = "doTerminate", description = "结束任务")
        public String doTerminate() {
            return "terminated";
        }
    }

    static class FakeListToolsTool {
        @Tool(name = "listTools", description = "列出工具")
        public String listTools() {
            return "listed";
        }
    }
}
