package com.yupi.yuaiagent.agent;

import com.yupi.yuaiagent.memory.ConversationMemoryService;
import com.yupi.yuaiagent.memory.MemoryAssembler;
import com.yupi.yuaiagent.memory.MemoryUpdateService;
import com.yupi.yuaiagent.toolrouter.ToolRoutingProperties;
import com.yupi.yuaiagent.toolrouter.ToolVectorRouter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具选择准确性端到端评测（100 场景版）
 * <p>
 * 每个 case：给定任务 → 跑完整 Agent 循环（2026-10 起默认全量加载，工具集常驻；
 * 旧的关键词/向量路由 + searchTools/listTools 元工具仅在 exposure-mode=dynamic 时启用）
 * → 解析实际调用的工具与期望值比对。
 * 额外维度：
 * 1. 按意图分类统计命中率（search/read/write/scrape/terminal/download/pdf/chat/multi/edge）
 * 2. 单 case 120 秒超时保护，防止个别 LLM 调用挂起拖垮整轮评测
 * 3. 产物验证：write/pdf 类场景检查文件是否真实生成（选择正确 ≠ 执行成功）
 * <p>
 * 运行前提：本地已配置 LLM API Key；联网类 case 需要网络。
 * 注意：100 场景约消耗 300~400 次真实 LLM 调用（约 35~40 分钟）。
 * 所以本类默认 <b>不执行</b>，必须显式打开开关：
 * <pre>RUN_TOOL_EVAL=true ./mvnw test -Dtest=ToolSelectionAccuracyTest</pre>
 */
@Slf4j
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_TOOL_EVAL", matches = "true")
class ToolSelectionAccuracyTest {

    /**
     * 业务工具集合（用于"纯聊天不应调工具"的判断，不含 doTerminate 与元工具）
     */
    private static final Set<String> BUSINESS_TOOLS = Set.of(
            "searchWeb", "scrapeWebPage", "downloadResource", "readFile",
            "writeFile", "generatePDF", "executeTerminalCommand", "searchImage");

    private static final Pattern TOOL_RESULT_PATTERN = Pattern.compile("工具 (\\w+) 返回的结果");

    /**
     * 单 case 超时（秒）
     */
    private static final long CASE_TIMEOUT_SECONDS = 120;

    /**
     * 下载评测用的稳定图片 URL（百度 logo，CDN 稳定）
     */
    private static final String DOWNLOAD_URL =
            "https://www.baidu.com/img/PCtm_d9c8750bed0b3c7d089fa7d55720d6cf.png";

    @Resource
    private ToolCallback[] allTools;

    @Resource(name = "deepSeekChatModel")
    private ChatModel chatModel;

    @Resource
    private MemoryAssembler memoryAssembler;

    @Resource
    private MemoryUpdateService memoryUpdateService;

    @Resource
    private ToolVectorRouter toolVectorRouter;

    @Resource
    private ToolRoutingProperties toolRoutingProperties;

    @Resource
    private ConversationMemoryService conversationMemoryService;

