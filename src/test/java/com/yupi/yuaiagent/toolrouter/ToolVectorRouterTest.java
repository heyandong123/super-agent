package com.yupi.yuaiagent.toolrouter;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Set;

/**
 * 语义向量工具路由测试类
 * 每次输入一段文本，输出从 PGVector 检索到的工具名称
 * <p>
 * 运行前提：Docker PostgreSQL + pgvector 已启动
 */
@SpringBootTest
class ToolVectorRouterTest {

    @Resource
    private ToolVectorRouter toolVectorRouter;

    @Resource
    private ToolVectorRouterProperties properties;

    @BeforeAll
    static void printHeader() {
        System.out.println("=".repeat(70));
        System.out.println("          语义向量工具路由测试");
        System.out.println("=".repeat(70));
    }

    @Test
    @DisplayName("地图/地点类查询")
    void testMapRelatedQuery() {
        printTestCase("地图/地点类查询",
                List.of(
                        "我想找附近好吃的餐厅",
                        "帮我查一下北京朝阳区有什么好玩的",
                        "附近的咖啡店在哪里",
                        "从西二旗到天安门怎么走"
                ));
    }

    @Test
    @DisplayName("网页搜索/信息查询")
    void testSearchRelatedQuery() {
        printTestCase("网页搜索/信息查询",
                List.of(
                        "帮我搜一下今天北京天气",
                        "查一下最新的AI新闻",
                        "什么是Spring AI框架"
                ));
    }

    @Test
    @DisplayName("文件操作类查询")
    void testFileRelatedQuery() {
        printTestCase("文件操作类查询",
                List.of(
                        "帮我读取这个文件的内容",
                        "把这段话保存到txt文件里",
                        "写一个markdown文档"
                ));
    }

    @Test
    @DisplayName("PDF/文档生成类查询")
    void testPdfRelatedQuery() {
        printTestCase("PDF/文档生成类查询",
                List.of(
                        "生成一份PDF报告",
                        "帮我导出一个文档",
                        "把搜索结果生成PDF"
                ));
    }

    @Test
    @DisplayName("网页抓取类查询")
    void testScrapingRelatedQuery() {
        printTestCase("网页抓取类查询",
                List.of(
                        "抓取这个网页的内容",
                        "帮我爬一下这个链接里的文章"
                ));
    }

    @Test
    @DisplayName("资源下载类查询")
    void testDownloadRelatedQuery() {
        printTestCase("资源下载类查询",
                List.of(
                        "下载这张图片",
                        "帮我下载那个文件"
                ));
    }

    @Test
    @DisplayName("终端命令类查询")
    void testTerminalRelatedQuery() {
        printTestCase("终端命令类查询",
                List.of(
                        "帮我执行一个shell命令",
                        "运行python脚本",
                        "ls查看当前目录"
                ));
    }

    @Test
    @DisplayName("混合/复杂任务查询")
    void testMixedQuery() {
        printTestCase("混合/复杂任务查询",
                List.of(
                        "帮我搜一下北京天气，然后生成一份PDF报告",
                        "先查一下附近有什么电影院，再把结果保存到文件",
                        "我想制定一个约会计划：搜索附近的餐厅，做成PDF发给我"
                ));
    }

    @Test
    @DisplayName("语义相近但关键词不同的查询（验证语义理解）")
    void testSemanticUnderstanding() {
        printTestCase("语义理解测试（不包含明确关键词）",
                List.of(
                        "我饿了想找地方吃饭",          // 不含"地图/附近/搜索"
                        "给我整一份文件出来",            // 不含"PDF/文档"
                        "看看外面有什么好玩的",          // 不含"地图/POI"
                        "上网看看最近有什么大新闻"        // 不含"搜索/查找"
                ));
    }

    // ========= 辅助方法 =========

    private void printTestCase(String category, List<String> queries) {
        System.out.println("\n" + "-".repeat(70));
        System.out.println("📂 测试类别：" + category);
        System.out.println("   路由配置：topK=" + properties.getTopK()
                + ", threshold=" + properties.getSimilarityThreshold());
        System.out.println("-".repeat(70));

        for (String query : queries) {
            System.out.println("\n🔍 输入：「" + query + "」");
            Set<String> result = toolVectorRouter.routeTools(query);
            printResult(result);
        }
    }

    private void printResult(Set<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            System.out.println("   ⚠️  未匹配到任何工具（将回退到全量工具）");
            return;
        }
        // 移除 doTerminate 单独显示（它始终存在）
        boolean hasTerminate = toolNames.contains("doTerminate");
        List<String> tools = toolNames.stream()
                .filter(name -> !"doTerminate".equals(name))
                .sorted()
                .toList();

        System.out.println("   ✅ 匹配的工具：");
        if (tools.isEmpty() && hasTerminate) {
            System.out.println("      ⚠️  仅 doTerminate（将回退到全量工具）");
        } else {
            for (String tool : tools) {
                System.out.println("      ├─ " + tool);
            }
            if (hasTerminate) {
                System.out.println("      └─ doTerminate（始终包含）");
            }
        }
    }
}
