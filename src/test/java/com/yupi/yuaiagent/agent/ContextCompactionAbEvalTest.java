package com.yupi.yuaiagent.agent;

import com.yupi.yuaiagent.constant.FileConstant;
import com.yupi.yuaiagent.context.ContextProperties;
import com.yupi.yuaiagent.context.ContextWindowManager;
import com.yupi.yuaiagent.context.TranscriptWriter;
import com.yupi.yuaiagent.memory.ConversationMemoryService;
import com.yupi.yuaiagent.memory.MemoryAssembler;
import com.yupi.yuaiagent.memory.MemoryUpdateService;
import com.yupi.yuaiagent.metrics.AgentRunMetrics;
import com.yupi.yuaiagent.toolrouter.ToolRoutingProperties;
import com.yupi.yuaiagent.toolrouter.ToolVectorRouter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.BeanUtils;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文压缩 A/B 评测（2026-10 上下文重构 · 阶段 4）
 * <p>
 * <b>问题</b>：旧机制按「消息条数 &gt; 15」压缩，与真实体积无关（一个工具步固定 +3 条，
 * 但一步可能产生 32KB 网页正文也可能 5 个字）；新机制按 token 预算分两级（先清旧工具输出、
 * 不够再 LLM 摘要）。<b>光有机制不算完成——必须有数字证明它更好</b>，本类就是那个数字。
 * <p>
 * <b>实验设计（两组跑同一批任务，唯一变量是压缩策略）</b>
 * <ul>
 *   <li>{@code legacy}：默认配置，触发条件 = 消息条数 &gt; 15；</li>
 *   <li>{@code budget}：{@code window-tokens=12000}（把 32K 窗口砍到 12K 做压力测试，
 *       否则默认窗口下这批 5~7 步任务根本到不了 16K 触发线，A/B 会变成"两组都没压缩"的空跑）。</li>
 * </ul>
 * 注意这是<b>对 budget 组更不利</b>的设置：它比 legacy 更容易被触发压缩，
 * 若即便如此它成功率不降、token 更低，结论才站得住。
 * <p>
 * <b>判定标准（写进断言）</b>：① budget 成功率 ≥ legacy；② promptTokens 或重复工具调用率
 * 至少一项更低。数字同时写进 {@code tmp/metrics/context-ab-<时间戳>.md}，供文档引用。
 * <p>
 * <b>成本</b>：默认 4 任务 × 2 模式 ≈ 50~70 次真实 LLM 调用、10~20 分钟，所以默认<b>不执行</b>：
 * <pre>RUN_CONTEXT_EVAL=true ./mvnw test -Dtest=ContextCompactionAbEvalTest</pre>
 * 抽样：{@code CONTEXT_EVAL_CASES=2}（任务数，默认 4）；{@code CONTEXT_EVAL_WINDOW_TOKENS=6000}（budget 组窗口，默认 12000，越小压力越大）。
 */
@Slf4j
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_CONTEXT_EVAL", matches = "true")
class ContextCompactionAbEvalTest {

    /** 单任务超时（秒）：多步任务 + 压缩重试，给足余量 */
    private static final long CASE_TIMEOUT_SECONDS = 240;

    /** budget 组的窗口：默认 32K 下这批任务触发不了压缩，砍到 12K 做压力测试（可用 CONTEXT_EVAL_WINDOW_TOKENS 覆盖） */
    private static final int BUDGET_WINDOW_TOKENS = Integer.parseInt(
            System.getenv().getOrDefault("CONTEXT_EVAL_WINDOW_TOKENS", "12000"));