    @Test
    @DisplayName("工具选择准确性：100 个场景端到端验证")
    void testToolSelectionAccuracy100Cases() throws Exception {
        // 准备读文件评测用的真实文件
        Path fileA = Files.createTempFile("yu-eval-read-a", ".txt");
        Files.writeString(fileA, "这是评测用的第一个测试文件，包含一段中文内容。");
        Path fileB = Files.createTempFile("yu-eval-read-b", ".txt");
        Files.writeString(fileB, "Second test file for evaluation, with English content as well.");

        List<TestCase> cases = sampleCases(buildCases(fileA, fileB));
        // 类别过滤：EVAL_CATEGORY=multi 只测多工具组合类
        String categoryFilter = System.getenv("EVAL_CATEGORY");
        if (categoryFilter != null && !categoryFilter.isBlank()) {
            cases = cases.stream().filter(c -> c.category().equals(categoryFilter)).toList();
        }
        int rounds = Integer.parseInt(System.getenv().getOrDefault("EVAL_ROUNDS", "1"));
        Map<String, int[]> categoryStats = new LinkedHashMap<>();
        List<String> missDetails = new ArrayList<>();
        int hitCount = 0;
        int timeoutCount = 0;
        int artifactTotal = 0;
        int artifactOk = 0;

        for (int i = 0; i < cases.size(); i++) {
            TestCase tc = cases.get(i);
            // 多轮投票：每个 case 跑 rounds 次，多数票定命中，消除 LLM 单次随机性
            int roundHits = 0;
            StringBuilder roundDetail = new StringBuilder();
            for (int r = 1; r <= rounds; r++) {
                long startMs = System.currentTimeMillis();
                CaseRunOutcome outcome = runOneCase(i + 1, tc, r);
                long costSeconds = (System.currentTimeMillis() - startMs) / 1000;
                boolean roundHit;
                if (outcome.timeout) {
                    timeoutCount++;
                    roundHit = false;
                    roundDetail.append("⏱️");
                } else {
                    roundHit = evaluate(tc, outcome.calledTools);
                    roundDetail.append(roundHit ? "✅" : "❌");
                }
                if (roundHit) {
                    roundHits++;
                }
                System.out.println("Case " + (i + 1) + "-r" + r + " [" + tc.category + "] "
                        + (roundHit ? "✅" : "❌") + " 调用=" + outcome.calledTools + " (" + costSeconds + "s)");
            }
            boolean pathHit = roundHits >= (rounds / 2 + 1);
            // 产物优先判定：工具路径未命中但产物真实存在 → 任务实际完成
            // （如 shell 重定向等替代路径，修正工具路径判据的假阴性）
            boolean artifactHit = !pathHit && tc.verifyPath != null && Files.exists(tc.verifyPath);
            boolean hit = pathHit || artifactHit;

            int[] stat = categoryStats.computeIfAbsent(tc.category, k -> new int[2]);
            stat[0]++;
            if (hit) {
                stat[1]++;
                hitCount++;
            } else {
                missDetails.add("Case " + (i + 1) + " [" + tc.category + "] 轮次=" + roundDetail
                        + " (" + roundHits + "/" + rounds + "): " + tc.task);
            }
            // 产物验证：命中后，检查 write/pdf 类场景的文件是否真实生成
            if (tc.verifyPath != null && hit) {
                artifactTotal++;
                if (Files.exists(tc.verifyPath)) {
                    artifactOk++;
                } else {
                    missDetails.add("Case " + (i + 1) + " [" + tc.category + "] 命中但产物缺失: " + tc.verifyPath);
                }
            }
            System.out.println("Case " + (i + 1) + " [" + tc.category + "] 汇总 "
                    + (artifactHit ? "✅(产物替代路径)" : hit ? "✅" : "❌")
                    + " 轮次=" + roundDetail + " (" + roundHits + "/" + rounds + ")");
            if ((i + 1) % 10 == 0) {
                System.out.println("进度: " + (i + 1) + "/" + cases.size()
                        + " 当前命中=" + hitCount + " 超时=" + timeoutCount);
            }
        }

        // ===== 汇总报告 =====
        // 注意：hitCount 是 case 级（多数票）统计，timeoutCount 是轮级统计，两者不能混在同一分母里
        int missCount = cases.size() - hitCount;
        System.out.println("=".repeat(70));
        System.out.println("============ 工具选择准确性汇总（100 场景）============");
        System.out.printf("总场景: %d, 命中: %d, 未命中: %d, 轮级超时: %d 次（共 %d 轮）%n",
                cases.size(), hitCount, missCount, timeoutCount, cases.size() * rounds);
        System.out.printf("选择准确率: %.0f%%%n", hitCount * 100.0 / cases.size());
        for (Map.Entry<String, int[]> entry : categoryStats.entrySet()) {
            int[] s = entry.getValue();
            System.out.printf("  分类[%s]: %d/%d%n", entry.getKey(), s[1], s[0]);
        }
        if (artifactTotal > 0) {
            System.out.printf("产物验证: %d/%d 个 write/pdf 场景文件真实生成%n", artifactOk, artifactTotal);
        }
        if (!missDetails.isEmpty()) {
            System.out.println("----- 未命中/异常明细 -----");
            missDetails.forEach(d -> System.out.println("  " + d));
        }
        System.out.println("=".repeat(70));
    }

