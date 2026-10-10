package com.yupi.yuaiagent.app;

import cn.hutool.core.util.StrUtil;
import com.yupi.yuaiagent.advisor.MyLoggerAdvisor;
import com.yupi.yuaiagent.chatmemory.KryoFileBasedChatMemoryRepository;
import com.yupi.yuaiagent.constant.FileConstant;
import com.yupi.yuaiagent.memory.ConversationMemoryService;
import com.yupi.yuaiagent.memory.MemoryAssembler;
import com.yupi.yuaiagent.memory.MemoryContext;
import com.yupi.yuaiagent.memory.MemoryUpdateService;
import com.yupi.yuaiagent.rag.LoveAppRagCustomAdvisorFactory;
import com.yupi.yuaiagent.rag.LoveAppRagProperties;
import com.yupi.yuaiagent.rag.QueryRewriter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

@Component
@Slf4j
public class LoveApp {

    private final ChatClient chatClient;

    private final MemoryAssembler memoryAssembler;

    private final MemoryUpdateService memoryUpdateService;

    private final ChatMemory chatMemory;

    private static final String SYSTEM_PROMPT = "扮演深耕恋爱心理领域的专家。开场向用户表明身份，告知用户可倾诉恋爱难题。" +
            "围绕单身、恋爱、已婚三种状态提问：单身状态询问社交圈拓展及追求心仪对象的困扰；" +
            "恋爱状态询问沟通、习惯差异引发的矛盾；已婚状态询问家庭责任与亲属关系处理的问题。" +
            "引导用户详述事情经过、对方反应及自身想法，以便给出专属解决方案。";

