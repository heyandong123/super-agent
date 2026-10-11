package com.yupi.yuaiagent.controller;

import com.yupi.yuaiagent.context.ContextWindowManager;
import com.yupi.yuaiagent.agent.YuManus;
import com.yupi.yuaiagent.agent.YuManusMultiAgent;
import lombok.extern.slf4j.Slf4j;

import com.yupi.yuaiagent.app.LoveApp;
import com.yupi.yuaiagent.memory.ConversationMemoryService;
import com.yupi.yuaiagent.memory.MemoryAssembler;
import com.yupi.yuaiagent.memory.MemoryUpdateService;
import com.yupi.yuaiagent.stream.StreamingProperties;
import com.yupi.yuaiagent.toolrouter.ToolRoutingProperties;
import com.yupi.yuaiagent.toolrouter.ToolVectorRouter;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.context.ApplicationContext;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.Arrays;

@Slf4j
@RestController
@RequestMapping("/ai")
public class AiController {

    @Resource
    private LoveApp loveApp;

    @Resource
    private ToolCallback[] allTools;

    @Resource(name = "searchTools")
    private ToolCallback[] searchTools;

    @Resource(name = "documentTools")
    private ToolCallback[] documentTools;

    @Resource(name = "deepSeekChatModel")
    private ChatModel chatModel;

    @Resource
    private MemoryAssembler memoryAssembler;

    @Resource
    private MemoryUpdateService memoryUpdateService;

    @Resource
    private ConversationMemoryService conversationMemoryService;

    @Resource
    private ToolCallbackProvider toolCallbackProvider;

    @Resource
    private ToolVectorRouter toolVectorRouter;

    @Resource
    private ToolRoutingProperties toolRoutingProperties;

    @Resource
    private ContextWindowManager contextWindowManager;

    /** 流式配置（2026-10 流式轮）：与其它依赖一样用 @Resource 取，再手动 new Agent 时沿构造器传入 */
    @Resource
    private StreamingProperties streamingProperties;

    @Resource
    private ApplicationContext applicationContext;

    /**
     * 同步调用 AI 恋爱大师应用
     *
     * @param message
     * @param chatId
     * @return
     */
    @GetMapping("/love_app/chat/sync")
    public String doChatWithLoveAppSync(String message, String chatId) {
        return loveApp.doChat(message, chatId);
    }

    /**
     * SSE 流式调用 AI 恋爱大师应用
     *
     * @param message
     * @param chatId
     * @return
     */
    @GetMapping(value = "/love_app/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> doChatWithLoveAppSSE(String message, String chatId,
                                             @RequestParam(value = "rag", defaultValue = "true") boolean rag) {
        return loveApp.doChatByStream(message, chatId, rag);
    }

    /**
     * SSE 流式调用 AI 恋爱大师应用
     *
     * @param message
     * @param chatId
     * @return
     */
    @GetMapping(value = "/love_app/chat/server_sent_event")
    public Flux<ServerSentEvent<String>> doChatWithLoveAppServerSentEvent(String message, String chatId) {
        return loveApp.doChatByStream(message, chatId)
                //把每一个chunk都封装成着ServerSentEvent
                .map(chunk -> ServerSentEvent.<String>builder()
                        .data(chunk)
                        .build());
    }

    /**
     * SSE 流式调用 AI 恋爱大师应用
     *
     * @param message
     * @param chatId
     * @return
     */
    @GetMapping(value = "/love_app/chat/sse_emitter")
    public SseEmitter doChatWithLoveAppServerSseEmitter(String message, String chatId) {
        // 创建一个超时时间较长的 SseEmitter
        SseEmitter sseEmitter = new SseEmitter(180000L); // 3 分钟超时
        // 获取 Flux 响应式数据流并且直接通过订阅推送给 SseEmitter
        loveApp.doChatByStream(message, chatId)
                //定义了三个函数，有数据来时 (onNext)、发生错误时 (onError)、全部发完时 (onComplete)
                .subscribe(chunk -> {
                    try {
                        sseEmitter.send(chunk);
                    } catch (IOException e) {
                        sseEmitter.completeWithError(e);
                    }
                },
                        sseEmitter::completeWithError,
                        sseEmitter::complete);
        // 返回
        return sseEmitter;
    }

    /**
     * 流式调用 Manus 超级智能体
     *
     * @param message 用户消息
     * @param chatId  会话 ID（可为空：服务端生成并通过 session 事件回传，前端存 localStorage 复用。
     *                2026-10-11 单会话收敛后，它只用于日志追踪，不再参与记忆寻址）
     * @return
     */
    @GetMapping("/manus/chat")
    public SseEmitter doChatWithManus(String message, String chatId) {
        YuManus yuManus = new YuManus(
                mergeTools(allTools, mcpTools()), chatModel, memoryAssembler, memoryUpdateService,
                toolVectorRouter, toolRoutingProperties, conversationMemoryService, contextWindowManager,
                streamingProperties);
        yuManus.setConversationId(chatId);
        return yuManus.runStream(message);
    }

    /**
     * 流式调用多智能体版 Manus 协调器
     *
     * @param message 用户消息
     * @param chatId  会话 ID（可为空：服务端生成并通过 session 事件回传）
     * @return
     */
    @GetMapping("/manus/multi_agent/chat")
    public SseEmitter doChatWithMultiAgentManus(String message, String chatId) {
        ToolCallback[] mcpTools = mcpTools();
        YuManusMultiAgent yuManusMultiAgent = new YuManusMultiAgent(
                mergeTools(allTools, mcpTools),
                mergeTools(searchTools, mcpTools),
                documentTools,
                chatModel,
                memoryAssembler,
                memoryUpdateService,
                toolVectorRouter,
                toolRoutingProperties,
                conversationMemoryService,
                contextWindowManager,
                streamingProperties
        );
        yuManusMultiAgent.setConversationId(chatId);
        return yuManusMultiAgent.runStream(message);
    }

    private ToolCallback[] mcpTools() {
        var tools = new java.util.ArrayList<ToolCallback>();
        // 方式 1: 从注入的 ToolCallbackProvider 获取
        if (toolCallbackProvider != null) {
            ToolCallback[] providerTools = toolCallbackProvider.getToolCallbacks();
            if (providerTools != null && providerTools.length > 0) {
                tools.addAll(Arrays.asList(providerTools));
            }
        }
        // 方式 2: 从 Spring 容器中查找所有 ToolCallbackProvider bean(兜底)
        if (tools.isEmpty() && applicationContext != null) {
            var providers = applicationContext.getBeanProvider(ToolCallbackProvider.class);
            providers.forEach(provider -> {
                ToolCallback[] providerTools = provider.getToolCallbacks();
                if (providerTools != null && providerTools.length > 0) {
                    tools.addAll(Arrays.asList(providerTools));
                }
            });
        }
        log.info("MCP 工具数量: {}", tools.size());
        return tools.toArray(new ToolCallback[0]);
    }

    private ToolCallback[] mergeTools(ToolCallback[] first, ToolCallback[] second) {
        return Arrays.stream(new ToolCallback[][]{
                        first == null ? new ToolCallback[0] : first,
                        second == null ? new ToolCallback[0] : second
                })
                .flatMap(Arrays::stream)
                .toArray(ToolCallback[]::new);
    }
}