    /**
     * 执行单个 case（带 120 秒超时保护）
     */
    private CaseRunOutcome runOneCase(int index, TestCase tc, int round) {
        CompletableFuture<CaseRunOutcome> future = CompletableFuture.supplyAsync(() -> {
            YuManus yuManus = new YuManus(allTools, chatModel, memoryAssembler, memoryUpdateService,
                    toolVectorRouter, toolRoutingProperties, conversationMemoryService);
            yuManus.setConversationId("tool-acc-" + index + "-r" + round);
            String output = yuManus.run(tc.task);
            return new CaseRunOutcome(extractCalledTools(output), output, false);
        });
        try {
            return future.get(CASE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("Case {} 超时({}s)，跳过", index, CASE_TIMEOUT_SECONDS);
            return new CaseRunOutcome(Set.of(), "TIMEOUT", true);
        } catch (Exception e) {
            log.warn("Case {} 执行异常: {}", index, e.getMessage());
            return new CaseRunOutcome(Set.of(), "ERROR: " + e.getMessage(), false);
        }
    }

    /**
     * 判定逻辑：
     * 1. 期望集合全空 → 纯聊天场景，不得调用任何业务工具
     * 2. requireAll 中的工具必须全部出现
     * 3. anyOf 非空时，其中至少一个出现
     */
    private boolean evaluate(TestCase testCase, Set<String> calledTools) {
        if (testCase.requireAll.isEmpty() && testCase.anyOf.isEmpty()) {
            return calledTools.stream().noneMatch(BUSINESS_TOOLS::contains);
        }
        if (!calledTools.containsAll(testCase.requireAll)) {
            return false;
        }
        return testCase.anyOf.isEmpty()
                || testCase.anyOf.stream().anyMatch(calledTools::contains);
    }

    /**
     * 按环境变量 EVAL_CASE_COUNT 采样（默认跑满 100 个）。
     * 等距采样保证跨类别覆盖，例如 20 个场景会均匀覆盖所有意图分类。
     */
    private List<TestCase> sampleCases(List<TestCase> allCases) {
        int count = Integer.parseInt(System.getenv().getOrDefault("EVAL_CASE_COUNT", "100"));
        if (count >= allCases.size()) {
            return allCases;
        }
        List<TestCase> sampled = new ArrayList<>();
        double stride = allCases.size() / (double) count;
        for (int i = 0; i < count; i++) {
            sampled.add(allCases.get((int) (i * stride)));
        }
        return sampled;
    }

    private Set<String> extractCalledTools(String output) {
        Set<String> tools = new LinkedHashSet<>();
        Matcher matcher = TOOL_RESULT_PATTERN.matcher(output);
        while (matcher.find()) {
            tools.add(matcher.group(1));
        }
        return tools;
    }

    // ==================== 100 个场景构造 ====================

    private List<TestCase> buildCases(Path fileA, Path fileB) {
        List<TestCase> cases = new ArrayList<>();

        // 1. 搜索类 20 个：覆盖天气/新闻/出行/科技/生活等主题
        String[] searchTopics = {
                "今天北京的天气", "最新的AI新闻", "上海到北京的高铁票价", "苹果公司最新财报",
                "附近有什么好吃的餐厅", "五一假期旅游推荐", "比特币最新价格", "最近上映的电影",
                "新能源汽车补贴政策", "如何学习Spring AI框架", "杭州亚运会金牌榜", "量子计算最新进展",
                "大学生就业形势", "股市今日行情", "ChatGPT最新功能", "如何提高编程效率",
                "2026年世界杯冠军", "格力空调的口碑", "Python和Java哪个更适合初学者", "房价走势预测"
        };
        for (String topic : searchTopics) {
            cases.add(caseOf("search", "搜索一下" + topic, Set.of("searchWeb"), Set.of()));
        }

        // 2. 读文件 10 个：不同措辞 + 两个真实存在的文件
        for (int i = 0; i < 8; i++) {
            cases.add(caseOf("read", readPhrasings[i].formatted(fileA), Set.of("readFile"), Set.of()));
        }
        cases.add(caseOf("read", "看看 " + fileB + " 这个文件里写了什么", Set.of("readFile"), Set.of()));
        cases.add(caseOf("read", "读取" + fileB + "的内容并简要总结", Set.of("readFile"), Set.of()));

        // 3. 写文件 15 个：独立路径 + 产物验证
        for (int i = 1; i <= 15; i++) {
            Path target = Path.of("/tmp/yu-eval-write-" + i + ".txt");
            cases.add(caseOf("write",
                    "把「第" + i + "条评测数据：工具选择准确性测试」这句话保存到 " + target,
                    Set.of("writeFile"), Set.of(), target));
        }

        // 4. 抓取网页 8 个
        String[] scrapeUrls = {
                "https://www.baidu.com", "https://cn.bing.com", "https://www.zhihu.com",
                "https://www.csdn.net", "https://spring.io", "https://github.com",
                "https://www.bilibili.com", "https://www.36kr.com"
        };
        String[] scrapePhrasings = {
                "抓取 %s 这个网页的内容", "帮我爬一下 %s", "获取网页 %s 的内容", "抓取网页 %s",
                "访问 %s 并提取页面内容", "抓一下 %s 的内容", "把 %s 网页内容抓下来", "提取 %s 的页面信息"
        };
        for (int i = 0; i < scrapeUrls.length; i++) {
            cases.add(caseOf("scrape", scrapePhrasings[i].formatted(scrapeUrls[i]), Set.of("scrapeWebPage"), Set.of()));
        }

        // 5. 终端命令 8 个
        String[] commands = {
                "ls /tmp", "pwd", "date", "echo hello", "whoami",
                "ls -la /tmp | head -5", "cat /etc/hosts", "df -h"
        };
        String[] terminalPhrasings = {
                "执行命令 %s", "在终端运行 %s", "帮我执行 %s", "运行命令 %s",
                "执行 %s 看看输出", "跑一下 %s 命令", "执行 %s", "用终端执行 %s"
        };
        for (int i = 0; i < commands.length; i++) {
            cases.add(caseOf("terminal", terminalPhrasings[i].formatted(commands[i]),
                    Set.of("executeTerminalCommand"), Set.of()));
        }

        // 6. 下载 8 个：稳定 CDN 图片 + 不同措辞
        String[] downloadPhrasings = {
                "下载 %s 这张图片并保存", "把 %s 这个图片下载下来", "帮我下载图片 %s",
                "下载资源 %s", "download %s and save it", "下载 %s", "把图片 %s 保存到本地",
                "下载这个链接的图片 %s"
        };
        for (int i = 0; i < downloadPhrasings.length; i++) {
            cases.add(caseOf("download", downloadPhrasings[i].formatted(DOWNLOAD_URL),
                    Set.of("downloadResource"), Set.of()));
        }

        // 7. 生成 PDF 8 个：独立路径 + 产物验证
        String[] pdfTitles = {"测试报告", "会议纪要", "项目周报", "数据分析总结", "学习笔记", "产品需求文档", "调研报告", "年终总结"};
        for (int i = 0; i < pdfTitles.length; i++) {
            Path target = Path.of("/tmp/yu-eval-report-" + (i + 1) + ".pdf");
            cases.add(caseOf("pdf",
                    "生成一份标题为「" + pdfTitles[i] + "」的 PDF 报告保存到 " + target,
                    Set.of("generatePDF"), Set.of(), target));
        }

        // 8. 纯聊天 12 个：不应调用任何业务工具
        String[] chatMessages = {
                "你好", "你是谁", "介绍一下你自己", "今天心情好吗", "讲个笑话", "1+1等于几",
                "谢谢", "你会做什么", "什么是人工智能", "给我一句鼓励的话", "你好呀", "随便聊聊"
        };
        for (String message : chatMessages) {
            cases.add(caseOf("chat", message, Set.of(), Set.of()));
        }

        // 9. 多工具组合 8 个：工具链协作
        for (int i = 1; i <= 3; i++) {
            Path target = Path.of("/tmp/yu-eval-multi-" + i + ".txt");
            cases.add(caseOf("multi",
                    "搜索今天北京的天气，然后把结果保存到 " + target,
                    Set.of("searchWeb", "writeFile"), Set.of(), target));
        }
        for (int i = 4; i <= 5; i++) {
            Path target = Path.of("/tmp/yu-eval-multi-" + i + ".txt");
            cases.add(caseOf("multi",
                    "读取 " + fileA + " 的内容，然后把它复制一份保存到 " + target,
                    Set.of("readFile", "writeFile"), Set.of(), target));
        }
        {
            Path target = Path.of("/tmp/yu-eval-multi-6.pdf");
            cases.add(caseOf("multi",
                    "搜索最新的AI新闻，然后生成一份PDF报告保存到 " + target,
                    Set.of("searchWeb"), Set.of("generatePDF", "writeFile"), target));
        }
        for (int i = 7; i <= 8; i++) {
            Path target = Path.of("/tmp/yu-eval-multi-" + i + ".txt");
            cases.add(caseOf("multi",
                    "执行 ls /tmp 命令，并把输出保存到 " + target,
                    Set.of("executeTerminalCommand", "writeFile"), Set.of(), target));
        }

        // 10. 边界 3 个：极简/口语化表述
        cases.add(caseOf("edge", "天气怎么样", Set.of("searchWeb"), Set.of()));
        cases.add(caseOf("edge", "帮我看看这个链接的内容 https://www.baidu.com", Set.of("scrapeWebPage"), Set.of()));
        cases.add(caseOf("edge", "请用一句话介绍你自己", Set.of(), Set.of()));

        return cases;
    }

    private static final String[] readPhrasings = {
            "读取文件 %s 的内容", "看看 %s 这个文件里写了什么", "帮我读一下 %s",
            "打开 %s 并告诉我内容", "读取 %s", "查看文件 %s 的内容",
            "把 %s 的内容读给我", "读取文件 %s"
    };

    private static TestCase caseOf(String category, String task, Set<String> requireAll, Set<String> anyOf) {
        return new TestCase(category, task, requireAll, anyOf, null);
    }

    private static TestCase caseOf(String category, String task, Set<String> requireAll, Set<String> anyOf,
                                   Path verifyPath) {
        return new TestCase(category, task, requireAll, anyOf, verifyPath);
    }

    record TestCase(String category, String task, Set<String> requireAll, Set<String> anyOf, Path verifyPath) {
    }

    record CaseRunOutcome(Set<String> calledTools, String output, boolean timeout) {
    }
}
