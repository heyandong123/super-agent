package com.yupi.yuaiagent.agent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.yuaiagent.advisor.MyLoggerAdvisor;
import com.yupi.yuaiagent.agent.model.AgentState;
import com.yupi.yuaiagent.agent.model.MultiAgentPlan;
import com.yupi.yuaiagent.agent.model.SubAgentType;
import com.yupi.yuaiagent.agent.model.SubTaskRequest;
import com.yupi.yuaiagent.agent.model.SubTaskResult;
import com.yupi.yuaiagent.memory.ConversationMemoryService;
import com.yupi.yuaiagent.memory.MemoryAssembler;
import com.yupi.yuaiagent.memory.MemoryUpdateService;
import com.yupi.yuaiagent.toolrouter.ToolRoutingProperties;
import com.yupi.yuaiagent.toolrouter.ToolVectorRouter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 多智能体协调器
 */
@Slf4j
public class YuManusMultiAgent extends BaseAgent {

    private static final int MAX_SUMMARY_LENGTH = 600;

    private static final int LLM_COMPRESSION_TARGET_CHARS = 200;

    private static final int FALLBACK_TRUNCATION_CHARS = 300;

    private static final int MAX_POI_SUMMARY_COUNT = 8;

    private static final Pattern POI_PATTERN = Pattern.compile(
            "\\\\?\"name\\\\?\"\\s*:\\s*\\\\?\"([^\"\\\\]+)\\\\?\".*?\\\\?\"address\\\\?\"\\s*:\\s*\\\\?\"([^\"\\\\]*)\\\\?\"",
            Pattern.DOTALL
    );

    private final ToolCallback[] allTools;

    private final ToolCallback[] searchTools;

    private final ToolCallback[] documentTools;

    private final ChatModel dashscopeChatModel;

    private final MemoryAssembler memoryAssembler;

    private final MemoryUpdateService memoryUpdateService;

    private final ToolVectorRouter toolVectorRouter;

    private final ToolRoutingProperties toolRoutingProperties;

    private final ConversationMemoryService conversationMemoryService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final List<SubTaskRequest> taskQueue = new ArrayList<>();

    private final List<SubTaskResult> completedTasks = new ArrayList<>();

    private String planSummary;

    private boolean planInitialized;

    public YuManusMultiAgent(ToolCallback[] allTools,
                             ToolCallback[] searchTools,
                             ToolCallback[] documentTools,
                             ChatModel dashscopeChatModel,
                             MemoryAssembler memoryAssembler,
                             MemoryUpdateService memoryUpdateService,
                             ToolVectorRouter toolVectorRouter,
                             ToolRoutingProperties toolRoutingProperties,
                             ConversationMemoryService conversationMemoryService) {
        this.allTools = allTools;
        this.searchTools = searchTools;
        this.documentTools = documentTools;
        this.dashscopeChatModel = dashscopeChatModel;
        this.memoryAssembler = memoryAssembler;
        this.memoryUpdateService = memoryUpdateService;
        this.toolVectorRouter = toolVectorRouter;
        this.toolRoutingProperties = toolRoutingProperties;
        this.conversationMemoryService = conversationMemoryService;
        this.setName("yuManusMultiAgent");
        this.setMemoryAssembler(memoryAssembler);
        this.setMemoryUpdateService(memoryUpdateService);
        this.setSystemPrompt("""
                You are YuManusCoordinator, a coordinator agent.
                You do not directly use tools. Instead, you decompose the user's task into specialist sub-tasks,
                route them to the right specialist agent, and synthesize the final answer.
                """);
        this.setMaxSteps(8);
        ChatClient chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();
        this.setChatClient(chatClient);
    }

