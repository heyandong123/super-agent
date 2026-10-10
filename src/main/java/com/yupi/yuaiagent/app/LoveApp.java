package com.yupi.yuaiagent.app;

import com.yupi.yuaiagent.advisor.MyLoggerAdvisor;
import com.yupi.yuaiagent.chatmemory.KryoFileBasedChatMemoryRepository;
import com.yupi.yuaiagent.rag.LoveAppRagCustomAdvisorFactory;
import com.yupi.yuaiagent.rag.QueryRewriter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

@Component
@Slf4j
public class LoveApp {

    private final ChatClient chatClient;

    private static final String SYSTEM_PROMPT = "扮演深耕恋爱心理领域的专家。开场向用户表明身份，告知用户可倾诉恋爱难题。" +
            "围绕单身、恋爱、已婚三种状态提问：单身状态询问社交圈拓展及追求心仪对象的困扰；" +
            "恋爱状态询问沟通、习惯差异引发的矛盾；已婚状态询问家庭责任与亲属关系处理的问题。" +
            "引导用户详述事情经过、对方反应及自身想法，以便给出专属解决方案。";

    /**
     * 初始化 ChatClient
     *
     * @param dashscopeChatModel
     */
    public LoveApp(ChatModel dashscopeChatModel) {
        // 初始化基于文件的对话记忆
//        String fileDir = System.getProperty("user.dir") + "/tmp/chat-memory";
//        KryoFileBasedChatMemoryRepository fileChatMemory = new KryoFileBasedChatMemoryRepository(fileDir);
        // 初始化基于内存的对话记忆
        MessageWindowChatMemory chatMemory = MessageWindowChatMemory.builder()
                //.chatMemoryRepository(fileChatMemory)
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
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
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
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
        return chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .stream()
                .content();
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
        LoveReport loveReport = chatClient
                .prompt()
                .system(SYSTEM_PROMPT + "每次对话后都要生成恋爱结果，标题为{用户名}的恋爱报告，内容为建议列表")
                .user(message)
                .advisors(new MyLoggerAdvisor())
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .entity(LoveReport.class);
        log.info("loveReport: {}", loveReport);
        return loveReport;
    }

    // AI 恋爱知识库问答功能
    @Resource
    private Advisor loveAppRagCloudAdvisor;

    @Resource
    private VectorStore pgVectorVectorStore;

    @Resource
    private QueryRewriter queryRewriter;
    /**
     * 这里注入的是你在上一个配置类中亲手创建并放入 Spring 容器的那个 VectorStore Bean。
     * 现在的它已经装满了被切分和增强过的业务领域知识。
     */
    @Resource
    private VectorStore loveAppVectorStore;
    /**
     * 和 RAG 知识库进行对话的核心业务方法
     *
     * @param message 用户输入的原始问题
     * @param chatId  当前对话的唯一标识（用于关联上下文记忆）
     * @return 最终大模型回复的字符串
     */
    public String doChatWithRag(String message, String chatId,String status) {
        // =========================================================================
        // TODO 1: 查询重写 (Query Rewrite)
        // 核心原理：用户在多轮对话中的提问往往是指代不清的（例如：“那它具体怎么用？”）。
        // 如果直接拿这句话去向量数据库检索，命中率会极低。所以需要先用算法或小型 LLM
        // 结合历史记录，将其重写为完整的、包含独立意图的查询词（例如：“恋爱大师App具体怎么用？”）。
        // 提示：调用 queryRewriter 的 doQueryRewrite 方法处理 message。
        // =========================================================================
        String rewrittenMessage = queryRewriter.doQueryRewrite(message); // 请替换为实际的重写逻辑代码
        // =========================================================================
        // TODO 2: 链式调用 ChatClient 构建对话请求流水线
        // 核心原理：ChatClient 使用了流畅的 Builder 设计模式（链式调用）。
        // 你需要通过不断的 "点(.)" 操作，把各个拦截器（Advisor）拼装上去。
        // =========================================================================
        ChatResponse chatResponse = chatClient
                .prompt()
                // 1. 填入用户问题
                // 提示：调用 .user() 方法，将上面重写好的 rewrittenMessage 传进去。
                // TODO: 填写代码
                .user(rewrittenMessage)
                // 2. 配置会话记忆拦截器 (Chat Memory Advisor)
                // 核心原理：LLM 是无状态的。这个 Advisor 会在请求发出前，根据 chatId 去缓存里捞出
                // 前几轮的对话记录，并悄悄拼接到本次请求中，从而让 LLM 产生“记忆”。
                // 提示：使用 .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                // TODO: 填写代码
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID,chatId))
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
                //.advisors(new QuestionAnswerAdvisor(pgVectorVectorStore))
                .advisors(LoveAppRagCustomAdvisorFactory.createLoveAppRagCustomAdvisor(loveAppVectorStore,status))
                //.advisors(loveAppRagCloudAdvisor)
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
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                // 开启日志，便于观察效果
                .advisors(new MyLoggerAdvisor())
                .toolCallbacks(allTools)
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
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
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                // 开启日志，便于观察效果
                .advisors(new MyLoggerAdvisor())
                .toolCallbacks(toolCallbackProvider)
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }
}
