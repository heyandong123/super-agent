package com.yupi.yuaiagent.agent;

import com.yupi.yuaiagent.advisor.MyLoggerAdvisor;
import com.yupi.yuaiagent.memory.ConversationMemoryService;
import com.yupi.yuaiagent.memory.MemoryAssembler;
import com.yupi.yuaiagent.memory.MemoryUpdateService;
import com.yupi.yuaiagent.toolrouter.ToolRoutingProperties;
import com.yupi.yuaiagent.toolrouter.ToolVectorRouter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * AI 超级智能体（拥有自主规划能力，可以直接使用）
 */
@Component
public class YuManus extends ToolCallAgent {

    public YuManus(@Qualifier("allTools") ToolCallback[] allTools,
                   ChatModel dashscopeChatModel,
                   MemoryAssembler memoryAssembler,
                   MemoryUpdateService memoryUpdateService,
                   ToolVectorRouter toolVectorRouter,
                   ToolRoutingProperties toolRoutingProperties,
                   ConversationMemoryService conversationMemoryService) {
        super(allTools, toolVectorRouter, toolRoutingProperties, conversationMemoryService);
        this.setName("yuManus");
        this.setMemoryAssembler(memoryAssembler);
        this.setMemoryUpdateService(memoryUpdateService);
//        String SYSTEM_PROMPT = """
//                You are YuManus, an all-capable AI assistant, aimed at solving any task presented by the user.
//                You have various tools at your disposal that you can call upon to efficiently complete complex requests.
//                """;
        String SYSTEM_PROMPT = """
                You are YuManus, an all-capable AI assistant, aimed at solving any task presented by the user.
                You have various tools at your disposal that you can call upon to efficiently complete complex requests.
                For simple lookups such as weather, news, prices or facts, call `searchWeb` directly —
                do not over-explore the tool catalog or chain unnecessary tools.
                Prefer dedicated tools for their purpose: use `readFile`/`writeFile`/`generatePDF`
                for file and PDF tasks, and `executeTerminalCommand` ONLY for system-level tasks
                (installing software, checking the environment, or shell commands the user explicitly requested).
                """;
        this.setSystemPrompt(SYSTEM_PROMPT);
        String NEXT_STEP_PROMPT = """
                If a tool can help with this step, pick ONE and use it.
                If you already have the answer, reply directly in text, then call `doTerminate`
                to end the run, listing what you did in the `taskChecklist`.
                If a tool fails twice, stop trying and answer with what you know, then call `doTerminate`.
                Before calling `doTerminate`, verify that EVERY requirement of the user's task is completed;
                if any part is still unfinished (e.g. a file to save), continue working on it first.
                Your final reply must mention the full paths of every file you created or saved
                (for example: "文件已保存到 /tmp/苏州.txt"), so the user knows exactly where the result is.
                Do NOT repeat your answer in a later step — after you have replied, end the run with `doTerminate`.
                """;
        this.setNextStepPrompt(NEXT_STEP_PROMPT);
        this.setMaxSteps(20);
        // 初始化 AI 对话客户端
        ChatClient chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();
        this.setChatClient(chatClient);
    }
}
