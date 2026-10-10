package com.yupi.yuaiagent.tools;

import com.yupi.yuaiagent.constant.FileConstant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.function.Supplier;

/**
 * 工具延迟基准（真实调用；本地 + 联网，<b>未标 @Tag("unit")</b>，不随 CI 分组跑）
 *
 * <p>运行方式（把本地配置里的 tavily key 导出为环境变量，key 绝不回显到终端）：
 * <pre>
 * TAVILY_API_KEY=$(grep -m1 tavily src/main/resources/application-local.yml | awk -F': ' '{print $2}' | awk '{print $1}') \
 *   ./mvnw test -Dtest=ToolLatencyBenchmarkTest
 * </pre>
 *
 * <p>输出：每个工具真实调用的 p50 / p95 / max（毫秒）+ 返回字符数中位数（结果侧 token 成本的输入）。
 * <p>说明：
 * <ul>
 *   <li>本地工具 30 次取分位数；联网工具 5 次（Tavily 免费额度友好）；网络抖动大，看数量级不看绝对值</li>
 *   <li>未覆盖：searchMemory（要 Spring 上下文 + DashScope embedding 往返）、searchImage（要 MCP 子进程）</li>
 * </ul>
 */
class ToolLatencyBenchmarkTest {

    private static final int LOCAL_ITERS = 30;
    private static final int NET_ITERS = 5;

    private final FileOperationTool fileTool = new FileOperationTool();
    private final PDFGenerationTool pdfTool = new PDFGenerationTool();
    private final TerminalOperationTool terminalTool = new TerminalOperationTool();
    private final TerminateTool terminateTool = new TerminateTool();
    private final WebSearchTool webSearchTool =
            new WebSearchTool(System.getenv().getOrDefault("TAVILY_API_KEY", ""));
    private final WebScrapingTool scrapingTool = new WebScrapingTool();
    private final ResourceDownloadTool downloadTool = new ResourceDownloadTool();

    private static final String BIG_FILE = "bench-big.txt";
    private static final String SMALL_FILE = "bench-small.txt";
    private static final String PDF_FILE = "bench-latency.pdf";
    private static final String DL_FILE = "bench-download.html";

    @Test
    @DisplayName("度量：各工具真实调用延迟（p50/p95/max，ms）与返回体积")
    void benchmark() throws Exception {
        // 准备：小文件 + 2 万行大文件（测分页读的常数成本）
        fileTool.writeFile(SMALL_FILE, "hello benchmark\n".repeat(100));
        Path bigFile = Paths.get(FileConstant.FILE_SAVE_DIR, "file", BIG_FILE);
        Files.createDirectories(bigFile.getParent());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            sb.append("line ").append(i).append(" some log content here\n");
        }
        Files.writeString(bigFile, sb.toString());

        System.out.println("\n===== 工具延迟基准（" + System.getProperty("os.name") + " / JDK "
                + System.getProperty("java.version") + "）=====");
        System.out.printf("%-36s %4s %6s %6s %6s %9s  %s%n",
                "工具", "n", "p50", "p95", "max", "返回字符", "备注");

        // ---- 本地工具（无网络）----
        run("writeFile 小文件(1.6KB)", LOCAL_ITERS,
                () -> fileTool.writeFile(SMALL_FILE, "hello benchmark\n".repeat(100)));
        run("readFile 小文件", LOCAL_ITERS,
                () -> fileTool.readFile(SMALL_FILE, null, null));
        run("readFile 分页(2万行, offset=0)", LOCAL_ITERS,
                () -> fileTool.readFile(BIG_FILE, 0, 500));
        run("readFile 深分页(offset=19000)", LOCAL_ITERS,
                () -> fileTool.readFile(BIG_FILE, 19_000, 500));
        run("generatePDF ~0.6KB 中文", 10,
                () -> pdfTool.generatePDF(PDF_FILE, "性能测试内容 ".repeat(80)));
        run("executeTerminalCommand(echo)", LOCAL_ITERS,
                () -> terminalTool.executeTerminalCommand("echo bench"));
        run("doTerminate(纯内存)", 100,
                () -> terminateTool.doTerminate("1. 基准测试-已完成"));

        // ---- 联网工具（网络为主，n 小）----
        run("searchWeb (引擎见备注)", NET_ITERS,
                () -> webSearchTool.searchWeb("Spring AI framework"));
        run("scrapeWebPage example.com", 3,
                () -> scrapingTool.scrapeWebPage("https://example.com"));
        run("downloadResource example.com", 3,
                () -> downloadTool.downloadResource("https://example.com/", DL_FILE));

        System.out.println("（沙箱: " + FileConstant.FILE_SAVE_DIR + "）\n");
    }

    /**
     * 跑 n 次真实调用：1 次预热不计入，输出 p50/p95/max 与返回字符数中位数。
     * 备注自动标注实际命中的搜索引擎 / 失败次数——避免「测 Tavily 其实是百度在兜底」这类误读。
     */
    private static void run(String label, int iterations, Supplier<String> call) {
        String warmup = call.get();
        long[] millis = new long[iterations];
        int[] lengths = new int[iterations];
        int failures = 0;
        String engine = "";
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            String result = call.get();
            millis[i] = (System.nanoTime() - start) / 1_000_000;
            lengths[i] = result == null ? 0 : result.length();
            if (result == null || result.startsWith("Error")
                    || result.contains("All search providers failed")) {
                failures++;
            }
            if (result != null) {
                for (String name : new String[]{"Tavily", "Baidu", "Bing"}) {
                    if (result.contains("from " + name)) {
                        engine = name;
                    }
                }
            }
        }
        long[] sortedMillis = millis.clone();
        Arrays.sort(sortedMillis);
        int[] sortedLengths = lengths.clone();
        Arrays.sort(sortedLengths);
        StringBuilder remark = new StringBuilder();
        if (!engine.isEmpty()) {
            remark.append("引擎=").append(engine);
        }
        if (warmup == null || warmup.isBlank()) {
            remark.append(" 预热失败");
        }
        if (failures > 0) {
            remark.append(" 失败 ").append(failures).append('/').append(iterations);
        }
        System.out.printf("%-36s %4d %6d %6d %6d %9d  %s%n",
                label, iterations,
                sortedMillis[iterations / 2],
                sortedMillis[(int) Math.ceil(iterations * 0.95) - 1],
                sortedMillis[iterations - 1],
                sortedLengths[iterations / 2],
                remark.toString().trim());
    }

    @AfterAll
    static void cleanup() throws Exception {
        for (String f : new String[]{SMALL_FILE, BIG_FILE}) {
            Files.deleteIfExists(Paths.get(FileConstant.FILE_SAVE_DIR, "file", f));
        }
        Files.deleteIfExists(Paths.get(FileConstant.FILE_SAVE_DIR, "pdf", PDF_FILE));
        Files.deleteIfExists(Paths.get(FileConstant.FILE_SAVE_DIR, "download", DL_FILE));
        Files.deleteIfExists(Paths.get(FileConstant.FILE_SAVE_DIR, DL_FILE));
    }
}
