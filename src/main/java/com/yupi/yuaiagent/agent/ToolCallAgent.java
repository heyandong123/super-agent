package com.yupi.yuaiagent.agent;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.yupi.yuaiagent.agent.model.AgentState;
import com.yupi.yuaiagent.context.ContextWindowManager;
import com.yupi.yuaiagent.memory.ConversationMemoryService;
import com.yupi.yuaiagent.metrics.AgentRunMetrics;
import com.yupi.yuaiagent.stream.StreamDisconnectedException;
import com.yupi.yuaiagent.stream.StreamingChatAggregator;
import com.yupi.yuaiagent.stream.StreamingProperties;
import com.yupi.yuaiagent.toolrouter.KeywordMatcher;
import com.yupi.yuaiagent.toolrouter.ToolRoutingProperties;
import com.yupi.yuaiagent.toolrouter.ToolVectorRouter;
import com.yupi.yuaiagent.tools.ListToolsTool;
import com.yupi.yuaiagent.tools.SearchMemoryTool;
import com.yupi.yuaiagent.tools.ToolSearchTool;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
/**
 * 处理工具调用的基础代理类(将 ReAct 模式真正落地)
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Slf4j
public class ToolCallAgent extends ReActAgent {

    /**
     * legacy 压缩阈值：消息数<b>严格大于</b>它才压缩（见 optimizeContextWindowIfNeeded）。
     * <p>
     * 2026-10-11 起包级可见（去掉 private）：{@code BaseAgentMemoryRestoreTest} 用它钉死
     * 「默认恢复 6 轮 + 记忆块 + 本轮用户消息 + nextStepPrompt = 15，恰好不触发压缩」的算术——
     * 阈值或恢复轮数任一方被改动，那条测试必须跟着红，不能让两者悄悄失配。
     */
    static final int MAX_CONTEXT_MESSAGES = 15;

    private static final int RECENT_MESSAGES_TO_KEEP = 6;

    private static final String SUMMARY_PREFIX = "[Context Summary]\n";

    /** 元工具名称集合（L1 常驻暴露，不进向量索引、不出现在工具目录里） */
    private static final Set<String> META_TOOL_NAMES = Set.of("searchTools", "listTools", "searchMemory");

    // ========== 工具调用容错机制:超时 + 重试 + 熔断 ==========
    /** 单次工具调用超时时间(秒) */
    private static final long TOOL_EXECUTION_TIMEOUT_SECONDS = 30;
    /** 工具调用失败后最大重试次数 */
    private static final int MAX_RETRY_COUNT = 2;
    /** 同一工具连续失败 N 次后触发熔断 */
    private static final int CIRCUIT_BREAKER_THRESHOLD = 3;
    /** 熔断冷却时间(毫秒),期间该工具不可用 */
    private static final long CIRCUIT_BREAKER_COOLDOWN_MS = 60_000;

    /** 记录每个工具的连续失败次数(Key: 工具名, Value: 连续失败次数) */
    private final Map<String, Integer> toolFailureCount = new ConcurrentHashMap<>();
    /** 记录被熔断工具的解禁时间戳(Key: 工具名, Value: 解禁时间的 System.currentTimeMillis) */
    private final Map<String, Long> toolDisabledUntil = new ConcurrentHashMap<>();

    // 定义该 Agent 能够使用的武器库(比如文件读写、联网搜索)
    private final ToolCallback[] availableTools;
    // 记忆切片:用于在 think() 和 act() 之间传递大模型的工具调用指令
    private ChatResponse toolCallChatResponse;
    // Spring AI 提供的官方工具执行管家
    private final ToolCallingManager toolCallingManager;
    // 专门用于覆写 Spring AI 默认行为的配置项
    private final OpenAiChatOptions chatOptions;
    // 语义向量工具路由器(可选,为 null 时回退到关键词路由)
    private ToolVectorRouter toolVectorRouter;

    /** 工具动态调用配置（L1 固定工具、searchTools 检索参数、listTools 分页大小） */
    private final ToolRoutingProperties toolRoutingProperties;

    /** L2 会话缓存：本 run 内通过 searchTools 发现的工具，后续轮次直接暴露 */
    private final Set<String> sessionDiscoveredTools = ConcurrentHashMap.newKeySet();

    /**
     * 本 run 内已出现过的「工具名 + 规范化参数」签名（阶段 1 埋点，用于重复工具调用率）。
     * 每 run 清空（见 {@link #cleanup()}）：跨 run 保留会把上一个任务的调用误判成本次重复。
     */
    private final Set<String> calledToolSignatures = ConcurrentHashMap.newKeySet();

    /** 工具搜索元工具实例（持有引用以便 cleanup 时读取调用统计） */
    private ToolSearchTool toolSearchTool;

    /** 对话记忆检索元工具实例（持有引用以便 cleanup 时读取调用统计） */
    private SearchMemoryTool searchMemoryTool;

    /** 对话记忆向量服务（可为 null：记忆未启用时 searchMemory 工具降级为提示） */
    private final ConversationMemoryService conversationMemoryService;

    /**
     * 上下文窗口管理器（2026-10 上下文重构 · 阶段 2；可为 null —— 评测/单测里不传即退回 legacy）。
     * 只有 {@code yuai.context.mode=budget} 时它才真正接管压缩，legacy 下旧路径一行不改。
     */
    private final ContextWindowManager contextWindowManager;

    /**
     * 本 run 的压缩状态（压缩次数与上次压缩步数，防 thrashing）。
     * 放在 Agent 实例上而不是 manager 上：manager 是单例 Bean，带上跨请求状态就违反了
     * 「每请求 new Agent、不给 Agent 加共享状态」的既有纪律。
     */
    private ContextWindowManager.RunState contextRunState = new ContextWindowManager.RunState();

    /**
     * 工具执行证据的环形缓冲（最近 {@value #MAX_EVIDENCE_LINES} 条，只存"工具名 + 结果前 100 字"）。
     * 与 {@code messageList} 解耦：压缩会把工具结果换成占位文案，证据一旦继续从消息列表倒扫，
     * 压缩之后就变成"证据是占位符"，doTerminate 的三方校验会误判任务未完成。
     */
    private final Deque<String> toolExecutionEvidence = new ArrayDeque<>();

    private static final int MAX_EVIDENCE_LINES = 8;

    // ========== RAG 知识库检索(可选) ==========
    /** 向量存储,为 null 时不启用 RAG */
    private VectorStore ragVectorStore;
    /** 最近一次 RAG 检索的轮次 */
    private int lastRagRetrievalRound = -1;
    /** 缓存的 RAG 检索结果 */
    private String cachedRagContext;
    /** RAG 检索的 Top-K */
    private static final int RAG_TOP_K = 3;
    /** 距上次检索超过 N 轮则强制重新检索 */
    private static final int RAG_RETRIEVAL_INTERVAL = 3;

    public ToolCallAgent(ToolCallback[] availableTools,
                         ToolVectorRouter toolVectorRouter,
                         ToolRoutingProperties toolRoutingProperties,
                         ConversationMemoryService conversationMemoryService,
                         ContextWindowManager contextWindowManager,
                         StreamingProperties streamingProperties) {
        // 流式配置沿构造器链上抛给 BaseAgent（2026-10 流式轮），本类只在 think() 里消费它
        super(streamingProperties);
        this.toolVectorRouter = toolVectorRouter;
        this.toolRoutingProperties = toolRoutingProperties;
        this.conversationMemoryService = conversationMemoryService;
        // 上下文管理器沿构造器链显式传递（与 ToolVectorRouter / ToolRoutingProperties 同一条纪律：
        // 不用 setter / @Autowired —— 那是历史 bug 的修复方向）
        this.contextWindowManager = contextWindowManager;
        // searchMemory：对话记忆检索。2026-10-11 单用户单会话后，检索不再需要任何键
        // （曾经的 conversationId/userId Supplier 随"会话隔离"语义一起删除）。
        // searchMemory 是记忆能力而非"工具发现"，两种暴露模式下都常驻。
        this.searchMemoryTool = new SearchMemoryTool(conversationMemoryService);
        ToolCallback[] metaTools;
        if (isFullExposureMode()) {
            // 全量加载模式（默认）：不注册 searchTools/listTools 这两个"工具发现"元工具，
            // 动态调用机制整体旁路（类的源码保留为教学素材，见 ToolRoutingProperties#exposureMode）。
            metaTools = ToolCallbacks.from(searchMemoryTool);
        } else {
            // 动态调用模式（教学/回退）：元工具每个 Agent 实例持有一份，
            // searchTools 直写本实例的 L2 会话缓存。
            // 先保存实例引用（ToolCallbacks.from 返回的是包装对象，拿不回原实例），再包装合并。
            this.toolSearchTool = new ToolSearchTool(
                    toolVectorRouter, availableTools, sessionDiscoveredTools, toolRoutingProperties);
            metaTools = ToolCallbacks.from(
                    toolSearchTool,
                    new ListToolsTool(availableTools, toolRoutingProperties.getListPageSize()),
                    searchMemoryTool);
        }
        // 原始工具 + 元工具合并为完整武器库（注意元工具不参与向量索引，见 ToolVectorRouter 只索引 allTools）
        this.availableTools = Stream.concat(Arrays.stream(availableTools), Arrays.stream(metaTools))
                .toArray(ToolCallback[]::new);
        // 初始化工具管家
        this.toolCallingManager = ToolCallingManager.builder().build();
        OpenAiChatOptions options = new OpenAiChatOptions();
        options.setInternalToolExecutionEnabled(false);
        options.setParallelToolCalls(false);
        options.setMaxTokens(1024);
        this.chatOptions = options;
    }

    /**
     * 设置语义向量工具路由器(可选)
     * 设置后 selectToolsForCurrentStep() 会优先使用语义路由
     */
    public void setToolVectorRouter(ToolVectorRouter toolVectorRouter) {
        this.toolVectorRouter = toolVectorRouter;
    }

    /**
     * 清理本 run 的会话状态：打印 searchTools 调用统计并清空 L2 会话缓存
     */
    @Override
    protected void cleanup() {
        if (toolSearchTool != null) {
            log.info("{} run 结束: searchTools 调用 {} 次, 命中 {} 个工具, L2 会话缓存已清理",
                    getName(), toolSearchTool.getCallCount(), toolSearchTool.getHitCount());
        }
        if (searchMemoryTool != null) {
            log.info("{} run 结束: searchMemory 调用 {} 次, 命中 {} 条历史记忆",
                    getName(), searchMemoryTool.getCallCount(), searchMemoryTool.getHitCount());
        }
        sessionDiscoveredTools.clear();
        // 重复工具调用签名是「本 run 内」的概念，必须随 run 一起清掉
        calledToolSignatures.clear();
        // 证据链与压缩状态同样是 run 级状态：留下一个 run 的压缩计数会让下一个 run 一开局就不许压缩
        toolExecutionEvidence.clear();
        this.contextRunState = new ContextWindowManager.RunState();
        super.cleanup();
    }

    // ========== 阶段 1 埋点：全部 fail-open（metrics 为 null 时直接返回） ==========

    /**
     * 度量里的模式字段：工具暴露模式（full / dynamic）。
     * A/B 对比与线上排查都靠它区分两种配置，缺失时回落 n/a。
     */
    @Override
    protected String metricsMode() {
        return toolRoutingProperties == null
                ? "n/a"
                : StrUtil.blankToDefault(toolRoutingProperties.getExposureMode(), "n/a");
    }

    /**
     * 记录模型本步"想调用"的工具，并识别重复调用。
     * <p>
     * 重复判定用「工具名 + 规范化参数」（trim + 折叠空白）：模型常把同一调用换个缩进/换行重写，
     * 不规范化会把格式差异误判成重复，重复率就失真了。
     */
    private void recordToolCallMetrics(List<AssistantMessage.ToolCall> toolCalls) {
        AgentRunMetrics metrics = getRunMetrics();
        if (metrics == null || toolCalls == null) {
            return;
        }
        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            metrics.recordToolCall(toolCall.name());
            String signature = toolCall.name() + "|"
                    + StrUtil.blankToDefault(toolCall.arguments(), "").trim().replaceAll("\\s+", " ");
            if (!calledToolSignatures.add(signature)) {
                metrics.recordDuplicateToolCall();
            }
        }
    }

    private void recordToolRetryMetric() {
        AgentRunMetrics metrics = getRunMetrics();
        if (metrics != null) {
            metrics.recordToolRetry();
        }
    }

    private void recordSoftErrorMetric() {
        AgentRunMetrics metrics = getRunMetrics();
        if (metrics != null) {
            metrics.recordSoftError();
        }
    }

    /**
     * 设置向量存储(可选),设置后 think() 会动态检索知识库
     */
    public void setRagVectorStore(VectorStore ragVectorStore) {
        this.ragVectorStore = ragVectorStore;
    }

    // ========== RAG 检索方法 ==========

    /**
     * 判断本轮是否需要触发 RAG 检索
     */
    private boolean shouldTriggerRagRetrieval() {
        if (ragVectorStore == null) {
            return false;
        }
        // 条件 1: 首次检索
        if (lastRagRetrievalRound < 0) {
            return true;
        }
        // 条件 2: 距上次检索超过 N 轮
        if (getCurrentStep() - lastRagRetrievalRound >= RAG_RETRIEVAL_INTERVAL) {
            return true;
        }
        // 条件 3: 上轮有工具执行,可能引入了新信息
        List<Message> messages = getMessageList();
        if (!messages.isEmpty()) {
            Message lastMessage = messages.get(messages.size() - 1);
            if (lastMessage instanceof ToolResponseMessage) {
                return true;
            }
        }
        return false;
    }

    /**
     * 执行 RAG 检索,返回格式化的文档内容
     */
    private String retrieveRagContext() {
        try {
            String query = buildRoutingContext();
            if (StrUtil.isBlank(query)) {
                return null;
            }
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(RAG_TOP_K)
                    .similarityThreshold(0.1)
                    .build();
            List<Document> docs = ragVectorStore.similaritySearch(request);
            if (docs.isEmpty()) {
                // 没搜到,缓存空结果避免重复检索
                lastRagRetrievalRound = getCurrentStep();
                return null;
            }
            String context = docs.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n\n"));
            cachedRagContext = context;
            lastRagRetrievalRound = getCurrentStep();
            log.info("{} RAG 检索完成,命中 {} 篇文档", getName(), docs.size());
            return context;
        } catch (Exception e) {
            log.warn("{} RAG 检索失败: {}", getName(), e.getMessage());
            return null;
        }
    }
    @Override
    public boolean think() {
        // 预算模式的两级压缩（阶段 2）。必须发生在追加 nextStepPrompt 之前：
        // 旧实现是"先入列再压缩"，一压缩就把刚加进去的下一步指令也摘要掉了。
        manageContextBudgetIfEnabled();
        // 防止提示词重复污染全局记忆
        if (StrUtil.isNotBlank(getNextStepPrompt())) {
            Message lastMessage = CollUtil.getLast(getMessageList());
            if (!(lastMessage instanceof UserMessage && getNextStepPrompt().equals(lastMessage.getText()))) {
                // RAG 检索:每轮 think() 根据最新上下文动态检索知识库
                String ragContext = null;
                if (shouldTriggerRagRetrieval()) {
                    ragContext = retrieveRagContext();
                }
                if (StrUtil.isNotBlank(ragContext)) {
                    String augmentedPrompt = "参考资料:\n" + ragContext + "\n---\n" + getNextStepPrompt();
                    getMessageList().add(new UserMessage(augmentedPrompt));
                } else {
                    getMessageList().add(new UserMessage(getNextStepPrompt()));
                }
            }
        }
        if (!isBudgetContextMode()) {
            // legacy（默认）：按消息条数压缩，保留为回滚目标与教学对照，一行不改
            optimizeContextWindowIfNeeded();
        }
        ToolCallback[] routedTools = selectToolsForCurrentStep();
        this.chatOptions.setToolCallbacks(Arrays.asList(routedTools));
        try {
            // [修复 1]: 统一使用流式构建器,确保 chatOptions 的控制权绝对不被覆写
            ChatResponse chatResponse;
            if (isTextStreamingActive()) {
                // 流式分支（2026-10 流式轮）：逐 token 消费，把增量推给客户端（scope=think）；
                // 聚合结果与阻塞路径形状一致（含完整 toolCalls），下游 limitRedundantToolCalls / act() 一行不用改。
                // streamUsage 只在这里开：Spring AI 会在非流式请求上剥掉 streamOptions 并打 WARN，
                // 而 chatOptions 是实例级对象（run() 链路/子 Agent 会复用同一实例），不能常开。
                this.chatOptions.setStreamUsage(isStreamUsageEnabled());
                chatResponse = StreamingChatAggregator.collectBlocking(
                        getChatClient()
                                .prompt() // 留空,不要传对象进去混合
                                //历史对话消息
                                .messages(getMessageList())
                                .system(getSystemPrompt())
                                .options(this.chatOptions) // 安全注入本轮工具与禁用自动执行的 Option
                                .stream()
                                .chatResponse(),
                        delta -> emitTextDelta("think", delta));
            } else {
                // 阻塞路径：yuai.stream.enabled=false 时的回滚目标；run()(评测/子 Agent) 也走这里
                chatResponse = getChatClient()
                        .prompt() // 留空,不要传对象进去混合
                        //历史对话消息
                        .messages(getMessageList())
                        .system(getSystemPrompt())
                        .options(this.chatOptions) // 安全注入本轮工具与禁用自动执行的 Option
                        .call()
                        .chatResponse();
            }
            chatResponse = limitRedundantToolCalls(chatResponse);
            // 将大模型的回复存入全局变量,留给稍后的 act() 方法使用
            this.toolCallChatResponse = chatResponse;
            // 从返回体中剥离出助手消息
            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
            // [修复 2]: 使用自带的 hasToolCalls() 规避空指针异常
            boolean hasTools = assistantMessage.hasToolCalls();
            List<AssistantMessage.ToolCall> toolCallList = assistantMessage.getToolCalls();
            int toolCount = hasTools ? toolCallList.size() : 0;
            String result = assistantMessage.getText();
            log.info(getName() + "的思考:" + result);
            log.info(getName() + "选择了 " + toolCount + " 个工具来使用");
            // 核心分支判断
            if (hasTools) {
                // 格式化打印大模型想调用的具体工具名称和参数
                String toolCallInfo = toolCallList.stream()
                        .map(toolCall -> String.format("工具名称:%s,参数:%s", toolCall.name(), toolCall.arguments()))
                        .collect(Collectors.joining("\n"));
                log.info(toolCallInfo);
                // 阶段 1 埋点：记录模型"想调用"的工具（不是实际执行的，执行在 act()）。
                // 微压缩的已知副作用就是模型会重新调用刚被清掉的工具，重复率是它的量化口径。
                recordToolCallMetrics(toolCallList);
                // 需要调用工具时,无需记录助手消息,返回 true 进入 act 阶段
                return true;
            } else {
                // 如果没有工具被调用,说明思考完成给出文本,记录助手消息,返回 false
                getMessageList().add(assistantMessage);
                return false;
            }
        } catch (StreamDisconnectedException e) {
            // 客户端已断（SSE 增量发送失败，2026-10 流式轮）：原样上抛给 runStream 的收尾逻辑。
            // 不能像普通异常那样吞掉——状态已被置 ERROR 但异常被吞的话，循环会继续尝试后续步骤继续烧 token
            throw e;
        } catch (Exception e) {
            // 打印完整的异常堆栈,方便排错
            log.error(getName() + "的思考过程遇到了问题:" + e.getMessage(), e);
            getMessageList().add(new AssistantMessage("处理时遇到了错误:" + e.getMessage()));
            return false;
        }
    }

    @Override
    public String act() {
        if (!toolCallChatResponse.hasToolCalls()) {
            recordStepTools("act");
            return "没有工具需要调用";
        }
        List<String> toolNames = toolCallChatResponse.getResult().getOutput()
                .getToolCalls().stream()
                .map(tc -> tc.name())
                .collect(Collectors.toList());

        boolean allRetriesExhausted = false;
        for (int retry = 0; retry <= MAX_RETRY_COUNT; retry++) {
            if (retry > 0) {
                log.warn("{} 工具调用失败,第 {} 次重试,工具:{}", getName(), retry, toolNames);
                // 阶段 1 埋点：retry>0 即一次重试尝试（软错误 / 超时 / 异常三条路径都汇到这里）
                recordToolRetryMetric();
            }
            try {
                // 确保 chatOptions 包含本轮所有需要的工具(路由可能误过滤)
                ensureToolCallbacksContain(toolNames);
                Prompt prompt = new Prompt(getMessageList(), this.chatOptions);
                ToolExecutionResult toolExecutionResult = CompletableFuture
                        .supplyAsync(() -> toolCallingManager.executeToolCalls(prompt, toolCallChatResponse))
                        .get(TOOL_EXECUTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                toolNames.forEach(name -> toolFailureCount.remove(name));

                setMessageList(toolExecutionResult.conversationHistory());
                ToolResponseMessage toolResponseMessage = (ToolResponseMessage) CollUtil
                        .getLast(toolExecutionResult.conversationHistory());
                // 结构化记录本步实际执行的工具名：直接取 ToolResponse 的 name，
                // 不再让 SSE 层回头解析 "工具 X 返回的结果:" 这种拼接文案
                recordStepTools(toolResponseMessage.getResponses().stream()
                        .map(ToolResponseMessage.ToolResponse::name)
                        .toArray(String[]::new));
                // 证据要在"结果还是原文"的此刻入缓冲：晚一步就可能被压缩换成占位文案
                recordToolExecutionEvidence(toolResponseMessage);

                boolean hasSoftError = toolResponseMessage.getResponses().stream()
                        .anyMatch(r -> String.valueOf(r.responseData()).startsWith("Error"));
                if (hasSoftError) {
                    // 阶段 1 埋点：软错误 = 工具没抛异常但明确返回了失败（"Error..." 开头）
                    recordSoftErrorMetric();
                }
                if (hasSoftError && retry < MAX_RETRY_COUNT) {
                    continue;
                }
                if (hasSoftError) {
                    allRetriesExhausted = true;
                    break;
                }

                boolean terminateToolCalled = toolResponseMessage
                        .getResponses().stream()
                        .anyMatch(response -> response.name().equals("doTerminate"));
                if (terminateToolCalled) {
                    // 终止前任务清单校验：模型声明完成 ≠ 真的完成，
                    // 拿清单与原始任务对比，有遗漏则拒绝终止并反馈模型继续
                    String checklist = toolResponseMessage.getResponses().stream()
                            .filter(response -> response.name().equals("doTerminate"))
                            .map(response -> String.valueOf(response.responseData()))
                            .findFirst()
                            .orElse("");
                    String missing = verifyTaskCompletion(checklist);
                    if (StrUtil.isNotBlank(missing)) {
                        getMessageList().add(new AssistantMessage(
                                "任务完成校验未通过：" + missing + "，请继续完成后再调用 doTerminate。"));
                        log.info("{} doTerminate 清单校验未通过: {}", getName(), missing);
                    } else {
                        setState(AgentState.FINISHED);
                    }
                } else if (isSearchAgentToolFailure(toolResponseMessage)) {
                    setState(AgentState.FINISHED);
                }
                String results = toolResponseMessage.getResponses().stream()
                        .map(response -> "工具 " + response.name() + " 返回的结果:" + response.responseData())
                        .collect(Collectors.joining("\n"));
                log.info(results);
                return results;

            } catch (TimeoutException e) {
                log.error("{} 工具调用超时({}s),工具:{}", getName(), TOOL_EXECUTION_TIMEOUT_SECONDS, toolNames);
                if (retry < MAX_RETRY_COUNT) {
                    getMessageList().add(new AssistantMessage(
                            "工具调用超时(>" + TOOL_EXECUTION_TIMEOUT_SECONDS + "s),请尝试简化任务或换一种方式"));
                } else {
                    allRetriesExhausted = true;
                    break;
                }
            } catch (Exception e) {
                log.error("{} 工具调用异常:{},工具:{}", getName(), e.getMessage(), toolNames);
                if (retry < MAX_RETRY_COUNT) {
                    getMessageList().add(new AssistantMessage(
                            "工具调用失败:" + e.getMessage() + ",请尝试其他方式"));
                } else {
                    allRetriesExhausted = true;
                    break;
                }
            }
        }
        if (allRetriesExhausted) {
            toolNames.forEach(this::recordToolFailure);
            // 执行没能落地：记录被尝试过的工具名，让执行过程面板能看到是哪个工具挂了
            recordStepTools(toolNames.toArray(new String[0]));
        }
        return "工具调用失败,已重试 " + MAX_RETRY_COUNT + " 次";
    }

    /**
     * 终止前任务完成校验：原始任务 + 模型声明清单 + 工具执行证据三方比对。
     * 证据校验设计：清单是模型自述（可能撒谎），工具执行记录是事实——
     * 两者冲突时相信证据，防止"清单声称完成但实际没做"的漏报。
     *
     * @param checklist 模型在 doTerminate 中声明的完成清单
     * @return 有遗漏时返回 "MISSING: ..." 说明文本；校验通过返回 null
     */
    private String verifyTaskCompletion(String checklist) {
        if (StrUtil.isBlank(checklist)) {
            return "未提供任务完成清单";
        }
        try {
            // 原始任务读 BaseAgent.currentUserPrompt，不再扫消息列表第一条 UserMessage——
            // 单会话恢复注入后，历史轮次的 UserMessage 排在真正任务之前，扫出来会是旧任务
            String originalTask = StrUtil.blankToDefault(getCurrentUserPrompt(), "");
            String evidence = buildToolExecutionEvidence();
            ChatResponse verifyResponse = getChatClient()
                    .prompt()
                    .system("""
                            You are a task completion verifier.
                            You receive:
                            1. the user's original task,
                            2. the agent's claimed completion checklist,
                            3. actual tool execution records (truncated evidence).
                            Compare the task against BOTH the checklist and the execution evidence.
                            The checklist may be inaccurate — trust the execution evidence when they conflict.
                            Reply ONLY with "COMPLETE" if every requirement of the task is supported
                            by the evidence, or "MISSING: <the missing requirement>" otherwise.
                            """)
                    .user("原始任务:\n" + originalTask
                            + "\n\n完成清单:\n" + checklist
                            + "\n\n工具执行记录:\n" + evidence)
                    .call()
                    .chatResponse();
            String verdict = verifyResponse.getResult().getOutput().getText();
            if (StrUtil.isNotBlank(verdict) && verdict.trim().toUpperCase(Locale.ROOT).startsWith("MISSING")) {
                return verdict.trim();
            }
            return null;
        } catch (Exception e) {
            // 校验调用失败时放行终止（fail-open），由 maxSteps 兜底防死循环
            log.warn("{} 任务完成校验调用失败，放行终止: {}", getName(), e.getMessage());
            return null;
        }
    }

    /**
     * 构建工具执行证据摘要：直接读环形缓冲（最近 {@value #MAX_EVIDENCE_LINES} 条）。
     * <p>
     * 旧实现是从 {@code getMessageList()} 倒扫 ToolResponseMessage 拼的——上下文压缩会把工具结果
     * 替换成占位文案，于是"证据"退化成占位符，三方校验会误判任务没完成。
     * 改成读缓冲后，证据与压缩彻底解耦（顺带：微压缩也就不会再有"删了证据"的副作用）。
     */
    String buildToolExecutionEvidence() {
        if (toolExecutionEvidence.isEmpty()) {
            return "（无工具执行记录）";
        }
        return String.join("\n", toolExecutionEvidence);
    }

    private boolean isSearchAgentToolFailure(ToolResponseMessage toolResponseMessage) {
        if (!"searchAgent".equals(getName())) {
            return false;
        }
        List<String> results = toolResponseMessage.getResponses().stream()
                .map(response -> String.valueOf(response.responseData()).toLowerCase(Locale.ROOT))
                .collect(Collectors.toList());
        boolean rateLimited = results.stream()
                .anyMatch(result -> KeywordMatcher.containsSubstring(
                        result,
                        "http status: 429",
                        "cuqps_has_exceeded_the_limit",
                        "qps",
                        "rate limit",
                        "too many requests"
                ));
        if (rateLimited) {
            return true;
        }
        return results.stream()
                .allMatch(result -> KeywordMatcher.containsSubstring(
                        result,
                        "error",
                        "failed",
                        "http status: 429",
                        "no search results",
                        "no toolcallback"
                ));
    }

    private ChatResponse limitRedundantToolCalls(ChatResponse chatResponse) {
        if (!"searchAgent".equals(getName()) || !chatResponse.hasToolCalls()) {
            return chatResponse;
        }
        AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
        List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();
        List<AssistantMessage.ToolCall> limitedToolCalls = new ArrayList<>();
        boolean aroundSearchSelected = false;
        boolean webSearchSelected = false;
        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            String toolName = toolCall.name();
            if (isAroundSearchToolName(toolName)) {
                if (aroundSearchSelected) {
                    continue;
                }
                aroundSearchSelected = true;
            }
            if ("searchWeb".equals(toolName)) {
                if (webSearchSelected) {
                    continue;
                }
                webSearchSelected = true;
            }
            limitedToolCalls.add(toolCall);
        }
        if (limitedToolCalls.size() == toolCalls.size()) {
            return chatResponse;
        }
        AssistantMessage limitedAssistantMessage = new AssistantMessage(
                assistantMessage.getText(),
                assistantMessage.getMetadata(),
                limitedToolCalls,
                assistantMessage.getMedia()
        );
        log.info("{} 裁剪重复工具调用:{} -> {}", getName(), toolCalls.size(), limitedToolCalls.size());
        return new ChatResponse(
                List.of(new Generation(limitedAssistantMessage, chatResponse.getResult().getMetadata())),
                chatResponse.getMetadata()
        );
    }

    // ========== 上下文预算模式（2026-10 上下文重构 · 阶段 2） ==========

    /** 是否由 {@link ContextWindowManager} 接管上下文管理（默认 false，走 legacy） */
    private boolean isBudgetContextMode() {
        return contextWindowManager != null && contextWindowManager.isBudgetMode();
    }

    /**
     * 按 token 预算管理上下文：一级微压缩（清旧工具结果，零 LLM 成本）→ 二级整体压缩（LLM 摘要，先落盘）。
     * 全部 fail-open：上下文管理失败最多是"这轮没压"，绝不能让整轮任务挂掉。
     */
    private void manageContextBudgetIfEnabled() {
        if (!isBudgetContextMode()) {
            return;
        }
        try {
            ContextWindowManager.Result result = contextWindowManager.manage(getMessageList(),
                    new ContextWindowManager.ManageRequest(
                            this::summarizeHistory,
                            getName(),
                            getCurrentUserPrompt(),
                            getConversationId(),
                            getCurrentStep(),
                            contextRunState,
                            getRunMetrics()));
            if (result.microcompacted() || result.compacted()) {
                setMessageList(result.messages());
            }
        } catch (Exception e) {
            log.warn("{} 上下文预算管理失败，本轮按原样继续: {}", getName(), e.getMessage());
        }
    }

    /**
     * 摘要器实现：与主对话<b>同一个系统提示</b>（对标 Claude Code 的"同系统提示 + 同历史 + 末尾指令"），
     * 而不是旧实现那套另写的压缩提示——换系统提示等于换人格，摘要出来的东西和主对话关心的不是一回事。
     * 渲染由 manager 负责（按消息类型分支、取 ToolResponseMessage 的 responses），这里只管调模型。
     */
    private String summarizeHistory(String historyText) {
        ChatResponse response = getChatClient()
                .prompt()
                .system(StrUtil.blankToDefault(getSystemPrompt(), ""))
                .user(historyText + """

                        ---
                        Compress the conversation history above into a compact working memory that you will
                        keep using to finish the task. Preserve, in this order:
                        1) the user's original goal and every explicit constraint;
                        2) completed tool actions with their key results (keep file paths verbatim);
                        3) unfinished sub-tasks;
                        4) important numbers, names and conclusions.
                        Drop small talk. Do not restate these instructions. Reply with the memory only.
                        """)
                .call()
                .chatResponse();
        return response.getResult().getOutput().getText();
    }

    /**
     * 记录工具执行证据（环形缓冲，最近 {@value #MAX_EVIDENCE_LINES} 条）。
     * 在 act() 里、工具刚执行完时写入——那时结果还是原文，不受任何后续压缩影响。
     */
    // 包级可见：离线单测直接喂消息进缓冲、断言证据不受压缩影响（见 ToolCallAgentEvidenceTest）
    void recordToolExecutionEvidence(ToolResponseMessage toolResponseMessage) {
        for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
            if ("doTerminate".equals(response.name())) {
                continue;
            }
            String compact = String.valueOf(response.responseData()).replace("\n", " ").trim();
            String truncated = compact.length() > 100 ? compact.substring(0, 100) + "..." : compact;
            toolExecutionEvidence.addLast(
                    "- " + response.name() + ": " + (truncated.isEmpty() ? "（无输出）" : truncated));
            while (toolExecutionEvidence.size() > MAX_EVIDENCE_LINES) {
                toolExecutionEvidence.removeFirst();
            }
        }
    }

    private void optimizeContextWindowIfNeeded() {
        List<Message> currentMessages = getMessageList();
        if (currentMessages.size() <= MAX_CONTEXT_MESSAGES) {
            return;
        }
        int splitIndex = Math.max(1, currentMessages.size() - RECENT_MESSAGES_TO_KEEP);
        List<Message> historyToSummarize = new ArrayList<>(currentMessages.subList(0, splitIndex));
        List<Message> recentMessages = new ArrayList<>(currentMessages.subList(splitIndex, currentMessages.size()));
        String summary = summarizeMessages(historyToSummarize);
        List<Message> optimizedMessages = new ArrayList<>();
        optimizedMessages.add(new SystemMessage(SUMMARY_PREFIX + summary));
        optimizedMessages.addAll(recentMessages);
        setMessageList(optimizedMessages);
        // 埋点：legacy 路径也记账（2026-10 上下文重构 · 阶段 4）。不记账的话
        // 「budget 模式压缩次数更少」就成了无法证伪的说法——A/B 要把两种模式放在同一张表里比。
        // fallback 恒传 false：本方法没有"摘要失败"分支（summarizeMessages 内部已 try/catch 回落成截断文本）。
        AgentRunMetrics legacyMetrics = getRunMetrics();
        if (legacyMetrics != null) {
            legacyMetrics.recordCompaction(false);
        }
        log.info("{} 上下文压缩完成,原消息数:{},压缩后消息数:{}", getName(), currentMessages.size(), optimizedMessages.size());
    }

    private String summarizeMessages(List<Message> messages) {
        // 渲染与 budget 路径共用同一实现（2026-10 重构轮补修 P3）。旧实现是
        // `message.getMessageType() + ": " + message.getText()`，而 ToolResponseMessage 的 text 是
        // 空串常量（spring-ai 字节码实锤）——摘要器眼里的工具输出**全是空的**，既丢信息又白花一次 LLM。
        // 这个 bug 两条路径共有，所以修复也必须两条路径共有：只修 budget 等于把已知缺陷留在默认路径上，
        // 而且会让 A/B 里的差异不止"触发策略"一个变量（budget 会因渲染更好而占便宜）。
        String historyText = contextWindowManager != null
                ? contextWindowManager.renderForSummary(messages)
                : messages.stream()
                        .map(message -> message.getMessageType() + ": " + message.getText())
                        .collect(Collectors.joining("\n"));
        try {
            ChatResponse summaryResponse = getChatClient()
                    .prompt()
                    .system("""
                            You are a context compression assistant.
                            Summarize the conversation history into a compact working memory for an agent.
                            Preserve:
                            1. the user's goal,
                            2. completed tool actions and key outputs,
                            3. unresolved sub-tasks,
                            4. constraints or preferences.
                            Keep it concise and structured.
                            """)
                    .user(historyText)
                    .call()
                    .chatResponse();
            String summary = summaryResponse.getResult().getOutput().getText();
            if (StrUtil.isNotBlank(summary)) {
                return summary;
            }
        } catch (Exception e) {
            log.warn("{} 生成上下文摘要失败,改用截断摘要:{}", getName(), e.getMessage());
        }
        // 失败回落保留**最近**的内容（P5）：旧实现截最老的 1200 字符，恰好丢掉模型最需要的"我刚才在干什么"
        int fallbackLength = Math.min(historyText.length(), 1200);
        return historyText.substring(historyText.length() - fallbackLength);
    }

    /**
     * 是否处于"全量加载"暴露模式（默认）。
     * full：每轮暴露全部工具，不做关键词/向量路由（动态调用机制整体旁路）；
     * dynamic：旧的四层动态调用机制（元工具 + 路由 + L2 缓存），保留为教学/回退能力。
     */
    private boolean isFullExposureMode() {
        return toolRoutingProperties == null
                || !"dynamic".equalsIgnoreCase(toolRoutingProperties.getExposureMode());
    }

    /**
     * 选择本轮暴露给模型的工具集合。
     * <p>
     * 默认走"全量加载"模式：每轮暴露同一套工具，不做关键词/向量路由——
     * 与 Claude Code / OpenHands 的固定工具面一致，也符合 Manus 的
     * "Mask, don't remove"（任务中途不增删工具，保持请求前缀稳定、缓存友好）。
     * 动态调用机制仅在 exposure-mode=dynamic 时启用（教学/回退）。
     */
    private ToolCallback[] selectToolsForCurrentStep() {
        ToolCallback[] exposedTools = isFullExposureMode() ? availableTools : routeToolsForCurrentStep();
        // ===== 熔断过滤（两种模式共用）:排除已被熔断的工具 =====
        ToolCallback[] routedTools = Arrays.stream(exposedTools)
                .filter(tool -> !isCircuitBroken(tool.getToolDefinition().name()))
                .toArray(ToolCallback[]::new);
        // 如果所有工具都被熔断,放行 doTerminate 让模型能优雅退出
        if (routedTools.length == 0) {
            routedTools = Arrays.stream(availableTools)
                    .filter(tool -> "doTerminate".equals(tool.getToolDefinition().name()))
                    .toArray(ToolCallback[]::new);
            log.warn("{} 所有工具已被熔断,仅保留 doTerminate", getName());
        }
        String toolNames = Arrays.stream(routedTools)
                .map(tool -> tool.getToolDefinition().name())
                .collect(Collectors.joining(", "));
        log.info("{} 本轮暴露工具(熔断过滤后):{}", getName(), toolNames);
        if (!isFullExposureMode()) {
            // 路由选择性观测：暴露数/总数，用于监控路由是否退化回"接近全量暴露"
            int totalTools = availableTools.length;
            log.info("{} 本轮工具路由选择性: {}/{} ({}%)", getName(), routedTools.length, totalTools,
                    totalTools > 0 ? routedTools.length * 100 / totalTools : 0);
        }
        return routedTools;
    }

    /**
     * 动态调用机制的工具选择：暴露集合 = L1 元工具 ∪ 可配置固定工具 ∪ L2 会话发现工具 ∪ 本轮路由结果。
     * 默认（full 模式）不走到这里，仅 exposure-mode=dynamic 时启用，源码保留为教学素材。
     */
    private ToolCallback[] routeToolsForCurrentStep() {
        String routingContext = buildRoutingContext();
        Set<String> selectedToolNames = resolveSelectedToolNames(routingContext);
        selectedToolNames.addAll(META_TOOL_NAMES);
        selectedToolNames.addAll(toolRoutingProperties.getFixedTools());
        selectedToolNames.addAll(sessionDiscoveredTools);
        ToolCallback[] routedTools = Arrays.stream(availableTools)
                .filter(tool -> selectedToolNames.contains(tool.getToolDefinition().name()))
                .toArray(ToolCallback[]::new);
        // 安全网:向量库中可能存在已过时的工具引用,导致 selectedToolNames
        // 与 availableTools 完全不匹配。此时回退到全量工具以保证可用性。
        // 注意:不能以"只有 doTerminate"作为回退条件——用户可能是纯聊天场景,
        // 语义路由正确判断无需工具,此时只暴露 doTerminate 是正确的。
        if (routedTools.length == 0) {
            log.info("{} 路由结果与可用工具完全不匹配,回退到全量工具", getName());
            routedTools = availableTools;
        }
        return routedTools;
    }

    private Set<String> resolveSelectedToolNames(String routingContext) {
        Set<String> keywordTools = routeToolNames(routingContext);
        if (toolVectorRouter != null) {
            try {
                Set<String> vectorTools = toolVectorRouter.routeTools(routingContext);
                vectorTools.addAll(keywordTools); // 合并关键词路由,避免 MCP 等新工具被遗漏
                return vectorTools;
            } catch (Exception e) {
                log.warn("{} 语义向量路由异常,回退到关键词路由:{}", getName(), e.getMessage());
            }
        }
        return keywordTools;
    }

    private String buildRoutingContext() {
        List<Message> currentMessages = getMessageList();
        // 上下文清洗：排除工具返回结果——大段外部文本（搜索结果、文件内容等）
        // 是关键词路由污染的主源，保留用户消息与模型文本以维持意图信号
        List<Message> cleanedMessages = currentMessages.stream()
                .filter(message -> !(message instanceof ToolResponseMessage))
                .toList();
        int startIndex = Math.max(0, cleanedMessages.size() - 4);
        return cleanedMessages.stream()
                .skip(startIndex)
                .map(Message::getText)
                .collect(Collectors.joining("\n"))
                .toLowerCase(Locale.ROOT);
    }

    private Set<String> routeToolNames(String context) {
        Set<String> toolNames = Stream.of("doTerminate").collect(Collectors.toSet());
        // 路由上下文用词边界匹配：否则 "file" 会命中记忆注入的 "[User Profile]"，
        // readFile/writeFile 每轮恒定暴露，"ls" 也会命中 tools/results 把终端工具带出来
        boolean needsMap = KeywordMatcher.containsAnyWord(
                context,
                "地图",
                "高德",
                "地址",
                "位置",
                "地点",
                "附近",
                "周边",
                "公里",
                "距离",
                "路线",
                "导航",
                "poi",
                "map",
                "amap",
                "nearby",
                "around",
                "radius",
                "location",
                "coordinate",
                "geocode",
                "place",
                "restaurant",
                "cafe",
                "cinema",
                "park"
        );
        boolean hasMapTools = hasMapTools();
        if (needsMap && hasMapTools) {
            addMapToolNames(toolNames);
        } else if (KeywordMatcher.containsAnyWord(context, "search", "搜索", "查找", "查询", "新闻", "天气", "地点", "附近")) {
            toolNames.add("searchWeb");
        }
        if (KeywordMatcher.containsAnyWord(context, "图片", "照片", "图像", "image", "photo", "picture", "搜图")) {
            toolNames.add("searchWeb");
            toolNames.add("searchImage");
        }
        if (KeywordMatcher.containsAnyWord(context, "http://", "https://", "网址", "网页", "页面", "链接", "抓取", "scrape")) {
            toolNames.add("scrapeWebPage");
        }
        if (KeywordMatcher.containsAnyWord(context, "download", "下载", "资源", "图片")) {
            toolNames.add("downloadResource");
        }
        // 文件读取类（只读意图）
        if (KeywordMatcher.containsAnyWord(context, "读取", "查看", "看看")) {
            toolNames.add("readFile");
        }
        // 文件写入类（只写意图）
        if (KeywordMatcher.containsAnyWord(context, "保存", "写入", "写")) {
            toolNames.add("writeFile");
        }
        // 通用文件词（读写均可能，保守起见都暴露）
        if (KeywordMatcher.containsAnyWord(context, "file", "文件", "txt", "markdown")) {
            toolNames.add("readFile");
            toolNames.add("writeFile");
        }
        if (KeywordMatcher.containsAnyWord(context, "pdf", "报告", "文档", "导出")) {
            toolNames.add("generatePDF");
        }
        // 终端组：收紧关键词——去掉宽泛的"执行"（文件/搜索类任务也常说"执行"），
        // 只在有明确的系统操作信号（具体命令词/系统任务词）时才暴露终端工具，
        // 引导模型优先使用文件/PDF 等专用工具（配合工具描述与 system prompt 的约束）
        if (KeywordMatcher.containsAnyWord(context, "terminal", "command", "shell", "bash", "命令", "终端",
                "python", "ls", "pwd", "date", "whoami", "cat", "df", "echo", "which",
                "brew", "安装", "环境", "版本")) {
            toolNames.add("executeTerminalCommand");
        }
        if (toolNames.size() == 1) {
            toolNames.addAll(Arrays.stream(availableTools)
                    .map(ToolCallback::getToolDefinition)
                    .map(ToolDefinition::name)
                    .filter(name -> !"executeTerminalCommand".equals(name))
                    .collect(Collectors.toSet()));
        }
        return toolNames;
    }

    private boolean hasMapTools() {
        return Arrays.stream(availableTools)
                .map(ToolCallback::getToolDefinition)
                .map(ToolDefinition::name)
                .anyMatch(this::isMapToolName);
    }

    private void addMapToolNames(Set<String> toolNames) {
        Arrays.stream(availableTools)
                .map(ToolCallback::getToolDefinition)
                .map(ToolDefinition::name)
                .filter(this::isMapToolName)
                .forEach(toolNames::add);
    }

    private boolean isMapToolName(String toolName) {
        String normalizedToolName = toolName.toLowerCase(Locale.ROOT);
        // 工具名是 "amap_around_search" 这类标识符，本来就该用子串匹配，不能加词边界
        return KeywordMatcher.containsSubstring(
                normalizedToolName,
                "map",
                "amap",
                "geo",
                "poi",
                "place",
                "around",
                "nearby",
                "distance",
                "direction",
                "route",
                "weather",
                "district"
        );
    }

    private boolean isAroundSearchToolName(String toolName) {
        String normalizedToolName = toolName.toLowerCase(Locale.ROOT);
        return normalizedToolName.contains("around_search") || normalizedToolName.contains("nearby");
    }

    /**
     * 确保 chatOptions.toolCallbacks 包含本轮所需工具,路由误过滤时从 availableTools 补齐
     */
    private void ensureToolCallbacksContain(List<String> requiredToolNames) {
        List<ToolCallback> currentCallbacks = this.chatOptions.getToolCallbacks();
        if (currentCallbacks == null || currentCallbacks.isEmpty()) {
            this.chatOptions.setToolCallbacks(Arrays.asList(availableTools));
            return;
        }
        Set<String> currentNames = currentCallbacks.stream()
                .map(tc -> tc.getToolDefinition().name())
                .collect(Collectors.toSet());
        boolean missing = requiredToolNames.stream().anyMatch(name -> !currentNames.contains(name));
        if (missing) {
            List<ToolCallback> patched = new ArrayList<>(currentCallbacks);
            for (String toolName : requiredToolNames) {
                if (!currentNames.contains(toolName)) {
                    Arrays.stream(availableTools)
                            .filter(t -> t.getToolDefinition().name().equals(toolName))
                            .findFirst()
                            .ifPresent(patched::add);
                }
            }
            this.chatOptions.setToolCallbacks(patched);
            log.info("{} 补齐缺失工具回调: {}", getName(), requiredToolNames.stream()
                    .filter(n -> !currentNames.contains(n)).collect(Collectors.joining(", ")));
        }
    }

    // ========== 熔断辅助方法 ==========

    /**
     * 记录工具调用失败,连续失败超阈值则触发熔断
     */
    private void recordToolFailure(String toolName) {
        int failures = toolFailureCount.merge(toolName, 1, Integer::sum);
        if (failures >= CIRCUIT_BREAKER_THRESHOLD) {
            long cooldownUntil = System.currentTimeMillis() + CIRCUIT_BREAKER_COOLDOWN_MS;
            toolDisabledUntil.put(toolName, cooldownUntil);
            log.warn("{} 工具 [{}] 连续失败 {} 次,已熔断,{} 秒后恢复",
                    getName(), toolName, failures, CIRCUIT_BREAKER_COOLDOWN_MS / 1000);
        }
    }

    /**
     * 判断工具是否处于熔断状态
     * 熔断冷却期内返回 true(不可用),冷却期过自动恢复
     */
    private boolean isCircuitBroken(String toolName) {
        Long cooldownUntil = toolDisabledUntil.get(toolName);
        if (cooldownUntil == null) {
            return false; // 从未被熔断
        }
        if (System.currentTimeMillis() > cooldownUntil) {
            // 冷却期已过,自动恢复
            toolDisabledUntil.remove(toolName);
            toolFailureCount.remove(toolName);
            log.info("{} 工具 [{}] 熔断冷却期结束,已恢复可用", getName(), toolName);
            return false;
        }
        return true; // 仍在冷却期
    }
}