    @Override
    public String step() {
        if (!planInitialized) {
            initializePlan();
            return buildPlanMessage();
        }
        if (!taskQueue.isEmpty()) {
            SubTaskRequest subTaskRequest = taskQueue.remove(0);
            SubTaskResult subTaskResult = executeSubTask(subTaskRequest);
            completedTasks.add(subTaskResult);
            getMessageList().add(new AssistantMessage(buildSubTaskMessage(subTaskResult)));
            return buildSubTaskMessage(subTaskResult);
        }
        String finalAnswer = synthesizeFinalAnswer();
        getMessageList().add(new AssistantMessage(finalAnswer));
        setState(AgentState.FINISHED);
        return finalAnswer;
    }

    @Override
    protected void cleanup() {
        taskQueue.clear();
        completedTasks.clear();
        planSummary = null;
        planInitialized = false;
        super.cleanup();
    }

    private void initializePlan() {
        String userGoal = getOriginalUserGoal();
        MultiAgentPlan multiAgentPlan = createPlan(userGoal);
        if (multiAgentPlan == null || CollUtil.isEmpty(multiAgentPlan.getSteps())) {
            multiAgentPlan = buildFallbackPlan(userGoal);
        }
        this.planSummary = StrUtil.blankToDefault(multiAgentPlan.getSummary(), "已生成多智能体执行计划");
        this.taskQueue.clear();
        this.taskQueue.addAll(multiAgentPlan.getSteps());
        this.planInitialized = true;
        getMessageList().add(new AssistantMessage(buildPlanMessage()));
    }

    private MultiAgentPlan createPlan(String userGoal) {
        try {
            String rawPlan = getChatClient()
                    .prompt()
                    .system("""
                            You are a multi-agent planner.
                            Break the user's request into a short execution plan for specialist agents.
                            Available agent types:
                            - SEARCH: gathering external information from the web
                            - DOCUMENT: writing files or generating reports / PDF artifacts
                            - GENERAL: fallback general-purpose agent when specialist agents are not enough

                            Rules:
                            - For nearby places, maps, distance, route, restaurant, cafe, cinema, park, or POI tasks,
                              create a SEARCH step only. The search specialist can use map/POI tools.
                            - Do not create a DOCUMENT step unless the user explicitly asks to save, export, write a file,
                              generate a report, or generate a PDF.
                            - If the user only asks for an answer in chat, synthesize the final answer directly after SEARCH.

                            Return strict JSON only with this shape:
                            {
                              "summary": "short plan summary",
                              "steps": [
                                {
                                  "agentType": "SEARCH | DOCUMENT | GENERAL",
                                  "goal": "specific sub-task instruction",
                                  "reason": "why this specialist is needed"
                                }
                              ]
                            }

                            Keep the number of steps between 1 and 3.
                            """)
                    .user(userGoal)
                    .call()
                    .content();
            String json = extractJson(rawPlan);
            MultiAgentPlan multiAgentPlan = objectMapper.readValue(json, MultiAgentPlan.class);
            normalizePlan(multiAgentPlan);
            return multiAgentPlan;
        } catch (Exception e) {
            log.warn("多智能体计划生成失败，改用兜底计划：{}", e.getMessage());
            return null;
        }
    }

    private void normalizePlan(MultiAgentPlan multiAgentPlan) {
        if (multiAgentPlan == null || CollUtil.isEmpty(multiAgentPlan.getSteps())) {
            return;
        }
        List<SubTaskRequest> normalizedSteps = multiAgentPlan.getSteps().stream()
                .filter(step -> StrUtil.isNotBlank(step.getGoal()))
                .map(step -> new SubTaskRequest(
                        safeParseAgentType(step.getAgentType()),
                        step.getGoal().trim(),
                        StrUtil.blankToDefault(step.getReason(), "Planner generated step")
                ))
                .collect(Collectors.toList());
        multiAgentPlan.setSteps(normalizedSteps);
    }

    private SubAgentType safeParseAgentType(SubAgentType agentType) {
        return agentType == null ? SubAgentType.GENERAL : agentType;
    }