    /**
     * 评测隔离（2026-10-11 单用户单会话重构）：记忆全局一份，隔离手段只剩「换根目录」——
     * 把 {@code yuai.memory.dir} 指到 target/ 下本次运行专属目录，绝不污染真实 tmp/memory。
     * <p>
     * {@code restore-turns=0} 是实验设计的一部分：A/B 的唯一变量必须是「压缩策略」，
     * 若每次 run 起始恢复 12 条近场历史，两组都会被垫高 12 条消息——legacy 组会被
     * 直接推过「>15 条」触发线（压缩行为被恢复功能改变），比较就不再干净。
     */
    @org.springframework.test.context.DynamicPropertySource
    static void isolateMemory(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("yuai.memory.dir", () -> "target/eval-memory/context-ab-" + UUID.randomUUID());
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
    @DisplayName("A/B：legacy（按条数压缩）vs budget（token 预算两级压缩）")
    void contextCompactionAbEval() throws Exception {
        Path longFile = Files.createTempFile("ctx-ab-long", ".txt");
        // 长文（约 4000 字符）：目的是让 readFile 的工具输出足够大，把上下文推到触发线附近
        Files.writeString(longFile, ("上下文管理是智能体的核心能力之一。它决定了模型在长任务中"
                + "能不能记住原始目标、能不能复用早先调用的工具结果、以及每一步要付多少 token。\n").repeat(40));
        Path fileA = Files.createTempFile("ctx-ab-a", ".txt");
        Files.writeString(fileA, "文件A：主题是向量检索；包含三个要点：嵌入、相似度、召回。\n".repeat(10));
        Path fileB = Files.createTempFile("ctx-ab-b", ".txt");
        Files.writeString(fileB, "文件B：主题是关键词检索；包含两个要点：分词、倒排索引。\n".repeat(10));

        int caseCount = Integer.parseInt(System.getenv().getOrDefault("CONTEXT_EVAL_CASES", "4"));
        List<String> modes = List.of("legacy", "budget");
        Map<String, List<AbResult>> results = new LinkedHashMap<>();
        // 一旦有任务超时就整轮作废：超时几乎都是外部 API 出问题（2026-10-10 实测 DeepSeek 抖动），
        // 而 future.cancel(true) 并不能真的掐断已经在跑的 LLM 调用——被放弃的 agent 会继续占着 API 打，
        // 让后面每个任务都更难成功。继续跑只会得到一张"两组都很难看"的表，没有解释力。
        String abortReason = null;

        for (String mode : modes) {
            ContextWindowManager manager = managerFor(mode);
            List<AbTask> tasks = buildTasks(mode, longFile, fileA, fileB);
            if (caseCount < tasks.size()) {
                tasks = tasks.subList(0, caseCount);
            }
            List<AbResult> modeResults = new ArrayList<>();
            for (int i = 0; i < tasks.size(); i++) {
                deleteQuietly(tasks.get(i).outputFile());
                AbResult result = runOne(mode, tasks.get(i), i + 1, manager);
                modeResults.add(result);
                System.out.println(result.toConsoleLine());
                if (result.timedOut()) {
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

        // ---- 判定：写进报告作为决策记录，**故意不做断言** ----
        // 方案 §4.2 的三条判定标准回答的是"要不要把默认开关翻成 budget"——那是产品决策，不是不变量：
        //   ① 成功率不降                        → 不变量，下面硬断言（压缩不许把任务压坏）
        //   ② token / 重复调用率至少一项显著下降 → 决策依据，进报告
        //   ③ 压缩次数不增加                    → 决策依据，进报告
        // ②③ 一旦写成硬断言，**刻意越界的压力轮必然变红**：2026-10-10 的 6K 轮里，budget 的保护集
        //（记忆块 + 原始任务 + 最近 6 条）本身就顶到窗口，压缩后仍在触发线之上 → 反复压缩，模型又因为
        // 工具输出被清掉而重复调用（dupRate 0.118），Σtoken 1.6×。那正是压力轮要暴露的现象，不是测试坏了。
        // 决策记录（未达标 → 默认保持 legacy）见 docs/context-memory-refactor.md §5.2。
        Judgment judgment = judge(results);
        String report = renderReport(results, abortReason, judgment);
        System.out.println(report);
        Path out = Path.of(FileConstant.FILE_SAVE_DIR, "metrics",
                "context-ab-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);
        System.out.println("A/B 报告已写入: " + out.toAbsolutePath());

        // 超时中止的那轮不许当成结果：先落盘留证据，再明确失败
        assertTrue(abortReason == null, "本轮 A/B 未跑完：" + abortReason);

        // ---- 不变量（先落盘再断言：断言失败也要留下数字）----
        double legacySuccess = successRate(results.get("legacy"));
        double budgetSuccess = successRate(results.get("budget"));
        assertTrue(budgetSuccess >= legacySuccess,
                String.format("budget 成功率(%s) 不应低于 legacy(%s)", budgetSuccess, legacySuccess));
        assertEquals(1.0, artifactRate(results.get("budget")),
                "budget 组每个任务都必须落下产物——「答得像」不算数");
    }

    // ---- 实验装置 ----

    /**
     * 造一个指定模式的 manager：{@link ContextProperties} 是 {@code @Data}，
     * 复制默认配置再改 mode/window 即可，<b>不需要第二个 Spring 上下文</b>（省一次启动 + 一次 PG 连接）。
     */
    private ContextWindowManager managerFor(String mode) {
        ContextProperties copy = new ContextProperties();
        BeanUtils.copyProperties(contextProperties, copy);
        copy.setMode(mode);
        if ("budget".equals(mode)) {
            copy.setWindowTokens(BUDGET_WINDOW_TOKENS);
        }
        return new ContextWindowManager(copy, transcriptWriter);
    }

    /**
     * 任务集：每条都要求「多步 + 落一个产物」，产物用于判定"选择正确 ≠ 执行成功"。
     * 步骤刻意排到 4~5 步（工具调用 4 个左右），让上下文有足够长度逼近两种策略的触发线。
     */
    private List<AbTask> buildTasks(String mode, Path longFile, Path fileA, Path fileB) {
        Path out1 = outputPath(mode, 1);
        Path out2 = outputPath(mode, 2);
        Path out3 = outputPath(mode, 3);
        Path out4 = outputPath(mode, 4);
        Path out5 = outputPath(mode, 5);
        return List.of(
                new AbTask("搜索+写入+终端确认",
                        "请按顺序完成以下步骤：1) 在网上搜索 Spring AI 的三个核心特性；"
                                + "2) 把三条特性写入文件 " + out1 + "；3) 用终端命令确认该文件存在；4) 汇报完成情况。",
                        out1),
                new AbTask("读长文+摘要+写入",
                        "请按顺序完成以下步骤：1) 读取文件 " + longFile + " 的全部内容；"
                                + "2) 把内容总结成三条要点；3) 写入文件 " + out2 + "；4) 用终端确认该文件非空。",
                        out2),
                new AbTask("搜索+抓取+写入+确认",
                        "请按顺序完成以下步骤：1) 搜索 \"Spring AI 1.0 新特性\"；"
                                + "2) 抓取搜索结果中第一个网页的正文；3) 把摘要写入文件 " + out3 + "；"
                                + "4) 用终端确认文件已生成。",
                        out3),
                new AbTask("读两文件+对比+写入",
                        "请按顺序完成以下步骤：1) 读取文件 " + fileA + "；2) 读取文件 " + fileB + "；"
                                + "3) 对比两个文件的差异并写入文件 " + out4 + "；4) 用终端确认。",
                        out4),
                // 长链路（专为一级微压缩准备）：6~7 次工具调用才能造出"最近 3 条之外还有内容"的局面，
                // 短任务里 keep=3 会把全部工具结果保护起来，microcompact 根本没机会触发
                new AbTask("长链路：三读一搜一写一终端",
                        "请严格按顺序执行以下 6 个步骤，每一步都必须真实调用工具，不要跳过："
                                + "1) 读取文件 " + fileA + "；2) 读取文件 " + fileB + "；3) 读取文件 " + longFile + "；"
                                + "4) 在网上搜索 \"Spring AI 向量检索\"；"
                                + "5) 把上面三个文件的要点与搜索结果汇总写入文件 " + out5 + "；"
                                + "6) 用终端命令查看 " + out5 + " 的内容确认写入成功。",
                        out5)
        );
    }

    private Path outputPath(String mode, int index) {
        return Path.of(FileConstant.FILE_SAVE_DIR, "ctx-ab", mode + "-" + index + ".md").toAbsolutePath();
    }

    /**
     * 跑一条任务。与 {@code ToolSelectionAccuracyTest} 一致：独立线程 + 超时保护，
     * 避免个别 LLM 调用挂起拖垮整轮评测（超时的 case 计入失败而不是让整类测试挂死）。
     */
    private AbResult runOne(String mode, AbTask task, int index, ContextWindowManager manager) {
        CompletableFuture<AbResult> future = CompletableFuture.supplyAsync(() -> {
            // streamingProperties 传 null（2026-10 流式轮）：本 A/B 的唯一变量必须是压缩策略，
            // 且 run() 链路本就不装增量出口——显式钉住"不启用流式"，别让流式混进来当第二个变量
            YuManus agent = new YuManus(allTools, chatModel, memoryAssembler, memoryUpdateService,
                    toolVectorRouter, toolRoutingProperties, conversationMemoryService, manager, null);
            // 会话 ID 带模式：两种模式的 transcript 互不覆盖，便于事后对照
            // （2026-10-11 起它不再参与记忆寻址——记忆是单用户单会话的）
            agent.setConversationId("ctx-ab-" + mode + "-" + index);
            long start = System.currentTimeMillis();
            String output = agent.run(task.task());
            long wallMs = System.currentTimeMillis() - start;
            AgentRunMetrics metrics = agent.getLastRunMetrics();
            boolean artifactOk = Files.exists(task.outputFile());
            return AbResult.of(mode, task.name(), metrics, wallMs, output, artifactOk);
        });
        try {
            return future.get(CASE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("[{}] 任务 {} 超时({}s)", mode, task.name(), CASE_TIMEOUT_SECONDS);
            return AbResult.timeout(mode, task.name());
        } catch (Exception e) {
            log.warn("[{}] 任务 {} 执行异常: {}", mode, task.name(), e.getMessage());
            return AbResult.error(mode, task.name(), e.getMessage());
        }
    }

    // ---- 报表与统计 ----

    /**
     * 方案 §4.2 的三条判定标准。
     * <p>
     * ②③ 是"要不要翻默认开关"的依据，不是测试的不变量（理由见调用点注释）；把它们算出来写进报告，
     * 是为了让每份报告都自带结论——数字与决策在同一个文件里，事后不用去翻聊天记录。
     */
    private Judgment judge(Map<String, List<AbResult>> results) {
        List<AbResult> legacy = results.getOrDefault("legacy", List.of());
        List<AbResult> budget = results.getOrDefault("budget", List.of());
        long legacyTokens = totalPromptTokens(legacy);
        long budgetTokens = totalPromptTokens(budget);
        double legacyDup = averageDuplicateRate(legacy);
        double budgetDup = averageDuplicateRate(budget);
        int legacyCompactions = legacy.stream().mapToInt(AbResult::compactions).sum();
        int budgetCompactions = budget.stream().mapToInt(AbResult::compactions).sum();
        return new Judgment(
                successRate(budget) >= successRate(legacy),
                // "显著下降"取 10%：单任务 token 的 run-to-run 抖动实测 ≥±20%（docs §5.2），
                // 幅度小于这个数的差异不配当结论。
                significantlyLower(budgetTokens, legacyTokens) || significantlyLower(budgetDup, legacyDup),
                budgetCompactions <= legacyCompactions,
                legacyTokens, budgetTokens, legacyDup, budgetDup, legacyCompactions, budgetCompactions);
    }

    /**
     * 候选值是否比基线**显著**（≥10%）下降。
     * <p>
     * <b>必须同时判"真的更低"</b>，不能只写 {@code candidate <= baseline * 0.9}：
     * 两组都是 0 时 {@code 0 <= 0} 恒真——本轮 dupToolCallRate 两组实测全是 0.000，
     * 第一版判定器就靠这个**空真**（vacuous truth）把 6K 轮 ② 误标成了达标（那一轮 token 实际只降 1.7%）。
     * 判据写宽松会让"没测出差异"冒充"测出了优势"，这比没做评测更糟。
     */
    private static boolean significantlyLower(double candidate, double baseline) {
        return candidate < baseline && candidate <= baseline * 0.9;
    }

    /** 判定结果：三项是否达标 + 支撑数字（报告要能自证，别只给结论） */
    private record Judgment(boolean successOk, boolean tokenOk, boolean compactionOk,
                            long legacyTokens, long budgetTokens, double legacyDup, double budgetDup,
                            int legacyCompactions, int budgetCompactions) {
        boolean allMet() {
            return successOk && tokenOk && compactionOk;
        }
    }

    private String renderReport(Map<String, List<AbResult>> results, String abortReason, Judgment judgment) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 上下文压缩 A/B 评测报告\n\n");
        sb.append("生成时间: ").append(LocalDateTime.now()).append("\n\n");
        if (abortReason != null) {
            sb.append("> ⚠️ **本轮未跑完（数字不可用）**：").append(abortReason).append("\n\n");
        }
        sb.append("- legacy = 默认配置（按消息条数 > 15 触发压缩）\n");
        sb.append("- budget = token 预算两级压缩，window-tokens=").append(BUDGET_WINDOW_TOKENS)
                .append("（压力测试：比 legacy 更容易被触发，结论偏向保守）\n\n");
        sb.append("| mode | task | outcome | 产物 | steps | llmCalls | promptTokens | peakPrompt | peak/窗口 | compactions | microcompactions | 清理tokens | dupToolCallRate | est/real | wallMs |\n");
        sb.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (Map.Entry<String, List<AbResult>> entry : results.entrySet()) {
            for (AbResult r : entry.getValue()) {
                sb.append("| ").append(r.mode())
                        .append(" | ").append(r.task())
                        .append(" | ").append(r.outcome())
                        .append(" | ").append(r.artifactOk() ? "✅" : "❌")
                        .append(" | ").append(r.steps())
                        .append(" | ").append(r.llmCalls())
                        .append(" | ").append(r.promptTokens())
                        .append(" | ").append(r.maxPromptTokens())
                        .append(" | ").append(String.format("%.0f%%", peakWindowRatio(r) * 100))
                        .append(" | ").append(r.compactions())
                        .append(" | ").append(r.microcompactions())
                        .append(" | ").append(r.clearedToolResultTokens())
                        .append(" | ").append(String.format("%.3f", r.duplicateToolCallRate()))
                        .append(" | ").append(String.format("%.3f", r.tokenEstimateRatio()))
                        .append(" | ").append(r.wallMs())
                        .append(" |\n");
            }
        }
        sb.append("\n## 汇总\n\n");
        sb.append("| mode | 成功率 | 产物落盘率 | Σ promptTokens | peakPrompt 均值 | peak/窗口 均值 | Σ llmCalls | Σ compactions | Σ microcompactions | 平均 dupToolCallRate | 平均 wallMs |\n");
        sb.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (Map.Entry<String, List<AbResult>> entry : results.entrySet()) {
            List<AbResult> list = entry.getValue();
            sb.append("| ").append(entry.getKey())
                    .append(" | ").append(String.format("%.0f%%", successRate(list) * 100))
                    .append(" | ").append(String.format("%.0f%%", artifactRate(list) * 100))
                    .append(" | ").append(totalPromptTokens(list))
                    .append(" | ").append(String.format("%.0f", list.stream().mapToInt(AbResult::maxPromptTokens).average().orElse(0)))
                    .append(" | ").append(String.format("%.0f%%", list.stream().mapToDouble(this::peakWindowRatio).average().orElse(0) * 100))
                    .append(" | ").append(list.stream().mapToInt(AbResult::llmCalls).sum())
                    .append(" | ").append(list.stream().mapToInt(AbResult::compactions).sum())
                    .append(" | ").append(list.stream().mapToInt(AbResult::microcompactions).sum())
                    .append(" | ").append(String.format("%.3f", averageDuplicateRate(list)))
                    .append(" | ").append(list.stream().mapToLong(AbResult::wallMs).sum() / Math.max(list.size(), 1))
                    .append(" |\n");
        }
        if (abortReason == null) {
            sb.append("\n## 判定（方案 §4.2：三条全达标才允许把 `yuai.context.mode` 默认翻成 budget）\n\n");
            sb.append(String.format("- ① 成功率不降：**%s**（budget vs legacy 见上表）%n", mark(judgment.successOk())));
            sb.append(String.format("- ② promptTokens 或重复调用率至少一项下降 ≥10%%：**%s**（Σtoken %d vs %d%s）%n",
                    mark(judgment.tokenOk()), judgment.budgetTokens(), judgment.legacyTokens(),
                    // 两组都是 0 必须显式说明"不计入"，否则读者会以为这条是被 dup 的下降满足的
                    judgment.legacyDup() == 0 && judgment.budgetDup() == 0
                            ? "，dup 两组均为 0，不计入"
                            : String.format("，dup %.3f vs %.3f", judgment.budgetDup(), judgment.legacyDup())));
            sb.append(String.format("- ③ 压缩次数不增加：**%s**（%d vs %d）%n",
                    mark(judgment.compactionOk()), judgment.budgetCompactions(), judgment.legacyCompactions()));
            sb.append(String.format("%n→ **%s**%n", judgment.allMet()
                    ? "三条全达标：可以把默认开关翻成 budget"
                    : "未达标：`yuai.context.mode` 默认保持 legacy（决策与取舍见 docs/context-memory-refactor.md §5.2）"));
        }
        return sb.toString();
    }

    private static String mark(boolean ok) {
        return ok ? "✅ 达标" : "❌ 未达标";
    }

    /**
     * 单次调用的 prompt 峰值占「模型窗口」的比例。
     * <p>
     * legacy 组其实没有窗口概念（它按条数压缩），这里的窗口统一取 budget 组的配置值——
     * 意思是"如果模型窗口只有这么大，这一组会不会顶到边上"。这正是「该压不压」的量化：
     * 旧机制完全可能在消息只有 12 条时就把单次 prompt 顶到窗口的 90%，而压缩一次都没触发。
     */
    private double peakWindowRatio(AbResult result) {
        return result.maxPromptTokens() * 1.0 / BUDGET_WINDOW_TOKENS;
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

    private double averageDuplicateRate(List<AbResult> list) {
        return list.isEmpty() ? 0 : list.stream().mapToDouble(AbResult::duplicateToolCallRate).average().orElse(0);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // 删不掉不影响本轮判定（产物验证会在旧文件上得到偏乐观的结果，日志里能看到）
        }
    }

    /** 一条任务的定义：任务文本 + 期望产物路径（产物存在即视为执行成功） */
    private record AbTask(String name, String task, Path outputFile) {
    }

    /** 一次运行的度量快照（全部取自 AgentRunMetrics，超时/异常路径给零值） */
    private record AbResult(String mode,
                            String task,
                            String outcome,
                            boolean success,
                            boolean artifactOk,
                            int steps,
                            int llmCalls,
                            int promptTokens,
                            int maxPromptTokens,
                            int completionTokens,
                            int compactions,
                            int microcompactions,
                            int clearedToolResultTokens,
                            double duplicateToolCallRate,
                            double tokenEstimateRatio,
                            long wallMs) {

        static AbResult of(String mode, String task, AgentRunMetrics metrics, long wallMs,
                           String output, boolean artifactOk) {
            if (metrics == null) {
                return new AbResult(mode, task, "NO_METRICS", false, artifactOk, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, wallMs);
            }
            // 成功判定：跑到 FINISHED + 产物真的落盘 + 答案不是异常兜底串
            // （"工具选对了"不等于"任务做成了"；BaseAgent.run 会把异常吞成 "出错了：..." 的字符串返回）
            boolean answerOk = output != null && !output.isBlank() && !output.startsWith("出错了");
            boolean finished = "FINISHED".equals(metrics.getOutcome());
            return new AbResult(mode, task, metrics.getOutcome(), finished && artifactOk && answerOk, artifactOk,
                    metrics.getSteps(), metrics.getLlmCalls(), metrics.getPromptTokens(),
                    metrics.getMaxPromptTokens(), metrics.getCompletionTokens(),
                    metrics.getCompactions(), metrics.getMicrocompactions(),
                    metrics.getClearedToolResultTokens(), metrics.duplicateToolCallRate(),
                    metrics.tokenEstimateRatio(), wallMs);
        }

        static AbResult timeout(String mode, String task) {
            return new AbResult(mode, task, "TIMEOUT", false, false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    CASE_TIMEOUT_SECONDS * 1000);
        }

        static AbResult error(String mode, String task, String message) {
            return new AbResult(mode, task, "ERROR: " + message, false, false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        String toConsoleLine() {
            return String.format("[%s] %s -> outcome=%s success=%s 产物=%s steps=%d llmCalls=%d promptTokens=%d "
                            + "peakPrompt=%d compactions=%d microcompactions=%d dupRate=%.3f wallMs=%d",
                    mode, task, outcome, success, artifactOk ? "✅" : "❌", steps, llmCalls, promptTokens,
                    maxPromptTokens, compactions, microcompactions, duplicateToolCallRate, wallMs);
        }

        boolean timedOut() {
            return "TIMEOUT".equals(outcome);
        }
    }
}
