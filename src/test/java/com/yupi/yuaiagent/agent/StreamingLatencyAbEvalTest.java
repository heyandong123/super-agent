package com.yupi.yuaiagent.agent;

import com.yupi.yuaiagent.constant.FileConstant;
import com.yupi.yuaiagent.context.ContextProperties;
import com.yupi.yuaiagent.context.ContextWindowManager;
import com.yupi.yuaiagent.context.TranscriptWriter;
import com.yupi.yuaiagent.memory.ConversationMemoryService;
import com.yupi.yuaiagent.memory.MemoryAssembler;
import com.yupi.yuaiagent.memory.MemoryUpdateService;
import com.yupi.yuaiagent.metrics.AgentRunMetrics;
import com.yupi.yuaiagent.stream.StreamingProperties;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式 A/B 评测（2026-10 流式轮）
 * <p>
 * <b>问题</b>：SSE 通道一直都有，用户看到的却是「长时间空白 + 整段砸下来」。根因不是通道，
 * 而是所有 LLM 调用都是阻塞 {@code .call()}：SSE 只是把「整步跑完」的结果当帧发。
 * 本评测回答的是唯一重要的问题——<b>改为逐 token 推送后，用户看到第一个字的时间真的提前了多少？</b>
 * <p>
 * <b>实验设计（两组跑同一批任务，唯一变量是流式开关）</b>
 * <ul>
 *   <li>{@code stream-off}：{@code yuai.stream.enabled=false}，完全走阻塞路径（改造前的行为）；</li>
 *   <li>{@code stream-on}：默认配置，think/最终答案逐 token 推送。</li>
 * </ul>
 * 两组都通过 {@link BaseAgent#runStream(String)} 驱动（这是唯一会安装增量出口的入口，
 * 也是真实前端走的路径），并轮询 {@code getLastRunMetrics()} 等待 worker 收尾。
 * SSE 发射器不带 handler——Spring 6 的 {@code ResponseBodyEmitter.send()} 此时把帧缓冲进
 * earlySendAttempts 而不是抛异常（javap 核对过字节码），所以测试里没有 HTTP 也能真实走完发送逻辑。
 * <p>
 * <b>判定标准</b>：方向性指标（首字更早）作为硬断言——那是这次改造的定义性质；
 * 幅度（提前多少毫秒、token 是否持平）写进报告。若 {@code usageMissingCalls > 0}，
 * 说明流式下 token 没采到（{@code stream_options.include_usage} 未生效），报告会显式标注"token 数字不可比"。
 * <p>
 * <b>成本</b>：默认 3 任务 × 2 模式 = 6 次真实多步任务，约 3~6 分钟。所以默认<b>不执行</b>：
 * <pre>RUN_STREAM_EVAL=true ./mvnw test -Dtest=StreamingLatencyAbEvalTest</pre>
 * 抽样：{@code STREAM_EVAL_CASES=2}（任务数，默认 3）。
 */
@Slf4j
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_STREAM_EVAL", matches = "true")
class StreamingLatencyAbEvalTest {

    /** 单任务超时（秒）：多步任务 + 可能触发压缩，给足余量 */
    private static final long CASE_TIMEOUT_SECONDS = 240;

    /**
     * 评测隔离（沿用 2026-10-11 单用户单会话重构的约定）：记忆全局一份，隔离手段只剩「换根目录」。
     * {@code restore-turns=0}：恢复历史会给两组都垫上历史消息，凭空改变首字时间与 token 口径，
     * 唯一变量必须只剩流式开关。
     */
    @org.springframework.test.context.DynamicPropertySource
    static void isolateMemory(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("yuai.memory.dir", () -> "target/eval-memory/stream-ab-" + UUID.randomUUID());
        registry.add("yuai.memory.history.restore-turns", () -> 0);
    }

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

    @Resource
    private ContextProperties contextProperties;

    @Resource
    private TranscriptWriter transcriptWriter;

    @Test
    @DisplayName("A/B：阻塞式（改造前）vs 逐 token 流式（改造后）的首字时间与 token 消耗")
    void streamingAbEval() throws Exception {
        Path fileA = Files.createTempFile("stream-ab-a", ".txt");
        Files.writeString(fileA, "流式输出的价值：用户更早看到反馈、可以中途打断、长回答体感更快。\n".repeat(10));
        Path fileB = Files.createTempFile("stream-ab-b", ".txt");
        Files.writeString(fileB, "阻塞式输出的代价：首字时间 = 整步耗时（LLM 生成 + 工具执行 + 再生成）。\n".repeat(10));

        int caseCount = Integer.parseInt(System.getenv().getOrDefault("STREAM_EVAL_CASES", "3"));
        List<AbTask> tasks = buildTasks(fileA, fileB);
        if (caseCount < tasks.size()) {
            tasks = tasks.subList(0, caseCount);
        }

        Map<String, List<AbResult>> results = new LinkedHashMap<>();
        String abortReason = null;
        for (String mode : List.of("stream-off", "stream-on")) {
            List<AbResult> modeResults = new ArrayList<>();
            for (int i = 0; i < tasks.size(); i++) {
                AbResult result = runOne(mode, tasks.get(i), i + 1);
                modeResults.add(result);
                System.out.println(result.toConsoleLine());
                if (result.timedOut()) {
                    // 超时几乎都是外部 API 抖动：future.cancel(true) 掐不断已经在跑的 LLM 调用，
                    // 继续跑只会得到一张两组都难看的表（同 ContextCompactionAbEvalTest 的处置）
                    abortReason = String.format("[%s] 任务「%s」超时 %ds，本轮数字作废（疑似外部 API 不可用），请稍后重跑",
                            mode, result.task(), CASE_TIMEOUT_SECONDS);
                    log.error(abortReason);
                    break;
                }
            }
            results.put(mode, modeResults);
            if (abortReason != null) {
                break;
            }
        }

        Judgment judgment = judge(results);
        String report = renderReport(results, abortReason, judgment);
        System.out.println(report);
        Path out = Path.of(FileConstant.FILE_SAVE_DIR, "metrics",
                "streaming-ab-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);
        System.out.println("A/B 报告已写入: " + out.toAbsolutePath());

        // 超时中止的那轮不许当成结果：先落盘留证据，再明确失败
        assertTrue(abortReason == null, "本轮 A/B 未跑完：" + abortReason);

        List<AbResult> off = results.get("stream-off");
        List<AbResult> on = results.get("stream-on");
        // ---- 不变量（先落盘再断言：断言失败也要留下数字）----
        assertTrue(successRate(on) >= successRate(off),
                String.format("流式的成功率(%s) 不应低于阻塞式(%s)——流式不许把任务做坏", successRate(on), successRate(off)));
        assertEquals(1.0, artifactRate(on), "stream-on 组每个任务都必须落下产物");
        // 方向性判定：逐 token 推送的「第一个字」必然早于「整步跑完」——这是这次改造的定义性质，
        // 不成立就说明流式根本没生效（而不是"效果不明显"）。幅度（提前多少）进报告。
        assertTrue(avgFirstVisible(on) < avgFirstVisible(off),
                String.format("stream-on 平均首字时间(%.0f ms) 必须早于 stream-off(%.0f ms)",
                        avgFirstVisible(on), avgFirstVisible(off)));
        assertTrue(on.stream().mapToInt(AbResult::deltaFrames).sum() > 0,
                "stream-on 组必须真的推出过增量帧（deltaFrames>0）——否则测到的是「流式根本没生效」");
        assertTrue(on.stream().mapToInt(AbResult::usageMissingCalls).sum() == 0,
                "stream-on 组不该出现 usage 缺失：include_usage 没生效的话 token 数字不可比（见报告口径说明）");
    }

    // ---- 实验装置 ----

    /**
     * 任务集：每条都是「一步就能看到模型开口说话」的简单多步任务。
     * 刻意选短任务——首字时间的差异在短任务上最容易被看清楚（长任务里工具耗时占比高，反而钝化对比）。
     */
    private List<AbTask> buildTasks(Path fileA, Path fileB) {
        return List.of(
                new AbTask("纯对话（不调工具）",
                        "用三句话解释什么是智能体的「首字延迟」，不要调用任何工具，直接回答。",
                        null),
                new AbTask("读文件 + 汇报",
                        "请读取文件 " + fileA + "，然后用两句话汇报它的主题。",
                        null),
                new AbTask("写文件 + 确认",
                        "请把「流式输出让用户更早看到反馈」这句话写入文件 " + outputPath("write") + "，然后用一句话确认。",
                        outputPath("write")),
                new AbTask("读两文件 + 对比",
                        "请分别读取文件 " + fileA + " 和 " + fileB + "，然后用三句话对比它们的差异。",
                        null)
        );
    }

    private Path outputPath(String name) {
        return Path.of(FileConstant.FILE_SAVE_DIR, "stream-ab", name + ".txt").toAbsolutePath();
    }

    /**
     * 跑一条任务：{@code runStream} 在 worker 线程上执行，主线程轮询指标快照等它收尾。
     * <p>
     * 为什么轮询 {@code getLastRunMetrics()} 而不是等别的信号：它是 worker finally 里
     * {@code finishRunMetrics} 的产物（volatile 写），拿到非空即代表本次 run 已定稿；
     * SSE 的 completion 回调被 {@code runStream} 自己占用，测试侧不能再挂。
     */
    private AbResult runOne(String mode, AbTask task, int index) {
        StreamingProperties properties = new StreamingProperties();
        properties.setEnabled("stream-on".equals(mode));
        ContextWindowManager manager = new ContextWindowManager(contextProperties, transcriptWriter);
        YuManus agent = new YuManus(allTools, chatModel, memoryAssembler, memoryUpdateService,
                toolVectorRouter, toolRoutingProperties, conversationMemoryService, manager, properties);
        agent.setConversationId("stream-ab-" + mode + "-" + index);
        if (task.outputFile() != null) {
            deleteQuietly(task.outputFile());
        }

        long start = System.currentTimeMillis();
        agent.runStream(task.task());
        AgentRunMetrics metrics = null;
        while (System.currentTimeMillis() - start < CASE_TIMEOUT_SECONDS * 1000) {
            metrics = agent.getLastRunMetrics();
            if (metrics != null) {
                break;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        long wallMs = System.currentTimeMillis() - start;
        if (metrics == null) {
            log.warn("[{}] 任务 {} 超时({}s)", mode, task.name(), CASE_TIMEOUT_SECONDS);
            return AbResult.timeout(mode, task.name());
        }
        boolean artifactOk = task.outputFile() == null || Files.exists(task.outputFile());
        return AbResult.of(mode, task.name(), metrics, wallMs, artifactOk);
    }

    // ---- 报表与统计 ----

    /**
     * 判定：三条硬断言之外的"幅度"问题在这里算出来写进报告，随报告一起落盘。
     * <p>
     * "token 持平"为什么不算达标项而算报告项：流式与阻塞在 token 上的差别本来就该接近 0
     * （同样的消息、同样的工具 schema），它在这里的作用是<b>证明改造没有偷偷变贵</b>——
     * 一旦某轮显示流式明显更贵，就是真出了问题（比如 streamUsage 没开导致重复请求）。
     */
    private Judgment judge(Map<String, List<AbResult>> results) {
        List<AbResult> off = results.getOrDefault("stream-off", List.of());
        List<AbResult> on = results.getOrDefault("stream-on", List.of());
        double offFirst = avgFirstVisible(off);
        double onFirst = avgFirstVisible(on);
        long offTokens = totalPromptTokens(off);
        long onTokens = totalPromptTokens(on);
        return new Judgment(
                onFirst < offFirst,
                on.stream().mapToInt(AbResult::deltaFrames).sum(),
                on.stream().mapToInt(AbResult::usageMissingCalls).sum(),
                offFirst, onFirst, offTokens, onTokens);
    }

    private record Judgment(boolean firstVisibleEarlier, int streamOnDeltaFrames, int streamOnUsageMissing,
                            double offFirstVisibleMs, double onFirstVisibleMs, long offTokens, long onTokens) {
    }

    private String renderReport(Map<String, List<AbResult>> results, String abortReason, Judgment judgment) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 流式 A/B 评测报告（阻塞 vs 逐 token）\n\n");
        sb.append("生成时间: ").append(LocalDateTime.now()).append("\n\n");
        if (abortReason != null) {
            sb.append("> ⚠️ **本轮未跑完（数字不可用）**：").append(abortReason).append("\n\n");
        }
        sb.append("- stream-off = `yuai.stream.enabled=false`（改造前的阻塞路径）\n");
        sb.append("- stream-on = 默认配置（think 叙述 + 最终答案逐 token 推送）\n");
        sb.append("- 两组都走 `runStream`（唯一会安装增量出口的入口，即真实前端路径）；记忆恢复关掉（restore-turns=0），唯一变量是流式开关\n\n");
        sb.append("| mode | task | outcome | 产物 | steps | llmCalls | **firstVisibleMs** | endToEndMs | llmMs | deltaFrames | promptTokens | completionTokens | usageMissing |\n");
        sb.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (Map.Entry<String, List<AbResult>> entry : results.entrySet()) {
            for (AbResult r : entry.getValue()) {
                sb.append("| ").append(r.mode())
                        .append(" | ").append(r.task())
                        .append(" | ").append(r.outcome())
                        .append(" | ").append(r.artifactOk() ? "✅" : "❌")
                        .append(" | ").append(r.steps())
                        .append(" | ").append(r.llmCalls())
                        .append(" | ").append(r.firstVisibleMs() < 0 ? "—（没发出内容）" : r.firstVisibleMs())
                        .append(" | ").append(r.endToEndMs())
                        .append(" | ").append(r.llmMs())
                        .append(" | ").append(r.deltaFrames())
                        .append(" | ").append(r.promptTokens())
                        .append(" | ").append(r.completionTokens())
                        .append(" | ").append(r.usageMissingCalls())
                        .append(" |\n");
            }
        }
        sb.append("\n## 汇总\n\n");
        sb.append("| mode | 成功率 | 产物落盘率 | 平均首字时间(ms) | 平均端到端(ms) | Σ deltaFrames | Σ promptTokens | Σ completionTokens | Σ usageMissing |\n");
        sb.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (Map.Entry<String, List<AbResult>> entry : results.entrySet()) {
            List<AbResult> list = entry.getValue();
            sb.append("| ").append(entry.getKey())
                    .append(" | ").append(String.format("%.0f%%", successRate(list) * 100))
                    .append(" | ").append(String.format("%.0f%%", artifactRate(list) * 100))
                    .append(" | ").append(String.format("%.0f", avgFirstVisible(list)))
                    .append(" | ").append(String.format("%.0f", list.stream().mapToLong(AbResult::endToEndMs).average().orElse(0)))
                    .append(" | ").append(list.stream().mapToInt(AbResult::deltaFrames).sum())
                    .append(" | ").append(totalPromptTokens(list))
                    .append(" | ").append(list.stream().mapToInt(AbResult::completionTokens).sum())
                    .append(" | ").append(list.stream().mapToInt(AbResult::usageMissingCalls).sum())
                    .append(" |\n");
        }
        if (abortReason == null) {
            sb.append("\n## 判定\n\n");
            sb.append(String.format("- ① 首字时间提前（方向性，硬断言）：**%s**（%.0f ms → %.0f ms）%n",
                    mark(judgment.firstVisibleEarlier()), judgment.offFirstVisibleMs(), judgment.onFirstVisibleMs()));
            double off = judgment.offFirstVisibleMs();
            if (off > 0) {
                sb.append(String.format("  - 提前幅度：**%.0f ms（%.0f%%）**%n",
                        off - judgment.onFirstVisibleMs(), (off - judgment.onFirstVisibleMs()) / off * 100));
            }
            sb.append(String.format("- ② 流式真的推出了增量帧：**%s**（Σ deltaFrames = %d）%n",
                    mark(judgment.streamOnDeltaFrames() > 0), judgment.streamOnDeltaFrames()));
            boolean usageOk = judgment.streamOnUsageMissing() == 0;
            sb.append(String.format("- ③ 流式下 token 采集完整（include_usage 生效）：**%s**（usageMissing = %d）%n",
                    mark(usageOk), judgment.streamOnUsageMissing()));
            if (!usageOk) {
                sb.append("  - ⚠️ 有 usage 缺失：上表的 token 数字**不可比**，先查 `stream_options.include_usage`\n");
            }
            sb.append(String.format("- ④ token 消耗未变贵（参考项）：ΣpromptTokens %d vs %d，ΣcompletionTokens 见上表%n",
                    judgment.onTokens(), judgment.offTokens()));
            sb.append(String.format("%n→ **%s**%n", judgment.firstVisibleEarlier() && judgment.streamOnDeltaFrames() > 0
                    ? "流式生效且首字时间确实提前：`yuai.stream.enabled` 保持默认 true"
                    : "未达标：检查增量出口是否安装（runStream 路径）与 `yuai.stream.enabled` 配置"));
        }
        return sb.toString();
    }

    private static String mark(boolean ok) {
        return ok ? "✅ 达标" : "❌ 未达标";
    }

    private double avgFirstVisible(List<AbResult> list) {
        // 只统计真的发出过内容的 run：-1 是「没测到」，混进平均会算出负数这种没有意义的数字
        return list.stream().mapToLong(AbResult::firstVisibleMs).filter(v -> v >= 0).average().orElse(-1);
    }

    private double successRate(List<AbResult> list) {
        return list.isEmpty() ? 0 : list.stream().filter(AbResult::success).count() * 1.0 / list.size();
    }

    private double artifactRate(List<AbResult> list) {
        return list.isEmpty() ? 0 : list.stream().filter(AbResult::artifactOk).count() * 1.0 / list.size();
    }

    private long totalPromptTokens(List<AbResult> list) {
        return list.stream().mapToLong(AbResult::promptTokens).sum();
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // 删不掉不影响本轮判定（产物验证会在旧文件上得到偏乐观的结果，日志里能看到）
        }
    }

    /** 一条任务的定义：任务文本 + 期望产物（null = 不产生产物，只看对话质量） */
    private record AbTask(String name, String task, Path outputFile) {
    }

    /** 一次运行的度量快照（全部取自 AgentRunMetrics，超时路径给零值） */
    private record AbResult(String mode, String task, String outcome, boolean success, boolean artifactOk,
                            int steps, int llmCalls, int promptTokens, int completionTokens, int usageMissingCalls,
                            long firstVisibleMs, int deltaFrames, long endToEndMs, long llmMs) {

        static AbResult of(String mode, String task, AgentRunMetrics metrics, long wallMs, boolean artifactOk) {
            // 成功判定看指标里的 outcome：runStream 不返回答案字符串，FINISHED 是唯一客观信号
            return new AbResult(mode, task, metrics.getOutcome(), "FINISHED".equals(metrics.getOutcome()), artifactOk,
                    metrics.getSteps(), metrics.getLlmCalls(), metrics.getPromptTokens(), metrics.getCompletionTokens(),
                    metrics.getUsageMissingCalls(), metrics.getFirstVisibleMs(), metrics.getDeltaFrames(),
                    metrics.getEndToEndMs(), metrics.getLlmMillis());
        }

        static AbResult timeout(String mode, String task) {
            return new AbResult(mode, task, "TIMEOUT", false, false, 0, 0, 0, 0, 0, -1, 0,
                    CASE_TIMEOUT_SECONDS * 1000, 0);
        }

        String toConsoleLine() {
            return String.format("[%s] %s -> outcome=%s 产物=%s steps=%d llmCalls=%d firstVisibleMs=%d "
                            + "endToEndMs=%d deltaFrames=%d promptTokens=%d usageMissing=%d",
                    mode, task, outcome, artifactOk ? "✅" : "❌", steps, llmCalls, firstVisibleMs,
                    endToEndMs, deltaFrames, promptTokens, usageMissingCalls);
        }

        boolean timedOut() {
            return "TIMEOUT".equals(outcome);
        }
    }
}
