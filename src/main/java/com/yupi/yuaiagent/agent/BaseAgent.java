package com.yupi.yuaiagent.agent;

import cn.hutool.core.util.StrUtil;
import com.yupi.yuaiagent.agent.model.AgentState;
import com.yupi.yuaiagent.memory.ConversationTurn;
import com.yupi.yuaiagent.memory.MemoryAssembler;
import com.yupi.yuaiagent.memory.MemoryUpdateService;
import com.yupi.yuaiagent.metrics.AgentRunMetrics;
import com.yupi.yuaiagent.stream.StreamDeltaSink;
import com.yupi.yuaiagent.stream.StreamDisconnectedException;
import com.yupi.yuaiagent.stream.StreamingChatAggregator;
import com.yupi.yuaiagent.stream.StreamingProperties;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
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
     * 本轮用户输入原文（2026-10-11 单用户单会话重构）。
     * <p>
     * 为什么必须有这个字段：恢复历史注入后，messageList 里会出现多轮历史的 UserMessage，
     * 「消息列表里第一条 UserMessage = 本轮任务」这个假设不再成立。所有需要"用户原始问题"
     * 的地方（synthesizeFinalAnswer / 子类校验与规划）一律读这里，不再扫 messageList。
     * 在 run()/runStream() 入口赋值。
     */
    private String currentUserPrompt;
    /**
     * 是否允许本 Agent 读写记忆（默认 true）——写（画像/摘要/归档/历史）与恢复（近场历史注入）
     * 由同一个开关控制。
     * <p>
     * 多智能体的子 Agent 会置为 false：① 写——父协调器已用「用户的原始话语」写过画像与摘要，
     * 子 Agent 拿到的是被包装过的子任务指令，照写会污染唯一那份工件（2026-10-11 单键化后
     * 不再有"子会话文件"可以隔离）；② 恢复——子任务不需要、也不该背上父会话的历史前缀。
     */
    private boolean memoryWriteEnabled = true;
    /**
     * 本 run 的度量快照（2026-10 上下文/记忆重构 · 阶段 1 埋点）。
     * <p>
     * volatile：worker 线程写（advisor 与 act() 都在该线程）、run 结束后由别的线程读
     * （日志/评测/测试）——不加锁，靠 volatile 保证可见性。
     * run 未开始或已结束时为 null，advisor 与各计数点都必须容忍 null。
     */
    private volatile AgentRunMetrics runMetrics;
    /**
     * 最近一次已结束 run 的度量快照（2026-10 上下文重构 · 阶段 4 A/B 评测用）。
     * <p>
     * {@link #runMetrics} 在 run 结束时会被置空（advisor 不该再往里写），但 A/B 评测需要在
     * {@code run()} 返回<b>之后</b>读数字，所以这里留一份引用。生产链路上 Agent 是每请求 new 的，
     * 这份引用随对象一起被回收，不累积、不构成泄漏。
     */
    private volatile AgentRunMetrics lastRunMetrics;
    private MemoryAssembler memoryAssembler;
    private MemoryUpdateService memoryUpdateService;
    /**
     * 流式配置（2026-10 流式轮）。
     * <p>
     * 沿构造器链显式传入（与 ToolVectorRouter / ToolRoutingProperties / ContextWindowManager
     * 同一条纪律），因此禁掉 Lombok 生成的 setter：只能从构造器进来。
     * 可为 null（评测/单测的历史调用点）——null 视为「不启用流式」，走阻塞路径。
     */
    @Setter(AccessLevel.NONE)
    private StreamingProperties streamingProperties;
    /**
     * 本 run 的 SSE 发射器（2026-10 流式轮：从 runStream 的局部变量提升为字段）。
     * <p>
     * 为什么必须提升：文本增量是从 think() 与最终答案合成的深层回调里发出去的，局部变量传不进去。
     * volatile：worker 线程写（runStream 入口）、Servlet 线程的超时/完成/错误回调读。
     * run() 阻塞链路下为 null（不发 SSE）。
     */
    @Setter(AccessLevel.NONE)
    private volatile SseEmitter sseEmitter;
    /**
     * 文本增量出口（2026-10 流式轮）。<b>只有 runStream 会安装它</b>——sink 非空 = 「有人在看」，
     * think()/最终答案合成才会走逐 token 流式；run()（评测、多智能体子 Agent）不安装，
     * 于是那些链路天然保持阻塞调用、零行为变化。
     * <p>
     * 不加 {@code @Setter(AccessLevel.NONE)}：单测要用 setter 装计数器版本（不起 HTTP 就能验证流式）。
     */
    private volatile StreamDeltaSink textDeltaSink;

    /**
     * 构造器：流式配置沿构造器链透传。
     * <p>
     * <b>注意</b>：原来 BaseAgent 没有显式构造器，Lombok {@code @Data} 会补一个无参构造器；
     * 一旦写了这个构造器，Lombok 就<b>不再生成无参构造器</b>（@Data 的既定行为），
     * 于是所有 {@code StubAgent extends BaseAgent} 的测试桩都必须显式写 {@code super(null)}。
     * 传 null = 不启用流式，等价于改造前的阻塞语义——测试桩正是靠这个钉住「单测不跑流式」。
     */
    protected BaseAgent(StreamingProperties streamingProperties) {
        this.streamingProperties = streamingProperties;
    }

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
        // 本轮任务原文：恢复历史注入后 messageList 里会有多轮历史 UserMessage，
        // 「用户原始问题」不能再去消息列表里扫（见 currentUserPrompt 字段注释）
        this.currentUserPrompt = userPrompt;
        messageList.clear();
        initializeMemoryLayers("agent_task");
        List<String> Results = new ArrayList<>();
        messageList.add(new UserMessage(userPrompt));
        // 埋点：状态进入 RUNNING 之后、第一次 LLM 调用之前创建——再晚就会漏掉首轮调用
        AgentRunMetrics metrics = startRunMetrics();
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
            // 埋点收尾放在最前面：后面的清理与记忆写入都可能抛异常，
            // 放在它们之后会导致异常路径上永远看不到这行指标（而异常路径恰恰最需要看）。
            finishRunMetrics(metrics, state.name());
            // 失败标记必须在 cleanup() 之前快照：cleanup 会把 ERROR 复位 IDLE，
            // 而 history.jsonl 的「失败轮不落历史」判定依赖它。
            boolean runFailed = state == AgentState.ERROR;
            // L5 归档要的是「给用户的最终答案」，不是步骤轨迹（轨迹归档进向量库，检索回来的
            // 全是过程汇报）。提取必须赶在 cleanup() 之前——它读的是 messageList，而 cleanup 会清空。
            // 失败路径沿用 finalOutput（异常时是"出错了：..."，中途置错时是轨迹），
            // 与改造前完全一致，不引入新风险。
            String memoryOutput = runFailed
                    ? finalOutput
                    : extractFinalAnswerForMemory(Results);
            // 顺序很重要：先清理运行态，再写记忆。记忆写入要调 LLM 且可能抛异常，
            // 放在前面一旦抛出去，cleanup() 就被跳过，状态机会永远停在 RUNNING/ERROR 上。
            this.cleanup();
            recordMemorySafely(userPrompt, memoryOutput, runFailed);
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
        // 同步镜像到字段（2026-10 流式轮）：文本增量要从 think()/合成器的回调里发出，深层的它们只够得着字段
        this.sseEmitter = sseEmitter;
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
            //    2026-10-11：payload 不再含 userId——没有用户体系，会话 ID 仅用于日志追踪/前端回执，
            //    不参与任何记忆寻址（记忆是单用户单会话的）。
            try {
                // conversationId 可能来自客户端传入的 chatId，含引号会直接破坏 JSON，必须转义
                String sessionJson = "{\"conversationId\":\"" + escapeJson(resolveConversationId()) + "\"}";
                sseEmitter.send(SseEmitter.event().name("session").data(sessionJson));
            } catch (Exception e) {
                sseEmitter.completeWithError(e);
                return;
            }
            // 3、执行，更改状态
            this.state = AgentState.RUNNING;
            this.currentUserPrompt = userPrompt;
            messageList.clear();
            initializeMemoryLayers("agent_task");
            // 记录消息上下文
            messageList.add(new UserMessage(userPrompt));
            // 保存结果列表
            List<String> results = new ArrayList<>();
            // 埋点：与 run() 一致，进入 RUNNING 后立刻创建，避免漏掉首轮 LLM 调用
            AgentRunMetrics metrics = startRunMetrics();
            // 安装文本增量出口（2026-10 流式轮）。只在这里安装：see BaseAgent#textDeltaSink
            if (isStreamingEnabled()) {
                this.textDeltaSink = (scope, text) -> sseEmitter.send(
                        SseEmitter.event().name("delta").data(buildDeltaEventJson(scope, currentStep, text)));
            }
            // L5 归档用的「给用户的最终答案」：成功路径在 try 内赋值（buildFinalSummary 的结果，
            // 含 LLM 合成，不额外多花调用）；失败/中断路径保持 null，由 finally 回退到「从消息列表提取」。
            String finalAnswerForMemory = null;
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
                    // 阻塞模式下这就是用户看到的第一帧内容（整步跑完才发）——首字指标在这里也要记，
                    // 流式/阻塞两条路径口径一致，A/B 才可比
                    markFirstVisible();
                    sseEmitter.send(SseEmitter.event().name("process").data(eventJson));
                }
                // 发送最终结果
                String summary = buildFinalSummary(results);
                // 先留档再发送：SSE 发送失败（连接已断）时也要保证归档的是给用户的答案
                finalAnswerForMemory = summary;
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
                // 清中断标志（2026-10 流式轮）：Future.cancel(true) 打断流式消费（blockLast）后，
                // 中断可能以「标志位」形态残留在线程上；本线程来自缓存线程池、会被复用，
                // 带着中断标志跑下一个任务会让它的首个阻塞调用直接失败。放最前面清，
                // 也保证后面的收尾/记忆写入不被残留标志干扰。
                Thread.interrupted();
                // 收尾顺序：先埋点、再停心跳、再清运行态、最后写记忆（写记忆会调 LLM，可能失败，
                // 但它失败绝不能拖累状态机——所以 cleanup 在前、且记忆写入自己吞异常）。
                // 埋点必须排第一个：cleanup() 会把 currentStep 归零，放在它后面就只剩 steps=0。
                finishRunMetrics(metrics, state.name());
                heartbeat.cancel(false);
                // 失败标记必须在 cleanup() 之前快照：cleanup 会把 ERROR 复位 IDLE，
                // 而 history.jsonl 的「失败轮不落历史」判定依赖它。
                boolean runFailed = state == AgentState.ERROR;
                // L5 归档：成功时归档「给用户的最终答案」；失败/中断（含客户端提前断开导致的 worker 取消）
                // 回退到消息列表提取。提取必须在 cleanup() 之前——它依赖 messageList。
                String memoryOutput = finalAnswerForMemory;
                if (StrUtil.isBlank(memoryOutput)) {
                    memoryOutput = extractFinalAnswerForMemory(results);
                }
                this.cleanup();
                recordMemorySafely(userPrompt, memoryOutput, runFailed);
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

    // --- 流式（2026-10 流式轮）---

    /**
     * 流式开关是否打开。properties 为 null（评测/单测的历史调用点不传它）视为关闭。
     */
    protected boolean isStreamingEnabled() {
        return streamingProperties != null && streamingProperties.isEnabled();
    }

    /**
     * 文本增量是否真的要流式：开关打开 <b>且</b> 有人在看（sink 只在 runStream 安装）。
     * <p>
     * run()（评测、多智能体子 Agent）的 sink 恒为 null → 自动走阻塞调用，行为与改造前一致——
     * 「流式是给人看的，不是给机器看的」这条边界就落在这一个判断上。
     */
    protected boolean isTextStreamingActive() {
        return isStreamingEnabled() && textDeltaSink != null;
    }

    /** 流式请求是否携带 {@code stream_options.include_usage}（见 StreamingProperties#includeUsage） */
    protected boolean isStreamUsageEnabled() {
        return streamingProperties != null && streamingProperties.isIncludeUsage();
    }

    /**
     * 发送一帧文本增量（SSE {@code delta} 事件），并记首字/帧数埋点。
     * <p>
     * <b>发送失败的语义（与「process 发送失败即停」对齐）</b>：流式把发送频率从「每步一次」放大成
     * 「每 token 一次」，异常如果放任其从聚合器的回调里抛出，会被 think() 的 catch 当成普通业务异常
     * 吞掉——状态虽然停了，循环却继续尝试后续步骤、继续烧 token。所以这里主动收手：
     * ① 清空增量出口（后续内部调用回落阻塞，不会再触发第二次失败）；② 状态置 ERROR（循环条件立刻失效）；
     * ③ 抛专用异常，由 think()/step() 原样上抛到 runStream 的收尾逻辑。断连最多损失当前一次 LLM 调用。
     */
    protected void emitTextDelta(String scope, String text) {
        if (StrUtil.isEmpty(text)) {
            return;
        }
        StreamDeltaSink sink = this.textDeltaSink;
        if (sink == null) {
            return;
        }
        try {
            sink.onTextDelta(scope, text);
        } catch (Exception e) {
            this.textDeltaSink = null;
            this.state = AgentState.ERROR;
            throw new StreamDisconnectedException("SSE 文本增量发送失败，客户端可能已断开", e);
        }
        // 埋点放在发送之后：firstVisibleMs / deltaFrames 的口径是「用户真的收到多少」，
        // 发送失败还计数的话，A/B 会拿一组虚高的帧数证明"流式生效了"
        AgentRunMetrics metrics = getRunMetrics();
        if (metrics != null) {
            metrics.recordTextDelta();
        }
        markFirstVisible();
    }

    /**
     * 拼 {@code delta} 事件的 JSON。包级可见：离线单测直接断言转义是否正确，不必起 HTTP。
     * 转义必须走 {@link #escapeJson}——模型文本里有引号/换行/制表符是常态，一个没转义就会
     * 让前端 JSON.parse 抛异常、整帧丢失。
     */
    String buildDeltaEventJson(String scope, int step, String text) {
        return "{\"type\":\"delta\",\"scope\":\"" + escapeJson(scope)
                + "\",\"step\":" + step
                + ",\"text\":\"" + escapeJson(text) + "\"}";
    }

    /** 记录「第一个内容帧已发出」的时刻（幂等；埋点为空说明本 run 没有埋点上下文，忽略即可） */
    private void markFirstVisible() {
        AgentRunMetrics metrics = getRunMetrics();
        if (metrics != null) {
            metrics.recordFirstVisible();
        }
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
        // 3. 兜底:取最后一个有实际内容的工具结果（排除 doTerminate/思考完成）
        return lastMeaningfulToolResult(results);
    }

    /**
     * 兜底链的最后一级：最后一个有实际内容的工具结果（去掉"工具 xxx 返回的结果:"前缀，
     * 截断到 1500 字符）；一个都没有时返回步骤计数。
     */
    private String lastMeaningfulToolResult(List<String> results) {
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
     * L5 归档专用的「给用户的最终答案」提取（2026-10 记忆重构 · 阶段 3）。
     * <p>
     * <b>为什么不直接用 run() 的返回值</b>：{@code run()} 返回的是<b>步骤轨迹拼接</b>
     * （"Step1: ... Step2: ..."），把它归档进向量库，下次检索回来的全是过程汇报，
     * 而不是"上次我问了什么、它答了什么"。改成归档「用户原话 + 最终答案」后，
     * 语义检索才命中得了对话内容。
     * <p>
     * <b>为什么不调 {@link #synthesizeFinalAnswer}</b>：那是一次真实 LLM 调用，而多智能体
     * 一次请求会有 3 个子 Agent 各走一遍 {@code run()}，等于每次 +3 次调用——超预算。
     * 这里只从已有数据里零成本地挑。
     * <p>
     * 优先级与 {@link #buildFinalSummary} 的兜底链一致（只是没有 LLM 合成那一级）：
     * 模型最后的文本回复 → 最后一个非 doTerminate 工具结果（截 1500 字符）→ 步骤计数。
     * 从"轨迹"变成"最终答案或最有内容的工具结果"，不会比改造前更差。
     * <p>
     * <b>必须在 cleanup() 之前调用</b>——{@code extractFinalAnswer()} 读的是 messageList。
     */
    private String extractFinalAnswerForMemory(List<String> results) {
        String answer = extractFinalAnswer();
        if (StrUtil.isNotBlank(answer)) {
            return answer;
        }
        return lastMeaningfulToolResult(results);
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
            // 用户原始问题 = 本轮 run 入口记录的 currentUserPrompt。
            // 不能再扫 messageList 的第一条 UserMessage——单会话恢复后，历史轮次的
            // UserMessage 排在真正任务之前，扫出来的会是「上一次会话的旧任务」。
            String originalQuestion = getCurrentUserPrompt();
            if (StrUtil.isBlank(originalQuestion)) {
                return null;
            }
            // 执行轨迹：截断每条结果防 token 爆炸
            String executionTrace = results.stream()
                    .map(r -> r.length() > 800 ? r.substring(0, 800) + "...(截断)" : r)
                    .collect(java.util.stream.Collectors.joining("\n"));
            ChatClient.ChatClientRequestSpec spec = getChatClient()
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
                    .user("用户原始问题:\n" + originalQuestion + "\n\n执行过程与结果:\n" + executionTrace);
            String answer;
            if (isTextStreamingActive()) {
                // 流式（2026-10 流式轮）：最终答案逐 token 推出（scope=final），用户不再等整段合成完。
                // 这里必须显式带上 streamUsage 的 options——本 spec 原本不传 options，
                // 而流式响应默认不带 usage，不请求就会让这一笔最大 prompt 的 token 统计整体丢失。
                ChatResponse response = StreamingChatAggregator.collectBlocking(
                        spec.options(streamUsageOptions()).stream().chatResponse(),
                        delta -> emitTextDelta("final", delta));
                answer = response == null || response.getResult() == null
                        ? null
                        : response.getResult().getOutput().getText();
            } else {
                // 阻塞路径（回滚目标；run() 链路也走这里）
                answer = spec.call().content();
            }
            return StrUtil.blankToDefault(answer, null);
        } catch (Exception e) {
            log.warn("最终答案合成失败，使用兜底逻辑: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 流式分支专用的 options：只声明 streamUsage，其余字段交给框架与 defaultOptions 合并补齐
     * （{@code ModelOptionsUtils.merge} 的语义是「请求级覆盖默认级」，不会丢掉 model/temperature 等配置）。
     * <p>
     * 不复用 ToolCallAgent 的 chatOptions：那是带工具定义与内部执行开关的实例，属于工具链路；
     * 合成器要的只是「流式下也把 usage 带回来」这一件事。
     */
    private ChatOptions streamUsageOptions() {
        OpenAiChatOptions options = new OpenAiChatOptions();
        options.setStreamUsage(isStreamUsageEnabled());
        return options;
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

    /**
     * 创建一个新的 run 度量对象并挂到 {@link #runMetrics}（2026-10 上下文/记忆重构 · 阶段 1 埋点）。
     * <p>
     * 必须在状态置为 RUNNING 之后、第一次 LLM 调用之前调用：{@code UsageMetricsAdvisor}
     * 是通过 {@code this::getRunMetrics} 拿对象的，创建晚了首轮 LLM 调用就白调了。
     */
    protected AgentRunMetrics startRunMetrics() {
        AgentRunMetrics metrics = new AgentRunMetrics(
                StrUtil.blankToDefault(getName(), "agent"),
                getConversationId(),
                metricsMode());
        this.runMetrics = metrics;
        return metrics;
    }

    /**
     * 收尾：写入结果与总耗时，输出一行结构化日志 {@code AGENT_METRICS {json}}，
     * 随后把 {@link #runMetrics} 置空（run 已结束，advisor 不该再写入）。
     * <p>
     * 全程 fail-open：埋点是观测手段，任何异常都只记日志，绝不影响任务结果。
     */
    protected void finishRunMetrics(AgentRunMetrics metrics, String outcome) {
        if (metrics == null) {
            return;
        }
        try {
            metrics.finish(outcome, this.currentStep);
            log.info(metrics.toLogLine());
        } catch (Exception e) {
            log.warn("Agent 指标落日志失败（不影响主链路）: {}", e.getMessage());
        } finally {
            this.runMetrics = null;
            // 留一份快照给 A/B 评测：它在 run() 返回之后才读得到数字
            this.lastRunMetrics = metrics;
        }
    }

    /**
     * 度量里的「模式」字段：子类覆写返回自己的运行模式（如工具暴露模式 full/dynamic），
     * 基类默认 n/a。A/B 对比与线上排查都靠这个字段区分两种配置。
     */
    protected String metricsMode() {
        return "n/a";
    }

    protected void cleanup() {
        this.messageList.clear();
        this.currentStep = 0;
        // 流式运行态随 run 一起清（2026-10 流式轮）：出口/发射器都是「本 run 的」，
        // 不清会泄漏到下一次 run（记忆写入等收尾调用也可能再碰它们）。
        this.textDeltaSink = null;
        this.sseEmitter = null;
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

    /**
     * 注入分层记忆（单用户单会话版，2026-10-11）。
     * <p>
     * 消息顺序（有硬约束，改动前先看 {@code MemoryHistoryProperties} 的算术注释）：
     * <pre>
     * [记忆 SystemMessage] → [近场历史 2N 条（User/Assistant 对）] → [本轮用户消息]
     * </pre>
     * 恢复<b>严格 2N 条、不加任何包装 Head</b>：默认 N=6 时首查消息数 = 12 + 1 + 1 + 1(nextStepPrompt)
     * = 15，恰好不触发 legacy 压缩（阈值严格 &gt; 15）——纯聊天与单步任务不因恢复付出压缩代价。
     * <p>
     * 子 Agent（memoryWriteEnabled=false）既不恢复也不写：子任务不该背父会话的历史前缀。
     * <p>
     * 整体包 try/catch：记忆是「锦上添花」能力，读记忆失败（坏文件、磁盘问题）
     * 绝不该让整轮任务 500 —— 降级为「本轮没有记忆上下文」继续跑。
     */
    protected void initializeMemoryLayers(String requestType) {
        if (memoryAssembler == null) {
            return;
        }
        try {
            // buildPromptSection：组装 + 渲染 + 常驻层容量上限（记忆块每轮都在，必须刻意做小）
            String memoryPrompt = memoryAssembler.buildPromptSection(getName(), requestType);
            if (StrUtil.isNotBlank(memoryPrompt)) {
                messageList.add(new SystemMessage(memoryPrompt));
            }
            // 近场恢复：把上一次会话的最近 N 轮原样放回上下文（对标 Claude Code --continue 的 transcript 恢复）
            if (isMemoryWriteEnabled()) {
                java.util.List<ConversationTurn> historyTurns = memoryAssembler.loadHistoryTurns();
                for (ConversationTurn turn : historyTurns) {
                    messageList.add(new UserMessage(turn.user()));
                    messageList.add(new AssistantMessage(turn.assistant()));
                }
                // 打一行 INFO：恢复是"看不见的行为"，出了问题（文件坏、轮数为 0、路径不对）时
                // 不复现就没法定位。一行日志换可观测性，与 AGENT_METRICS 同思路。
                if (!historyTurns.isEmpty()) {
                    log.info("近场恢复 {} 轮历史（对标 Claude Code --continue 的 transcript 恢复）", historyTurns.size());
                }
            }
        } catch (Exception e) {
            log.warn("分层记忆注入失败，本轮降级为无记忆上下文: {}", e.getMessage());
        }
    }

    protected void recordMemoryAfterRun(String userPrompt, String finalOutput, boolean runFailed) {
        if (memoryUpdateService == null) {
            return;
        }
        // 记忆写入总开关（2026-10-11 单键化）：子 Agent（memoryWriteEnabled=false）一律不写。
        // 单键化之后没有"子会话文件"可以隔离——多智能体子 Agent 手里是被包装过的子任务指令
        // （不是用户的话语），写进全局唯一的画像/摘要/归档就是污染唯一那份工件。
        // 恢复侧的同款开关在 initializeMemoryLayers()。
        if (!isMemoryWriteEnabled()) {
            return;
        }
        String agentName = StrUtil.blankToDefault(getName(), "agent");
        // 画像/摘要/归档：照旧（失败轮也写——行为与改造前一致；画像用原始用户话语抽取）
        memoryUpdateService.recordAgentInteraction(
                agentName,
                userPrompt,
                StrUtil.blankToDefault(finalOutput, "")
        );
        // 历史日志：失败轮跳过——把「出错了：...」回放进下一轮的上下文只会污染对话
        if (!runFailed) {
            memoryUpdateService.appendHistory(userPrompt, finalOutput, agentName);
        }
    }

    /**
     * 记忆写入的收尾包装，遵循 fail-open：失败只记日志。
     * 记忆本身全程 fail-open（画像回落、摘要截断、向量写入失败只 warn），
     * 一次写盘异常不该污染任务结果，更不该把状态机卡在 RUNNING/ERROR 上。
     *
     * @param runFailed 本 run 是否失败（必须在 cleanup() 之前快照，cleanup 会把 ERROR 复位 IDLE）
     */
    private void recordMemorySafely(String userPrompt, String finalOutput, boolean runFailed) {
        try {
            recordMemoryAfterRun(userPrompt, finalOutput, runFailed);
        } catch (Exception e) {
            log.warn("记录记忆失败，已忽略: {}", e.getMessage());
        }
    }
}
