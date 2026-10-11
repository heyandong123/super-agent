package com.yupi.yuaiagent.agent;

import com.yupi.yuaiagent.advisor.MyLoggerAdvisor;
import com.yupi.yuaiagent.context.ContextWindowManager;
import com.yupi.yuaiagent.memory.ConversationMemoryService;
import com.yupi.yuaiagent.memory.MemoryAssembler;
import com.yupi.yuaiagent.memory.MemoryUpdateService;
import com.yupi.yuaiagent.stream.StreamingProperties;
import com.yupi.yuaiagent.toolrouter.ToolRoutingProperties;
import com.yupi.yuaiagent.toolrouter.ToolVectorRouter;
import com.yupi.yuaiagent.metrics.UsageMetricsAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

/**
 * 搜索与资料收集子智能体
 */
public class SearchAgent extends ToolCallAgent {

    public SearchAgent(ToolCallback[] searchTools,
                       ChatModel dashscopeChatModel,
                       MemoryAssembler memoryAssembler,
                       MemoryUpdateService memoryUpdateService,
                       ToolVectorRouter toolVectorRouter,
                       ToolRoutingProperties toolRoutingProperties,
                       ConversationMemoryService conversationMemoryService,
                       ContextWindowManager contextWindowManager,
                       StreamingProperties streamingProperties) {
        super(searchTools, toolVectorRouter, toolRoutingProperties, conversationMemoryService, contextWindowManager,
                streamingProperties);
        this.setName("searchAgent");
        this.setMemoryAssembler(memoryAssembler);
        this.setMemoryUpdateService(memoryUpdateService);
        this.setSystemPrompt("""
                You are SearchAgent, a specialist focused on information gathering.
                Use only search and web retrieval tools to collect facts, links, and concise findings.
                For nearby-place, POI, distance, route, or map tasks, prefer map / AMap / POI tools over generic web search.
                For AMap nearby-place tasks, first geocode the target location if needed, then call around_search at most once.
                Use one broad keyword such as "约会地点", "休闲娱乐", or the most important category; do not fan out into coffee, restaurant, cinema, park, and milk-tea as separate tool calls.
                Do not repeatedly call the same failing tool. If a tool returns an error, explain the limitation and stop.
                Prefer factual, concise outputs over long prose.
                """);
        this.setNextStepPrompt("""
                Complete the current research sub-task step by step.
                Use the minimum number of tool calls needed.
                For map nearby searches, never call more than one around_search in the same step.
                If enough evidence has been collected, or the available tool fails / is rate-limited, summarize the findings and call `doTerminate`.
                """);
        this.setMaxSteps(3);
        ChatClient chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultAdvisors(new MyLoggerAdvisor(), new UsageMetricsAdvisor(this::getRunMetrics))
                .build();
        this.setChatClient(chatClient);
    }
}
