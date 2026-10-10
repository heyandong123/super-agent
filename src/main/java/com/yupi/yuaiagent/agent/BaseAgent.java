package com.yupi.yuaiagent.agent;

import cn.hutool.core.util.StrUtil;
import com.yupi.yuaiagent.agent.model.AgentState;
import com.yupi.yuaiagent.memory.MemoryAssembler;
import com.yupi.yuaiagent.memory.MemoryContext;
import com.yupi.yuaiagent.memory.MemoryUpdateService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 抽象基础代理类（Agent 的超级心脏）
 * 核心职责：维护状态机、管理短期记忆（Message Context）、驱动 ReAct (思考-行动) 循环。
 */
@Data
@Slf4j
public abstract class BaseAgent {

    /** SSE 心跳间隔（秒）。空闲太久时，中间的代理/浏览器会把「一直没事件」当成连接已死而断开。 */
    private static final long HEARTBEAT_SECONDS = 15;

    /**
     * Agent 执行线程池。刻意不用 {@link java.util.concurrent.CompletableFuture#runAsync}：
     * 它跑在公共 ForkJoinPool 上，拿不到句柄；更关键的是，future.cancel(true) 对「已经开始执行的任务」
     * 不会产生任何中断（CompletableFuture 根本不走中断取消那条路径）。结果是 SSE 超时/客户端断开后，
     * 正在跑的步骤停不下来——超时回调把状态改成 IDLE，而循环条件仍成立，于是继续多跑含 LLM 调用与
     * 工具副作用的步骤。显式线程池 + Future 才能真正中断 worker。
     */
    private static final ExecutorService AGENT_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "agent-worker");
        // 守护线程：残留的 worker 不该拖住 JVM 退出
        thread.setDaemon(true);
        return thread;
    });

    /** 心跳调度器。单个守护线程即可，心跳任务本身不做任何业务。 */
    private static final ScheduledExecutorService HEARTBEAT_SCHEDULER = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "agent-sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    private String name;
    private List<Message> messageList = new ArrayList<>();
    private String systemPrompt;
    private String nextStepPrompt;
    /**
     * 状态机。worker 线程在循环里读，Servlet 线程在超时/断开回调里写，必须 volatile——
     * 否则 worker 可能永远看不到 FINISHED/IDLE，连接早断了还在继续跑步骤。
     */
    private volatile AgentState state = AgentState.IDLE;
    private int currentStep = 0;
    private int maxSteps = 8;
    /**
     * 本步实际执行的工具名（结构化来源：子类在 step() 内通过 recordStepTools 写入）。
     * SSE process 事件的 tool 字段直接取这里，不再回头解析 step() 返回的拼接字符串。
     */
    private List<String> lastStepTools = List.of();
    private ChatClient chatClient;
    private String conversationId;
    /**
     * 用户标识（复合记忆键的第一段）。没有用户体系时默认 "default"。
     */
    private String userId = "default";
    private MemoryAssembler memoryAssembler;
    private MemoryUpdateService memoryUpdateService;
    /**
     * 同步运行代理（会阻塞当前线程，直到大模型把所有步骤思考执行完毕）
     */
    public String run(String userPrompt) {
        if(state != AgentState.IDLE){
            throw new RuntimeException("agent状态错误！");
        }
        if(StrUtil.isBlank(userPrompt)){
            throw new RuntimeException("用户提示词为空！");
        }
        this.state = AgentState.RUNNING;
        messageList.clear();
        initializeMemoryLayers("agent_task");
        List<String> Results = new ArrayList<>();
        messageList.add(new UserMessage(userPrompt));
        String finalOutput = "";
        try {
            // 只在 RUNNING 时继续。用 state != FINISHED 时，任何把状态改成 ERROR/IDLE 的路径
            // （超时回调、外部中断）都会让循环继续跑下去。
            for (int i = 0; i < maxSteps && state == AgentState.RUNNING; i++) {
                int stepNumber = i + 1;
                beginStep(stepNumber);
                String stepRssult = step();
                Results.add("Step" + currentStep + ": " + stepRssult);
                if(currentStep >= maxSteps){
                    this.state = AgentState.FINISHED;
                    Results.add("agent到达最大步数");
                }
            }
            finalOutput = String.join("\n",Results);
            return finalOutput;
        } catch (Exception e) {
            this.state = AgentState.ERROR;
            finalOutput = "出错了：" + e.getMessage();
            return finalOutput;
        } finally {
            // 顺序很重要：先清理运行态，再写记忆。记忆写入要调 LLM 且可能抛异常，
            // 放在前面一旦抛出去，cleanup() 就被跳过，状态机会永远停在 RUNNING/ERROR 上。
            this.cleanup();
            recordMemorySafely(userPrompt, finalOutput);
        }
    }
    /**
     //     * 运行代理（流式输出）
     //     *
     //     * @param userPrompt 用户提示词
     //     * @return 执行结果
     //     */
    public SseEmitter runStream(String userPrompt) {
        // 创建一个超时时间较长的 SseEmitter
        SseEmitter sseEmitter = new SseEmitter(300000L); // 5 分钟超时
        // 心跳：用 SSE 注释帧（: keep-alive）保活。注释帧不会触发前端的 message 事件，
        // 也不会被按事件名监听的 process/final 处理，纯粹是给中间代理看的「我还活着」。
        ScheduledFuture<?> heartbeat = HEARTBEAT_SCHEDULER.scheduleAtFixedRate(() -> {
            try {
                sseEmitter.send(SseEmitter.event().comment("keep-alive"));
            } catch (Exception e) {
                // 连接已经断了。抛异常让调度器自动取消这个周期任务，避免永远重试。
                throw new IllegalStateException("SSE 心跳发送失败，连接可能已断开", e);
            }
        }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        // 使用独立线程池异步处理，避免阻塞主线程。必须持有 Future，超时/断开时才能中断它。
        Future<?> worker = AGENT_EXECUTOR.submit(() -> {
            // 1、基础校验
            try {
                if (this.state != AgentState.IDLE) {
                    sseEmitter.send("错误：无法从状态运行代理：" + this.state);
                    sseEmitter.complete();
                    return;
                }
                if (StrUtil.isBlank(userPrompt)) {
                    sseEmitter.send("错误：不能使用空提示词运行代理");
                    sseEmitter.complete();
                    return;
                }
            } catch (Exception e) {
                sseEmitter.completeWithError(e);
            }
            // 2、服务端确认会话：无会话时生成 conversationId，通过 session 事件回传前端
            //    （前端首次不带 chatId → 此处生成 UUID → 前端存 localStorage 后续复用）
            try {
                // conversationId 可能来自客户端传入的 chatId，含引号会直接破坏 JSON，必须转义
                String sessionJson = "{\"conversationId\":\"" + escapeJson(resolveConversationId())
                        + "\",\"userId\":\"" + escapeJson(getUserId()) + "\"}";
                sseEmitter.send(SseEmitter.event().name("session").data(sessionJson));
            } catch (Exception e) {
                sseEmitter.completeWithError(e);
                return;
            }
            // 3、执行，更改状态
            this.state = AgentState.RUNNING;
            messageList.clear();
            initializeMemoryLayers("agent_task");
            // 记录消息上下文
            messageList.add(new UserMessage(userPrompt));
            // 保存结果列表
            List<String> results = new ArrayList<>();
            try {
                // 执行循环。条件同样是 == RUNNING：连接超时/断开时回调会把状态挪出 RUNNING，
                // 这里就立刻停下来，不再继续消耗 LLM 调用与工具副作用。
                for (int i = 0; i < maxSteps && state == AgentState.RUNNING; i++) {
                    int stepNumber = i + 1;
                    beginStep(stepNumber);
                    log.info("Executing step {}/{}", stepNumber, maxSteps);
                    String stepResult = step();
                    results.add(stepResult);
                    // 结构化事件: type=process 表示执行过程,type=final 表示最终结果
                    String eventJson = buildProcessEvent(stepNumber, stepResult);
                    sseEmitter.send(SseEmitter.event().name("process").data(eventJson));
                }
                // 发送最终结果
                String summary = buildFinalSummary(results);
                String finalJson = "{\"type\":\"final\",\"content\":\"" + escapeJson(summary) + "\"}";
                sseEmitter.send(SseEmitter.event().name("final").data(finalJson));
                // 检查是否超出步骤限制
                if (currentStep >= maxSteps) {
                    state = AgentState.FINISHED;
                    log.warn("Agent 达到最大步数: {}", maxSteps);
                }
                // 正常完成
                sseEmitter.complete();
            } catch (Exception e) {
                state = AgentState.ERROR;
                log.error("error executing agent", e);
                try {
                    sseEmitter.send("执行错误：" + e.getMessage());
                    sseEmitter.complete();
                } catch (IOException ex) {
                    sseEmitter.completeWithError(ex);
                }
            } finally {
                // 收尾顺序：先停心跳、再清运行态、最后写记忆（写记忆会调 LLM，可能失败，
                // 但它失败绝不能拖累状态机——所以 cleanup 在前、且记忆写入自己吞异常）。
                heartbeat.cancel(false);
                this.cleanup();
                recordMemorySafely(userPrompt, String.join("\n", results));
            }
        });

        // 设置超时回调
        sseEmitter.onTimeout(() -> {
            this.state = AgentState.ERROR;
            // 连接已经没了，必须中断 worker，否则它会把剩下的步骤跑完（每步都是真实的 LLM 调用与工具副作用）
            worker.cancel(true);
            heartbeat.cancel(false);
            this.cleanup();
            log.warn("SSE connection timeout");
        });
        // 设置完成回调
        sseEmitter.onCompletion(() -> {
            // 正常结束时 worker 已把状态置为 FINISHED；仍停在 RUNNING 说明是客户端提前断开，
            // 这时同样要中断 worker。
            if (this.state == AgentState.RUNNING) {
                this.state = AgentState.FINISHED;
                worker.cancel(true);
            }
            heartbeat.cancel(false);
            this.cleanup();
            log.info("SSE connection completed");
        });
        // 错误回调：写 SSE 失败时（客户端已断开等）也要收手，否则 worker 会在无人接收的情况下继续跑
        sseEmitter.onError(throwable -> {
            log.warn("SSE connection error: {}", throwable.getMessage());
            this.state = AgentState.ERROR;
            worker.cancel(true);
            heartbeat.cancel(false);
            this.cleanup();
        });
        return sseEmitter;
    }

    // --- 模板方法与钩子 ---
    public abstract String step();

    private String buildProcessEvent(int stepNumber, String stepResult) {
        // tool 字段取自子类记录的结构化工具名；子类未记录（如纯文本步骤）时回退为 act
        String toolName = lastStepTools.isEmpty() ? "act" : String.join(",", lastStepTools);
        String preview = stepResult.length() > 80 ? stepResult.substring(0, 80) + "..." : stepResult;
        return "{\"type\":\"process\",\"step\":" + stepNumber
                + ",\"tool\":\"" + escapeJson(toolName)
                + "\",\"preview\":\"" + escapeJson(preview)
                + "\",\"content\":\"" + escapeJson(stepResult) + "\"}";
    }

    /**
     * 开始新的一步：重置步序号与本步工具记录。
     * 必须在 step() 之前重置——否则某一步没有执行工具（或中途抛异常）时，
     * process 事件会把上一步的工具名当成这一步的，前端看到的执行过程是错的。
     */
    private void beginStep(int stepNumber) {
        this.currentStep = stepNumber;
        this.lastStepTools = List.of();
    }

    /**
     * 记录本步实际执行的工具名（供 SSE process 事件的 tool 字段使用）。
     * 子类在 step() 内调用；压根没执行工具时不要调用，由 buildProcessEvent 回退为 act。
     */
    protected void recordStepTools(String... toolNames) {
        if (toolNames == null || toolNames.length == 0) {
            this.lastStepTools = List.of();
            return;
        }
        this.lastStepTools = java.util.Arrays.stream(toolNames)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
    }

    private String buildFinalSummary(List<String> results) {
        // 1. 首选：LLM 合成最终答案——基于"用户原始问题 + 全部执行结果"重新生成，
        //    保证回答面向用户的问题（而不是面向最后一条消息/过程汇报）
        String synthesized = synthesizeFinalAnswer(results);
        if (StrUtil.isNotBlank(synthesized)) {
            return synthesized;
        }
        // 2. 兜底：从消息列表中提取模型的最终文本回复
        String finalAnswer = extractFinalAnswer();
        if (StrUtil.isNotBlank(finalAnswer)) {
            // 模型文本常缺产物路径（如只说"文件已保存"）——从工具结果提取缺失的产物路径附加，保证用户拿到完整信息
            String artifactPaths = extractMissingArtifactPaths(results, finalAnswer);
            if (StrUtil.isNotBlank(artifactPaths)) {
                return finalAnswer + "\n\n" + artifactPaths;
            }
            return finalAnswer;
        }
        // 2. 兜底:取最后一个有实际内容的工具结果（排除 doTerminate/思考完成）
        for (int i = results.size() - 1; i >= 0; i--) {
            String r = results.get(i);
            if (!r.contains("doTerminate") && !r.contains("思考完成")) {
                // 提取工具返回的实际内容(去掉"工具 xxx 返回的结果:"前缀)
                String content = r.replaceFirst("^.*返回的结果[：:]\\s*", "");
                return content.length() > 1500 ? content.substring(0, 1500) + "\n...(内容已截断)" : content;
            }
        }
        return "任务已执行完成,共 " + results.size() + " 个步骤。";
    }

    /**
     * 从工具执行结果中提取产物文件路径（写入/生成/下载的文件），过滤掉最终答案已包含的路径。
     * 识别 "written/generated/downloaded successfully to: <路径>" 模式，拼成"产物文件"列表。
     */
    private String extractMissingArtifactPaths(List<String> results, String finalAnswer) {
        java.util.regex.Pattern pathPattern = java.util.regex.Pattern.compile(
                "(?:written|generated|downloaded) successfully to:\\s*(.+?)(?:\\n|$)");
        java.util.LinkedHashSet<String> paths = new java.util.LinkedHashSet<>();
        for (String r : results) {
            java.util.regex.Matcher m = pathPattern.matcher(r);
            while (m.find()) {
                String path = m.group(1).trim();
                if (StrUtil.isNotBlank(path) && !finalAnswer.contains(path)) {
                    paths.add(path);
                }
            }
        }
        if (paths.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("产物文件：");
        for (String path : paths) {
            sb.append("\n- ").append(path);
        }
        return sb.toString();
    }

    /**
     * 最终答案合成器：用一次 LLM 调用，基于用户原始问题与全部执行结果生成面向用户的最终回答。
     * 解决"模型最后一条文本只是过程汇报、未回答用户问题"的质量问题。
     * 合成失败时返回 null，由上层走兜底逻辑（extractFinalAnswer + 产物路径）。
     */
    private String synthesizeFinalAnswer(List<String> results) {
        try {
            // 用户原始问题 = 消息列表中的第一条 UserMessage（记忆是 SystemMessage，不干扰）
            String originalQuestion = messageList.stream()
                    .filter(m -> m instanceof UserMessage)
                    .map(Message::getText)
                    .findFirst()
                    .orElse("");
            if (StrUtil.isBlank(originalQuestion)) {
                return null;
            }
            // 执行轨迹：截断每条结果防 token 爆炸
            String executionTrace = results.stream()
                    .map(r -> r.length() > 800 ? r.substring(0, 800) + "...(截断)" : r)
                    .collect(java.util.stream.Collectors.joining("\n"));
            String answer = getChatClient()
                    .prompt()
                    .system("""
                            You are the final answer synthesizer for an AI agent.
                            Based on the user's original question and the agent's execution trace,
                            produce the complete final answer for the user.
                            Rules:
                            - Directly answer the user's original question; do NOT merely report task status
                              like "task finished" or "file verified".
                            - Include key findings, numbers, and results obtained during execution.
                            - Include the full paths of every file created, saved, or downloaded.
                            - Reply in the same language as the user's question.
                            - Be concise but complete.
                            """)
                    .user("用户原始问题:\n" + originalQuestion + "\n\n执行过程与结果:\n" + executionTrace)
                    .call()
                    .content();
            return StrUtil.blankToDefault(answer, null);
        } catch (Exception e) {
            log.warn("最终答案合成失败，使用兜底逻辑: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从消息列表中提取模型最后的文本回复作为最终答案
     */
    private String extractFinalAnswer() {
        for (int i = messageList.size() - 1; i >= 0; i--) {
            Message msg = messageList.get(i);
            if (msg instanceof AssistantMessage assistantMsg) {
                String text = assistantMsg.getText();
                if (StrUtil.isNotBlank(text) && text.length() > 5
                        && !text.startsWith("工具调用失败") && !text.startsWith("处理时遇到")) {
                    return text;
                }
            }
        }
        return null;
    }

    /**
     * 转义 JSON 字符串值。除反斜杠与引号外，U+0000–U+001F 的控制字符也必须处理：
     * 工具返回的文件内容/表格里一个制表符就能让手写 JSON 变成非法，
     * 前端 JSON.parse 抛异常后整个 process 事件被丢弃（页面表现为"这一步没渲染"）。
     * 常规控制字符用短转义，其余（如 0x01）走六字符的 unicode 转义。
     */
    private String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    protected void cleanup() {
        this.messageList.clear();
        this.currentStep = 0;
        if (this.state != AgentState.RUNNING) {
            this.state = AgentState.IDLE;
        }
    }

    /**
     * 解析会话 ID：客户端已传则原样使用；未传则服务端生成随机 UUID。
     * 服务端生成保证不可枚举、不可伪造（前端旧逻辑是时间戳+短随机数，可被猜到）。
     */
    protected String resolveConversationId() {
        if (StrUtil.isBlank(this.conversationId)) {
            this.conversationId = java.util.UUID.randomUUID().toString();
        }
        return this.conversationId;
    }

    protected void initializeMemoryLayers(String requestType) {
        if (memoryAssembler == null) {
            return;
        }
        MemoryContext memoryContext = memoryAssembler.assemble(getUserId(), resolveConversationId(), getName(), requestType);
        String memoryPrompt = memoryContext.toPromptSection();
        if (StrUtil.isNotBlank(memoryPrompt)) {
            messageList.add(new SystemMessage(memoryPrompt));
        }
    }

    protected void recordMemoryAfterRun(String userPrompt, String finalOutput) {
        if (memoryUpdateService == null) {
            return;
        }
        memoryUpdateService.recordAgentInteraction(
                getUserId(),
                resolveConversationId(),
                StrUtil.blankToDefault(getName(), "agent"),
                userPrompt,
                StrUtil.blankToDefault(finalOutput, "")
        );
    }

    /**
     * 记忆写入的收尾包装，遵循 fail-open：失败只记日志。
     * 记忆本身全程 fail-open（画像回落、摘要截断、向量写入失败只 warn），
     * 一次写盘异常不该污染任务结果，更不该把状态机卡在 RUNNING/ERROR 上。
     */
    private void recordMemorySafely(String userPrompt, String finalOutput) {
        try {
            recordMemoryAfterRun(userPrompt, finalOutput);
        } catch (Exception e) {
            log.warn("记录记忆失败，已忽略: {}", e.getMessage());
        }
    }
}
