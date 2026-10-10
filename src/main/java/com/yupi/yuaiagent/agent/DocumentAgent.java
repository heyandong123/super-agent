package com.yupi.yuaiagent.agent;

import com.yupi.yuaiagent.advisor.MyLoggerAdvisor;
import com.yupi.yuaiagent.memory.ConversationMemoryService;
import com.yupi.yuaiagent.memory.MemoryAssembler;
import com.yupi.yuaiagent.memory.MemoryUpdateService;
import com.yupi.yuaiagent.toolrouter.ToolRoutingProperties;
import com.yupi.yuaiagent.toolrouter.ToolVectorRouter;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

/**
 * 文档与产物生成子智能体
 */
public class DocumentAgent extends ToolCallAgent {

    public DocumentAgent(ToolCallback[] documentTools,
                         ChatModel dashscopeChatModel,
                         MemoryAssembler memoryAssembler,
                         MemoryUpdateService memoryUpdateService,
                         ToolVectorRouter toolVectorRouter,
                         ToolRoutingProperties toolRoutingProperties,
                         ConversationMemoryService conversationMemoryService) {
        super(documentTools, toolVectorRouter, toolRoutingProperties, conversationMemoryService);
        this.setName("documentAgent");
        this.setMemoryAssembler(memoryAssembler);
        this.setMemoryUpdateService(memoryUpdateService);
        this.setSystemPrompt("""
                You are DocumentAgent, a specialist focused on file operations and deliverable generation.
                Use file and PDF tools carefully, keep outputs organized, and return clear artifact paths.
                """);
        this.setNextStepPrompt("""
                Complete the current document sub-task step by step.
                Create or update files only when needed, and call `doTerminate` once the requested artifact is ready.
                """);
        this.setMaxSteps(6);
        ChatClient chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();
        this.setChatClient(chatClient);
    }
}