    /**
     * 构建兜底执行计划。
     * 当 AI 规划失败时，基于关键词规则简单判断用户需求，生成基础的子任务列表。
     * <p>
     * 生成逻辑：
     * 1. 将用户目标转换为小写，以便进行不区分大小写的匹配。
     * 2. 检测是否包含搜索类关键词（如 "search", "搜索", "http" 等），若包含则添加 SEARCH 类型子任务。
     * 3. 检测是否包含文档类关键词（如 "pdf", "文档", "write" 等），若包含则添加 DOCUMENT 类型子任务。
     * 4. 若上述均未匹配，则默认添加 GENERAL 类型子任务，由通用智能体处理。
     *
     * @param userGoal 用户原始目标
     * @return 兜底的多智能体执行计划
     */
    private MultiAgentPlan buildFallbackPlan(String userGoal) {
        MultiAgentPlan fallbackPlan = new MultiAgentPlan();
        fallbackPlan.setSummary("使用规则路由生成的兜底计划");
        String lowerCaseGoal = userGoal.toLowerCase(Locale.ROOT);
        boolean needsSearch = containsAny(lowerCaseGoal, "search", "搜索", "查找", "网页", "链接", "新闻", "天气", "地点", "http://", "https://");
        boolean needsDocument = containsAny(lowerCaseGoal, "pdf", "文档", "报告", "导出", "文件", "write", "保存");
        if (needsSearch) {
            fallbackPlan.getSteps().add(new SubTaskRequest(
                    SubAgentType.SEARCH,
                    "Collect the external information required to solve the user's request: " + userGoal,
                    "The task needs web research or page retrieval."
            ));
        }
        if (needsDocument) {
            fallbackPlan.getSteps().add(new SubTaskRequest(
                    SubAgentType.DOCUMENT,
                    "Create or update the requested deliverable for the user's request: " + userGoal,
                    "The task needs file or PDF generation."
            ));
        }
        if (fallbackPlan.getSteps().isEmpty()) {
            fallbackPlan.getSteps().add(new SubTaskRequest(
                    SubAgentType.GENERAL,
                    userGoal,
                    "Fallback to the general-purpose single agent."
            ));
        }
        return fallbackPlan;
    }

    private SubTaskResult executeSubTask(SubTaskRequest subTaskRequest) {
        String subTaskPrompt = buildSubTaskPrompt(subTaskRequest);
        String rawOutput;
        try {
            String childConversationId = resolveConversationId() + ":" + subTaskRequest.getAgentType().name().toLowerCase(Locale.ROOT);
            rawOutput = switch (subTaskRequest.getAgentType()) {
                case SEARCH -> {
                    SearchAgent searchAgent = new SearchAgent(
                            searchTools, dashscopeChatModel, memoryAssembler, memoryUpdateService,
                            toolVectorRouter, toolRoutingProperties, conversationMemoryService);
                    searchAgent.setConversationId(childConversationId);
                    searchAgent.setUserId(getUserId());
                    yield searchAgent.run(subTaskPrompt);
                }
                case DOCUMENT -> {
                    DocumentAgent documentAgent = new DocumentAgent(
                            documentTools, dashscopeChatModel, memoryAssembler, memoryUpdateService,
                            toolVectorRouter, toolRoutingProperties, conversationMemoryService);
                    documentAgent.setConversationId(childConversationId);
                    documentAgent.setUserId(getUserId());
                    yield documentAgent.run(subTaskPrompt);
                }
                case GENERAL -> {
                    YuManus yuManus = new YuManus(
                            allTools, dashscopeChatModel, memoryAssembler, memoryUpdateService,
                            toolVectorRouter, toolRoutingProperties, conversationMemoryService);
                    yuManus.setConversationId(childConversationId);
                    yuManus.setUserId(getUserId());
                    yield yuManus.run(subTaskPrompt);
                }
            };
            return new SubTaskResult(
                    subTaskRequest.getAgentType(),
                    subTaskRequest.getGoal(),
                    true,
                    summarizeOutput(rawOutput),
                    rawOutput
            );
        } catch (Exception e) {
            String errorMessage = "子任务执行失败：" + e.getMessage();
            return new SubTaskResult(
                    subTaskRequest.getAgentType(),
                    subTaskRequest.getGoal(),
                    false,
                    summarizeOutput(errorMessage),
                    errorMessage
            );
        }
    }

/*这段代码的主要作用是**构建子任务的提示词（Prompt）上下文**。

具体来说，它执行了以下操作：
1.  **获取原始用户目标**：调用 `getOriginalUserGoal()` 获取用户最初提出的完整需求。
2.  **收集已完成任务的上下文**：遍历 `completedTasks` 列表，将每个已完成的子任务结果格式化为字符串（包含智能体类型、目标和摘要），并用换行符连接起来。这有助于后续的智能体了解之前的执行历史和中间结果，保持任务的连贯性。

选中的代码片段正是这一过程的开始部分，负责初始化这两个关键变量。由于指令要求重写选中的代码，且原代码逻辑清晰、无明显错误，我将保持其逻辑不变，仅确保代码风格一致并完整保留选中部分。*/