    /**
     * 初始化 ChatClient
     *
     * @param dashscopeChatModel
     */
    public LoveApp(ChatModel dashscopeChatModel,
                   MemoryAssembler memoryAssembler,
                   MemoryUpdateService memoryUpdateService) {
        this.memoryAssembler = memoryAssembler;
        this.memoryUpdateService = memoryUpdateService;
        String fileDir = FileConstant.FILE_SAVE_DIR + "/chat-memory";
        KryoFileBasedChatMemoryRepository fileChatMemory = new KryoFileBasedChatMemoryRepository(fileDir);
        MessageWindowChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(fileChatMemory)
                .maxMessages(20)
                .build();
        this.chatMemory = chatMemory;
        chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        // 自定义日志 Advisor，可按需开启
                        new MyLoggerAdvisor()
//                        // 自定义推理增强 Advisor，可按需开启
//                       ,new ReReadingAdvisor()
                )
                .build();
    }
    /**
     * AI 基础对话（支持多轮对话记忆）
     *
     * @param message
     * @param chatId
     * @return
     */
    public String doChat(String message, String chatId) {
        String conversationId = normalizeConversationId(chatId);
        String runtimeSystemPrompt = appendConversationMemory(
                buildRuntimeSystemPrompt(conversationId, "love_app", "chat"), conversationId, message);
        ChatResponse chatResponse = chatClient
                .prompt()
                .system(runtimeSystemPrompt)
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        memoryUpdateService.recordLoveAppInteraction(conversationId, null, message, content, "chat");
        log.info("content: {}", content);
        return content;
    }
    /**
     * AI 基础对话（支持多轮对话记忆，SSE 流式传输）
     *
     * @param message
     * @param chatId
     * @return
     */
    public Flux<String> doChatByStream(String message, String chatId) {
        return doChatByStream(message, chatId, true);
    }

    /**
     * AI 基础对话（支持多轮对话记忆 + 可选 RAG 知识库增强，SSE 流式传输）
     *
     * @param message 用户消息
     * @param chatId  会话 ID
     * @param useRag  是否启用 RAG 知识库检索（默认开启；开启时用 QueryRewriter 重写查询词
     *                后经 RAG Advisor 检索知识库，把命中文档作为参考资料注入上下文）
     */
    public Flux<String> doChatByStream(String message, String chatId, boolean useRag) {
        String conversationId = normalizeConversationId(chatId);
        String runtimeSystemPrompt = appendConversationMemory(
                buildRuntimeSystemPrompt(conversationId, "love_app", "stream_chat"), conversationId, message);
        StringBuilder assistantOutput = new StringBuilder();
        // 多轮指代消解：RAG 场景先重写查询词（"那它怎么用？"→完整意图），再交给 RAG Advisor 检索
        String effectiveMessage = useRag
                ? queryRewriter.doQueryRewrite(message, getConversationHistory(conversationId))
                : message;
        var promptSpec = chatClient
                .prompt()
                .system(runtimeSystemPrompt)
                .user(effectiveMessage)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, conversationId));
        if (useRag) {
            // status 传 null：不按情感状态过滤，检索整个知识库。
            // （若传非空值如 "stream_chat"，factory 会加 status == xxx 的 filter，
            //   文档 metadata 的 status 是"单身/热恋"等，会导致全部文档被过滤、检索为空）
            promptSpec.advisors(LoveAppRagCustomAdvisorFactory.createLoveAppRagCustomAdvisor(
                    loveAppRagVectorStore, null, loveAppRagProperties));
        }
        return promptSpec
                .stream()
                .content()
                .doOnNext(assistantOutput::append)
                .doOnComplete(() -> memoryUpdateService.recordLoveAppInteraction(
                        conversationId,
                        null,  // relationshipStatus 只放真实状态；传 null 让模型从消息中抽取（曾误传 "stream_rag" 导致画像永远为空）
                        message,
                        assistantOutput.toString(),
                        useRag ? "stream_rag" : "stream_chat"  // RAG 标记放 requestType，不占用画像字段
                ));
    }

    record LoveReport(String title, List<String> suggestions) {

    }

    /**
     * AI 恋爱报告功能（实战结构化输出）
     *
     * @param message
     * @param chatId
     * @return
     */
    public LoveReport doChatWithReport(String message, String chatId) {
        String conversationId = normalizeConversationId(chatId);
        String runtimeSystemPrompt = buildRuntimeSystemPrompt(conversationId, "love_app", "report")
                + "\n\n每次对话后都要生成恋爱结果，标题为{用户名}的恋爱报告，内容为建议列表";
        LoveReport loveReport = chatClient
                .prompt()
                .system(runtimeSystemPrompt)
                .user(message)
                .advisors(new MyLoggerAdvisor())
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .entity(LoveReport.class);
        memoryUpdateService.recordLoveAppInteraction(conversationId, null, message, String.valueOf(loveReport), "report");
        log.info("loveReport: {}", loveReport);
        return loveReport;
    }

    // AI 恋爱知识库问答功能
    @Resource
    private Advisor loveAppRagCloudAdvisor;

    @Resource
    private QueryRewriter queryRewriter;

    @Resource(name = "loveAppRagVectorStore")
    private VectorStore loveAppRagVectorStore;

    @Resource
    private LoveAppRagProperties loveAppRagProperties;

    @Resource
    private ConversationMemoryService conversationMemoryService;
    /**
     * 和 RAG 知识库进行对话的核心业务方法
     *
     * @param message 用户输入的原始问题
     * @param chatId  当前对话的唯一标识（用于关联上下文记忆）
     * @return 最终大模型回复的字符串
     */
    public String doChatWithRag(String message, String chatId,String status) {
        String conversationId = normalizeConversationId(chatId);
        // =========================================================================
        // TODO 1: 查询重写 (Query Rewrite)
        // 核心原理：用户在多轮对话中的提问往往是指代不清的（例如：“那它具体怎么用？”）。
        // 如果直接拿这句话去向量数据库检索，命中率会极低。所以需要先用算法或小型 LLM
        // 结合历史记录，将其重写为完整的、包含独立意图的查询词（例如：“恋爱大师App具体怎么用？”）。
        // 提示：调用 queryRewriter 的 doQueryRewrite 方法处理 message。
        // =========================================================================
        String rewrittenMessage = queryRewriter.doQueryRewrite(message,
                getConversationHistory(conversationId));
        String runtimeSystemPrompt = buildRuntimeSystemPrompt(conversationId, "love_app", "rag");
        // =========================================================================
        // TODO 2: 链式调用 ChatClient 构建对话请求流水线
        // 核心原理：ChatClient 使用了流畅的 Builder 设计模式（链式调用）。
        // 你需要通过不断的 "点(.)" 操作，把各个拦截器（Advisor）拼装上去。
        // =========================================================================
        ChatResponse chatResponse = chatClient
                .prompt()
                .system(runtimeSystemPrompt)
                // 1. 填入用户问题
                // 提示：调用 .user() 方法，将上面重写好的 rewrittenMessage 传进去。
                // TODO: 填写代码
                .user(rewrittenMessage)
                // 2. 配置会话记忆拦截器 (Chat Memory Advisor)
                // 核心原理：LLM 是无状态的。这个 Advisor 会在请求发出前，根据 chatId 去缓存里捞出
                // 前几轮的对话记录，并悄悄拼接到本次请求中，从而让 LLM 产生“记忆”。
                // 提示：使用 .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                // TODO: 填写代码
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID,conversationId))
                // 3. 开启日志拦截器
                // 提示：添加 MyLoggerAdvisor 拦截器，方便在控制台打印完整的 prompt，利于 debug。
                // TODO: 填写代码
                .advisors(new MyLoggerAdvisor())
                // 4. 应用 RAG 知识库问答拦截器 (核心步骤！)
                // 核心原理：当请求经过这个 QuestionAnswerAdvisor 时，它会截获你的 rewrittenMessage，
                // 去我们注入的 loveAppVectorStore 中做向量相似度计算，查出最匹配的几段文档（Top-K），
                // 然后将这些文档作为系统提示词（System Prompt）塞给 LLM：“请根据以下参考资料回答用户问题...”。
                // 提示：实例化一个 QuestionAnswerAdvisor，并把 loveAppVectorStore 作为参数传进去。
                // TODO: 填写代码
                //.advisors(loveAppRagCloudAdvisor)
                .advisors(LoveAppRagCustomAdvisorFactory.createLoveAppRagCustomAdvisor(
                        loveAppRagVectorStore,
                        status,
                        loveAppRagProperties
                ))
                // 5. 触发网络调用并封装响应
                // 提示：调用 .call() 发起真正的 HTTP 请求，接着调用 .chatResponse() 将结果反序列化为对象。
                // TODO: 填写代码
                .call().chatResponse()
                ; // 不要漏掉这里的分号结束链式调用
        // =========================================================================
        // TODO 3: 从响应对象中提取纯文本结果
        // 核心原理：chatResponse 对象非常庞大，包含了 Token 消耗统计、安全过滤结果、停止原因等元数据。
        // 我们只需要拿到大模型最终生成的纯文本即可。
        // 提示：通过 chatResponse.getResult().getOutput().getText() 一层层剥开获取。
        // =========================================================================
        String content = chatResponse.getResult().getOutput().getText(); // 请替换为实际的提取代码

        memoryUpdateService.recordLoveAppInteraction(conversationId, status, message, content, "rag");
        log.info("最终回复内容: {}", content);
        return content;
    }


    // AI 调用工具能力
    @Resource
    private ToolCallback[] allTools;

    /**
     * AI 恋爱报告功能（支持调用工具）
     *
     * @param message
     * @param chatId
     * @return
     */
    public String doChatWithTools(String message, String chatId) {
        String conversationId = normalizeConversationId(chatId);
        String runtimeSystemPrompt = buildRuntimeSystemPrompt(conversationId, "love_app", "tool_chat");
        ChatResponse chatResponse = chatClient
                .prompt()
                .system(runtimeSystemPrompt)
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, conversationId))
                // 开启日志，便于观察效果
                .advisors(new MyLoggerAdvisor())
                .toolCallbacks(allTools)
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        memoryUpdateService.recordLoveAppInteraction(conversationId, null, message, content, "tool_chat");
        log.info("content: {}", content);
        return content;
    }

    // AI 调用 MCP 服务

    @Resource
    private ToolCallbackProvider toolCallbackProvider;
    /**
     * AI 恋爱报告功能（调用 MCP 服务）
     *
     * @param message
     * @param chatId
     * @return
     */
    public String doChatWithMcp(String message, String chatId) {
        String conversationId = normalizeConversationId(chatId);
        String runtimeSystemPrompt = buildRuntimeSystemPrompt(conversationId, "love_app", "mcp_chat");
        ChatResponse chatResponse = chatClient
                .prompt()
                .system(runtimeSystemPrompt)
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, conversationId))
                // 开启日志，便于观察效果
                .advisors(new MyLoggerAdvisor())
                .toolCallbacks(toolCallbackProvider.getToolCallbacks())
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        memoryUpdateService.recordLoveAppInteraction(conversationId, null, message, content, "mcp_chat");
        log.info("content: {}", content);
        return content;
    }

    /**
     * 对话记忆向量检索（规则触发）：
     * 每轮把用户消息当检索词，从本会话的历史对话全文库里找相关片段拼进 system prompt。
     * 画像/摘要提供"稳定事实 + 最近概览"，向量库兜底"具体细节"——摘要压缩会丢信息，
     * 检索全文虽然不能保证 100% 召回，但为"很久以前说过的事"提供了完整的第二通道。
     * 检索失败静默降级，不影响主链路（fail-open）。
     */
    private String appendConversationMemory(String systemPrompt, String conversationId, String message) {
        if (conversationMemoryService == null) {
            return systemPrompt;
        }
        String memory = conversationMemoryService.retrieveMemory(conversationId, message);
        if (StrUtil.isBlank(memory)) {
            return systemPrompt;
        }
        return systemPrompt + "\n\n[Past Conversation Memory]\n" + memory
                + "\n（以上为历史对话片段，供参考；回答时不要虚构其中未提到的内容）";
    }

    /**
     * 构建运行时系统提示词
     * 根据会话ID、模式和请求类型，组装记忆上下文并拼接到基础系统提示词中
     *
     * @param conversationId 会话ID
     * @param mode           模式（如 love_app）
     * @param requestType    请求类型（如 chat, stream_chat, report, rag 等）
     * @return 完整的系统提示词字符串
     */
    private String buildRuntimeSystemPrompt(String conversationId, String mode, String requestType) {
        // 恋爱大师链路无用户体系，统一归入 "default" 用户
        MemoryContext memoryContext = memoryAssembler.assemble("default", conversationId, mode, requestType);
        String memoryPrompt = memoryContext.toPromptSection();
        if (StrUtil.isBlank(memoryPrompt)) {
            return SYSTEM_PROMPT;
        }
        return SYSTEM_PROMPT + "\n\n" + memoryPrompt;
    }

    /**
     * 从对话记忆中提取最近几轮对话历史，供 QueryRewriter 理解上下文。
     */
    private String getConversationHistory(String conversationId) {
        List<org.springframework.ai.chat.messages.Message> messages =
                chatMemory.get(conversationId);
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        // 只取最近 6 条，避免 token 浪费
        int startIndex = Math.max(0, messages.size() - 6);
        return messages.subList(startIndex, messages.size()).stream()
                .filter(m -> m.getMessageType() == org.springframework.ai.chat.messages.MessageType.USER
                          || m.getMessageType() == org.springframework.ai.chat.messages.MessageType.ASSISTANT)
                .map(m -> m.getMessageType().name() + ": " + m.getText())
                .collect(Collectors.joining("\n"));
    }

    private String normalizeConversationId(String chatId) {
        return StrUtil.blankToDefault(chatId, "anonymous-loveapp")
                .replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
