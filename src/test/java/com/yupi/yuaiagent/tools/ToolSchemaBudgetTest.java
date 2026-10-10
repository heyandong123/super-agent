package com.yupi.yuaiagent.tools;

import com.yupi.yuaiagent.toolrouter.ToolRoutingProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具 Schema 体积度量 + 预算护栏（纯离线单测：不启 Spring、不调 LLM、不碰外部服务）。
 *
 * <p>动机：每个工具在**每一次** LLM 调用里都会以 JSON Schema 形式随请求发送，
 * 这是"全量加载 vs 动态路由"决策的关键输入——本测试把它变成可复现的数字。
 *
 * <p>输出三张表的字符数：description、inputSchema、合计；token 估算按 ASCII 约 4 字符/token。
 * 断言只是一道防呆阈值（预算上限），不是目标值。
 *
 * <p>2026-10 起 {@code yuai.tool-routing.exposure-mode=full} 为默认（动态调用机制弃用，见
 * docs/tool-refactor-plan.md §9），因此"常驻工具"一栏就是每轮 LLM 请求的真实 schema 开销；
 * 本测试同时承担工具描述膨胀的回归护栏。
 */
@Tag("unit")
class ToolSchemaBudgetTest {

    /** description + inputSchema 合计的防呆上限（字符）。超了说明某个工具描述膨胀了，需要复核。 */
    private static final int TOTAL_CHAR_BUDGET = 20_000;

    @Test
    @DisplayName("度量：常驻工具 definition 的字符数（full 模式每轮真实开销），并守住总预算")
    void measureToolDefinitionSizes() {
        List<Object> toolObjects = List.of(
                new FileOperationTool(),                 // readFile + writeFile
                new WebSearchTool(""),                   // searchWeb
                new WebScrapingTool(),                   // scrapeWebPage
                new ResourceDownloadTool(),              // downloadResource
                new TerminalOperationTool(),             // executeTerminalCommand
                new PDFGenerationTool(),                 // generatePDF
                new TerminateTool(),                     // doTerminate
                new SearchMemoryTool(null, () -> "probe") // searchMemory
        );
        // 元工具：动态路由机制的一部分，构造参数只影响执行、不影响 schema 生成
        ToolSearchTool toolSearchTool = new ToolSearchTool(
                null, new ToolCallback[0], new HashSet<>(), new ToolRoutingProperties());
        ListToolsTool listToolsTool = new ListToolsTool(new ToolCallback[0], 10);

        ToolCallback[] business = ToolCallbacks.from(toolObjects.toArray());
        ToolCallback[] meta = ToolCallbacks.from(toolSearchTool, listToolsTool);

        int businessChars = printAndSum("常驻工具（full 模式每轮实际发送）", business);
        int metaChars = printAndSum("元工具 searchTools/listTools（仅 dynamic 模式注册，full 模式不占 schema）", meta);

        System.out.printf("%n== 合计 ==%n常驻（full 模式每轮）%d 字符 ≈ %d tokens%n元工具（仅 dynamic 模式额外）%d 字符 ≈ %d tokens%n",
                businessChars, businessChars / 4, metaChars, metaChars / 4);
        System.out.println("（MCP searchImage 无法离线构造，未计入；单查询参数，量级 ~100-200 字符）");

        int total = businessChars + metaChars;
        assertTrue(total < TOTAL_CHAR_BUDGET,
                "工具 definition 总字符数 " + total + " 超预算 " + TOTAL_CHAR_BUDGET + "，复核是否有描述膨胀");
    }

    /** 逐工具打印并返回 char 总数；顺带守住"每个工具都有非空名称与描述"这一最低契约。 */
    private static int printAndSum(String title, ToolCallback[] callbacks) {
        int sum = 0;
        for (ToolCallback callback : callbacks) {
            var def = callback.getToolDefinition();
            String description = def.description() == null ? "" : def.description();
            int chars = description.length() + def.inputSchema().length();
            sum += chars;
            System.out.printf("%-28s desc %5d  schema %5d  total %5d  ≈%4d tokens%n",
                    def.name(), description.length(), def.inputSchema().length(), chars, chars / 4);
            assertFalse(def.name().isBlank(), "工具名不能为空");
            assertFalse(description.isBlank(), "工具 " + def.name() + " 缺少 description");
        }
        System.out.printf("-- %s 小计：%d 字符 ≈ %d tokens（%d 个）%n%n",
                title, sum, sum / 4, callbacks.length);
        return sum;
    }
}