    private String buildSubTaskPrompt(SubTaskRequest subTaskRequest) {
        String originalGoal = getOriginalUserGoal();
        String completedContext = completedTasks.stream()
                .map(result -> String.format(
                        "Agent=%s, Goal=%s, Summary=%s",
                        result.getAgentType(),
                        result.getGoal(),
                        result.getSummary()
                ))
                .collect(Collectors.joining("\n"));
        if (StrUtil.isBlank(completedContext)) {
            completedContext = "No previous sub-task results.";
        }
        return """
                Overall user goal:
                %s

                Current sub-task:
                %s

                Why this sub-task exists:
                %s

                Completed sub-task context:
                %s

                Produce only the result needed for this sub-task.
                """.formatted(
                originalGoal,
                subTaskRequest.getGoal(),
                StrUtil.blankToDefault(subTaskRequest.getReason(), "No extra reason."),
                completedContext
        );
    }

    private String synthesizeFinalAnswer() {
        String userGoal = getOriginalUserGoal();
        String taskDigest = completedTasks.stream()
                .map(result -> """
                        Agent: %s
                        Goal: %s
                        Success: %s
                        Summary: %s
                        """.formatted(
                        result.getAgentType(),
                        result.getGoal(),
                        result.isSuccess(),
                        result.getSummary()
                ))
                .collect(Collectors.joining("\n"));
        try {
            String finalAnswer = getChatClient()
                    .prompt()
                    .system("""
                            You are a coordinator summarizer.
                            Merge specialist-agent outputs into a final answer for the user.
                            Mention concrete artifact paths or important findings when available.
                            Keep the answer concise but complete.
                            """)
                    .user("""
                            User goal:
                            %s

                            Coordinator plan summary:
                            %s

                            Specialist results:
                            %s
                            """.formatted(
                            userGoal,
                            StrUtil.blankToDefault(planSummary, "No plan summary."),
                            taskDigest
                    ))
                    .call()
                    .content();
            return StrUtil.blankToDefault(finalAnswer, "任务已执行完成，但未生成最终总结。");
        } catch (Exception e) {
            log.warn("最终结果整合失败，使用本地兜底摘要：{}", e.getMessage());
            return """
                    多智能体任务已完成。
                    计划摘要：%s

                    子任务结果：
                    %s
                    """.formatted(
                    StrUtil.blankToDefault(planSummary, "无"),
                    taskDigest
            );
        }
    }

    private String buildPlanMessage() {
        String stepSummary = taskQueue.stream()
                .map(task -> String.format("[%s] %s", task.getAgentType(), task.getGoal()))
                .collect(Collectors.joining(" | "));
        return "多智能体计划已生成：" + planSummary + "。步骤：" + stepSummary;
    }

    private String buildSubTaskMessage(SubTaskResult subTaskResult) {
        return """
                子智能体执行完成：
                Agent=%s
                Goal=%s
                Success=%s
                Summary=%s
                """.formatted(
                subTaskResult.getAgentType(),
                subTaskResult.getGoal(),
                subTaskResult.isSuccess(),
                subTaskResult.getSummary()
        );
    }

    /**
     * 压缩子 Agent 输出为结构化摘要。
     * 三层策略：POI 专项提取 → LLM 语义压缩 → 兜底截断。
     */
    private String summarizeOutput(String rawOutput) {
        if (StrUtil.isBlank(rawOutput)) {
            return "No output returned.";
        }
        // 第 1 层：POI 地图数据专项提取
        String poiSummary = summarizePoiOutput(rawOutput);
        if (StrUtil.isNotBlank(poiSummary)) {
            return poiSummary;
        }
        // 第 2 层：LLM 语义压缩，保留关键结论与数据
        if (rawOutput.length() > LLM_COMPRESSION_TARGET_CHARS) {
            String llmSummary = compressWithLLM(rawOutput);
            if (StrUtil.isNotBlank(llmSummary)) {
                return llmSummary;
            }
        }
        // 第 3 层：兜底硬截断
        return rawOutput.length() <= FALLBACK_TRUNCATION_CHARS
                ? rawOutput
                : rawOutput.substring(0, FALLBACK_TRUNCATION_CHARS) + "...";
    }

    /**
     * 用 LLM 将子 Agent 输出压缩为简洁的结构化摘要。
     * 只保留关键结论、具体数据、文件路径和未完成事项，丢弃过程性描述。
     */
    private String compressWithLLM(String rawOutput) {
        try {
            String compressed = getChatClient()
                    .prompt()
                    .system("""
                            You are a context compressor.
                            Compress the agent output into a structured summary under 200 characters.
                            Preserve ONLY:
                            1. key findings and conclusions,
                            2. concrete numbers, file paths, artifact locations,
                            3. any unfinished items or follow-up actions.
                            Discard: step-by-step reasoning, tool invocation details, redundant text.
                            """)
                    .user(rawOutput)
                    .call()
                    .content();
            if (StrUtil.isNotBlank(compressed) && compressed.length() <= MAX_SUMMARY_LENGTH) {
                return compressed.trim();
            }
            // LLM 返回过长则截断
            return StrUtil.isNotBlank(compressed)
                    ? compressed.trim().substring(0, Math.min(compressed.length(), MAX_SUMMARY_LENGTH))
                    : null;
        } catch (Exception e) {
            log.warn("LLM 压缩子 Agent 输出失败，降级为截断：{}", e.getMessage());
            return null;
        }
    }

    private String summarizePoiOutput(String rawOutput) {
        if (!rawOutput.contains("pois")) {
            return "";
        }
        Matcher matcher = POI_PATTERN.matcher(rawOutput);
        List<String> poiLines = new ArrayList<>();
        while (matcher.find() && poiLines.size() < MAX_POI_SUMMARY_COUNT) {
            String name = matcher.group(1);
            String address = matcher.group(2);
            if (StrUtil.isBlank(name)) {
                continue;
            }
            poiLines.add(StrUtil.isBlank(address)
                    ? "- " + name
                    : "- " + name + "（" + address + "）");
        }
        if (poiLines.isEmpty()) {
            return "";
        }
        return "地图工具已返回候选约会地点：\n" + String.join("\n", poiLines);
    }

    private String getOriginalUserGoal() {
        Message firstMessage = CollUtil.getFirst(getMessageList());
        if (firstMessage instanceof UserMessage) {
            return firstMessage.getText();
        }
        return getMessageList().stream()
                .map(Message::getText)
                .collect(Collectors.joining("\n"));
    }

    private String extractJson(String rawText) {
        if (StrUtil.isBlank(rawText)) {
            throw new IllegalArgumentException("Planner returned blank text");
        }
        int start = rawText.indexOf('{');
        int end = rawText.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("Planner output does not contain valid JSON");
        }
        return rawText.substring(start, end + 1);
    }

    private boolean containsAny(String content, String... keywords) {
        return Arrays.stream(keywords).anyMatch(content::contains);
    }
}
