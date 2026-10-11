package com.yupi.yuaiagent.memory;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 统一更新第二层和第三层记忆（单用户单会话版，2026-10-11）。
 * <p>
 * <b>2026-10 记忆重构 · 阶段 3：写入异步化</b>。本类是全部调用方写记忆的唯一通道
 * （LoveApp 的 6 个写入点 + BaseAgent 的 run/runStream 收尾），所以异步化只改这里，
 * 调用方零改动，且 LoveApp 那 6 个原先没有 fail-open 的写入点顺带获得保护
 * （任务在队列线程里执行，异常被 {@link MemoryWriteQueue} 吞掉并计数）。
 * <p>
 * 一次交互固定 3 次投递：抽画像（1 次 LLM）+ 写摘要（1 次 LLM）+ 向量归档（1 次嵌入）。
 * 三者互相独立、互不依赖结果，所以拆成三个任务（而不是一个长任务）：单个任务短 =
 * 队头阻塞小、失败面小（一条失败不牵连另两条）。执行顺序由队列的 FIFO 语义保证。
 * <p>
 * <b>2026-10-11 单用户单会话重构</b>：所有键（userId / conversationId）删除；新增
 * {@link #appendHistory} —— history.jsonl 是<b>同步</b>追加的，刻意不走异步队列：
 * ① 恢复读取发生在一个 run 的起始，若写入还压在队列里会读到缺了最新一轮的历史（双通道竞态）；
 * ② 它不做 LLM 调用（纯文件 append，亚毫秒级），没有"移出请求线程"的动机。
 * 失败在内部吞掉（fail-open）：LoveApp 的 6 个写点都在响应/reactive 链上，没有 try/catch。
 */
@Service
@Slf4j
public class MemoryUpdateService {

    private final FileUserProfileRepository userProfileRepository;

    private final FileRecentSummaryRepository recentSummaryRepository;

    private final FileConversationHistoryRepository historyRepository;

    private final ConversationMemoryService conversationMemoryService;

    /** 记忆写入队列：把 3~8 秒的 LLM/嵌入往返移出请求线程 */
    private final MemoryWriteQueue writeQueue;

    private final MemoryHistoryProperties historyProperties;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MemoryUpdateService(FileUserProfileRepository userProfileRepository,
                               FileRecentSummaryRepository recentSummaryRepository,
                               FileConversationHistoryRepository historyRepository,
                               ConversationMemoryService conversationMemoryService,
                               MemoryWriteQueue writeQueue,
                               MemoryHistoryProperties historyProperties,
                               ChatModel dashscopeChatModel) {
        this.userProfileRepository = userProfileRepository;
        this.recentSummaryRepository = recentSummaryRepository;
        this.historyRepository = historyRepository;
        this.conversationMemoryService = conversationMemoryService;
        this.writeQueue = writeQueue;
        this.historyProperties = historyProperties;
        this.chatClient = ChatClient.builder(dashscopeChatModel).build();
    }

    /**
     * 提交一次记忆写入。捕获的只有不可变值对象（字符串/布尔），绝不带 Agent 或请求态引用——
     * Agent 是每请求 new 的，而队列里的任务可能活得比它久。
     */
    private void submitWrite(String description, Runnable write) {
        writeQueue.submit(new MemoryWriteQueue.MemoryWriteTask() {
            @Override
            public void execute() {
                write.run();
            }

            @Override
            public String describe() {
                return description;
            }
        });
    }

    /**
     * 等所有已提交的记忆写入完成（测试与评测用；生产链路上没人等记忆）。
     *
     * @return true=已排空；false=超时仍有未完成的任务
     */
    public boolean awaitPendingWrites(long timeoutMillis) {
        return writeQueue.awaitIdle(timeoutMillis);
    }

    /** 队列指标行（submitted/completed/failed/rejected/timedOut/pending），排障用 */
    public String writeStats() {
        return writeQueue.stats();
    }

    /**
     * 恋爱大师路径：画像 + 摘要 + 归档（异步投递）+ 对话历史（同步追加）。
     * relationshipStatus 只放真实状态（单身/恋爱/已婚），传 null 让模型从消息中抽取——
     * 曾误传模式字符串 "stream_rag" 导致画像永远为空。
     */
    public void recordLoveAppInteraction(String relationshipStatus,
                                         String userMessage,
                                         String assistantMessage,
                                         String requestType) {
        if (StrUtil.isBlank(userMessage) && StrUtil.isBlank(assistantMessage)) {
            return;
        }
        // 三条写入互相独立 → 拆成三个任务投递（详见类注释）。describe 只用于日志定位。
        submitWrite("loveApp 画像",
                () -> updateUserProfile(relationshipStatus, userMessage));
        submitWrite("loveApp 摘要",
                () -> updateRecentSummary("love_app", requestType, userMessage, assistantMessage));
        // 第五层记忆：每轮对话全文原样入库（画像/摘要会丢细节，向量库做完整回溯兜底）
        submitWrite("loveApp 归档",
                () -> conversationMemoryService.saveExchange(userMessage, assistantMessage, "love_app"));
        appendHistory(userMessage, assistantMessage, "love_app");
    }

    /**
     * Agent 交互记忆写入。
     * <p>
     * <b>调用方负责开关</b>：{@code BaseAgent.recordMemoryAfterRun} 在
     * {@code memoryWriteEnabled=false}（多智能体子 Agent）时压根不会调到这里——
     * 单键化后画像/摘要/归档全局各一份，子 Agent 若照写就是污染唯一那份工件
     * （子任务指令是包装过的语言，不是用户的话语）。开关刻意放在调用方：
     * 这里少一个布尔参数，就少一条"以为关上了其实没关"的路径。
     * <p>
     * <b>注意：本方法不写 history.jsonl。</b>history 由 BaseAgent 收尾处显式调
     * {@link #appendHistory}——因为 agent 链路有 ERROR 状态语义（失败的一轮不进历史），
     * 而那个判定必须发生在调用方（cleanup 会把状态复位）。
     */
    public void recordAgentInteraction(String agentName,
                                       String userMessage,
                                       String agentOutput) {
        if (StrUtil.isBlank(userMessage) && StrUtil.isBlank(agentOutput)) {
            return;
        }
        submitWrite("Agent 画像",
                () -> updateUserProfile(null, userMessage));
        submitWrite("Agent 摘要",
                () -> updateRecentSummary(agentName, "agent_task", userMessage, agentOutput));
        // L5 归档：归档文本是「用户原话 + 给用户的最终答案」（2026-10 记忆重构前存的是步骤轨迹，检索命中率低）
        submitWrite("Agent 归档",
                () -> conversationMemoryService.saveExchange(userMessage, agentOutput, agentName));
    }

    /**
     * 同步追加一轮对话到 history.jsonl（单个 run 的近场恢复源）。
     * <p>
     * fail-open：内部吞掉全部异常只记 warn——调用方分布在响应链/reactive 回调上，
     * 记忆写失败绝不能把一次已经成功的对话变成 500。
     * 用户与助手任一侧为空时跳过：失败/空回答回放进上下文会误导模型。
     */
    public void appendHistory(String userMessage, String assistantMessage, String source) {
        if (!historyProperties.isEnabled()) {
            return;
        }
        if (StrUtil.isBlank(userMessage) || StrUtil.isBlank(assistantMessage)) {
            return;
        }
        try {
            historyRepository.append(new ConversationTurn(
                    OffsetDateTime.now().toString(),
                    userMessage,
                    assistantMessage,
                    StrUtil.blankToDefault(source, "unknown")));
        } catch (Exception e) {
            log.warn("对话历史追加失败（不影响主链路）: {}", e.getMessage());
        }
    }

//    private void updateUserProfile(String conversationId, String relationshipStatus, String userMessage) {
//        UserProfile userProfile = userProfileRepository.findByUserKey(conversationId);
//        if (userProfile == null) {
//            userProfile = UserProfile.builder()
//                    .userKey(conversationId)
//                    .notableFacts(new ArrayList<>())
//                    .build();
//        }
//        if (StrUtil.isNotBlank(relationshipStatus)) {
//            userProfile.setRelationshipStatus(relationshipStatus);
//        } else if (StrUtil.containsAnyIgnoreCase(userMessage, "单身", "恋爱", "已婚")) {
//            if (userMessage.contains("单身")) {
//                userProfile.setRelationshipStatus("单身");
//            } else if (userMessage.contains("已婚")) {
//                userProfile.setRelationshipStatus("已婚");
//            } else if (userMessage.contains("恋爱")) {
//                userProfile.setRelationshipStatus("恋爱");
//            }
//        }
//        if (StrUtil.containsAnyIgnoreCase(userMessage, "简洁", "简单点", "直接一点")) {
//            userProfile.setPreferredAnswerStyle("简洁");
//        } else if (StrUtil.containsAnyIgnoreCase(userMessage, "详细", "具体一点", "展开讲")) {
//            userProfile.setPreferredAnswerStyle("详细");
//        }
//        Set<String> notableFacts = new LinkedHashSet<>(userProfile.getNotableFacts() == null
//                ? List.of()
//                : userProfile.getNotableFacts());
//        if (StrUtil.containsAnyIgnoreCase(userMessage, "异地恋", "相亲", "复合", "结婚", "分手")) {
//            if (userMessage.contains("异地恋")) {
//                notableFacts.add("近期关注异地恋相关问题");
//            }
//            if (userMessage.contains("相亲")) {
//                notableFacts.add("近期关注相亲场景");
//            }
//            if (userMessage.contains("复合")) {
//                notableFacts.add("近期关注复合相关问题");
//            }
//            if (userMessage.contains("结婚")) {
//                notableFacts.add("近期关注结婚或婚后问题");
//            }
//            if (userMessage.contains("分手")) {
//                notableFacts.add("近期关注分手修复或情绪问题");
//            }
//        }
//        userProfile.setNotableFacts(new ArrayList<>(notableFacts));
//        userProfile.setUpdatedAt(OffsetDateTime.now().toString());
//        userProfileRepository.save(userProfile);
//    }
    /*public void updateUserProfile(String conversationId, String relationshipStatus, String userMessage) {
        UserProfile userProfile = userProfileRepository.findByUserKey(conversationId);
        if (userProfile == null) {
            userProfile = UserProfile.builder()
                    .userKey(conversationId)
                    .notableFacts(new ArrayList<>())
                    .build();
        }
        if (StrUtil.isNotBlank(relationshipStatus)) {
            userProfile.setRelationshipStatus(relationshipStatus);
        }else{
            String summary = chatClient.prompt()
                    .system("你是一个感情状态判断专家，你要从用户输入的提问中判断用户的感情状态，" +
                            "感情状态有“单身”、“恋爱”、“已婚”三种" +
                            "你要判断当前用户目前是处于哪种状态并输出这三种状态中的一种" +
                            "你要确保判断准确，如果从用户提问中判断不出感情状态，则输出“无法判断”")
                    .user(userMessage)
                    .call()
                    .content();
            if(!summary.equals("无法判断")){
                userProfile.setRelationshipStatus(summary);
            }
        }
        String answerHabit = chatClient.prompt()
                .system("你是一个用户要求模型回答习惯判断专家，你要从用户输入的提问中判断用户需要模型回答的习惯，" +
                        "模型回答的习惯有“简洁”、“详细”、“直白”三种" +
                        "你要判断当前用户目前是想要模型按哪种习惯回答，并输出这三种状态中的一种" +
                        "你要确保判断准确，如果从用户提问中判断不出回答习惯，则输出“无法判断”")
                .user(userMessage)
                .call()
                .content();
        if(!answerHabit.equals("无法判断")){
            userProfile.setPreferredAnswerStyle(answerHabit);
        }

        Set<String> notableFacts = new LinkedHashSet<>(userProfile.getNotableFacts() == null
                ? List.of()
                : userProfile.getNotableFacts());
        String curNotableFact = chatClient.prompt()
                .system("你是一个用户当前处境判断专家，你要从用户输入的提问中判断用户目前的处境，" +
                        "用户的处境有“异地恋”、“相亲”、“复合”、“结婚”、“分手”五种" +
                        "你要判断当前用户目前的处境，并输出这几种状态中的一个或几个，若输出多个状态，则每个状态直接用空格隔开" +
                        "你要确保判断准确，如果从用户提问中判断不出用户当前处境，则输出“无法判断”")
                .user(userMessage)
                .call()
                .content();
        if(!curNotableFact.equals("无法判断")){
            if (StrUtil.containsAnyIgnoreCase(curNotableFact, "异地恋", "相亲", "复合", "结婚", "分手")) {
                if (curNotableFact.contains("异地恋")) {
                    notableFacts.add("近期关注异地恋相关问题");
                }
                if (curNotableFact.contains("相亲")) {
                    notableFacts.add("近期关注相亲场景");
                }
                if (curNotableFact.contains("复合")) {
                    notableFacts.add("近期关注复合相关问题");
                }
                if (curNotableFact.contains("结婚")) {
                    notableFacts.add("近期关注结婚或婚后问题");
                }
                if (curNotableFact.contains("分手")) {
                    notableFacts.add("近期关注分手修复或情绪问题");
                }
            }
        }
        userProfile.setNotableFacts(new ArrayList<>(notableFacts));
        userProfile.setUpdatedAt(OffsetDateTime.now().toString());
        userProfileRepository.save(userProfile);
    }*/
    private record UserMemoryExtraction(
            String relationshipStatus,
            String preferredAnswerStyle,
            String nickname,
            String communicationPreference,
            String longTermGoal,
            List<String> notableFacts
    ) {
    }
    /**
     * 抽取并更新用户画像（单用户，全局一份）。
     * <p>
     * 2026-10-11：userId / conversationId 参数删除。画像历史根因链：
     * 最早按 conversationId 存（新会话永远从零）→ 2026-10 改按 userId 存（跨会话共享）
     * → 单用户单会话后连 userId 也是死复杂度，画像全局一份。
     */
    public void updateUserProfile(String relationshipStatus, String userMessage) {
        UserProfile userProfile = userProfileRepository.find();
        if (userProfile == null) {
            userProfile = UserProfile.builder()
                    .notableFacts(new ArrayList<>())
                    .build();
        }

        UserMemoryExtraction extraction = extractUserMemory(relationshipStatus, userMessage);

        if (isAllowedValue(extraction.relationshipStatus(), "单身", "恋爱", "已婚")) {
            userProfile.setRelationshipStatus(extraction.relationshipStatus());
        }

        if (isAllowedValue(extraction.preferredAnswerStyle(), "简洁", "详细", "直白")) {
            userProfile.setPreferredAnswerStyle(extraction.preferredAnswerStyle());
        }

        // nickname / longTermGoal 是自由文本：只做非空 + 长度校验（"无法判断"或过长不写）
        if (isValidFreeText(extraction.nickname(), 20)) {
            userProfile.setNickname(extraction.nickname().trim());
        }
        if (isAllowedValue(extraction.communicationPreference(), "直接", "委婉", "幽默", "温柔")) {
            userProfile.setCommunicationPreference(extraction.communicationPreference());
        }
        if (isValidFreeText(extraction.longTermGoal(), 50)) {
            userProfile.setLongTermGoal(extraction.longTermGoal().trim());
        }

        Set<String> notableFacts = new LinkedHashSet<>(userProfile.getNotableFacts() == null
                ? List.of()
                : userProfile.getNotableFacts());

        for (String fact : extraction.notableFacts()) {
            if ("异地恋".equals(fact)) {
                notableFacts.add("近期关注异地恋相关问题");
            } else if ("相亲".equals(fact)) {
                notableFacts.add("近期关注相亲场景");
            } else if ("复合".equals(fact)) {
                notableFacts.add("近期关注复合相关问题");
            } else if ("结婚".equals(fact)) {
                notableFacts.add("近期关注结婚或婚后问题");
            } else if ("分手".equals(fact)) {
                notableFacts.add("近期关注分手修复或情绪问题");
            }
        }

        userProfile.setNotableFacts(new ArrayList<>(notableFacts));
        userProfile.setUpdatedAt(OffsetDateTime.now().toString());
        userProfileRepository.save(userProfile);
    }
    private UserMemoryExtraction extractUserMemory(String relationshipStatus, String userMessage) {
        try {
            String json = chatClient.prompt()
                    .system("""
                    你是一个用户画像信息抽取器。
                    请从用户输入中抽取六类信息：
                    1. relationshipStatus：用户感情状态，只能是 "单身"、"恋爱"、"已婚"、"无法判断" 之一。
                    2. preferredAnswerStyle：用户希望的回答风格，只能是 "简洁"、"详细"、"直白"、"无法判断" 之一。
                    3. nickname：用户明确提到的自称或昵称（如"我叫小帅"→"小帅"），未提到则 "无法判断"。
                    4. communicationPreference：用户偏好的沟通方式，只能是 "直接"、"委婉"、"幽默"、"温柔"、"无法判断" 之一。
                    5. longTermGoal：用户表达的长期目标，用一句话概括（如"想通过相亲找到对象"），未提到则 "无法判断"。
                    6. notableFacts：用户当前关注场景，只能从 ["异地恋", "相亲", "复合", "结婚", "分手"] 中选择，可以为空数组。

                    只返回 JSON，不要输出任何解释。
                    JSON 格式如下：
                    {
                      "relationshipStatus": "无法判断",
                      "preferredAnswerStyle": "无法判断",
                      "nickname": "无法判断",
                      "communicationPreference": "无法判断",
                      "longTermGoal": "无法判断",
                      "notableFacts": []
                    }
                    """)
                    .user(userMessage)
                    .call()
                    .content();

            UserMemoryExtraction extraction = parseUserMemoryExtraction(json);

            // 防御：调用方传入的 relationshipStatus 必须通过白名单校验才采用，
            // 否则回落为 LLM 抽取（曾发生把模式字符串 "stream_rag" 传进来导致画像为空）
            String finalRelationshipStatus = isAllowedValue(relationshipStatus, "单身", "恋爱", "已婚")
                    ? relationshipStatus
                    : extraction.relationshipStatus();

            return new UserMemoryExtraction(
                    finalRelationshipStatus,
                    extraction.preferredAnswerStyle(),
                    extraction.nickname(),
                    extraction.communicationPreference(),
                    extraction.longTermGoal(),
                    extraction.notableFacts()
            );
        } catch (Exception e) {
            return new UserMemoryExtraction(
                    StrUtil.blankToDefault(relationshipStatus, "无法判断"),
                    "无法判断",
                    null,
                    null,
                    null,
                    List.of()
            );
        }
    }
    private UserMemoryExtraction parseUserMemoryExtraction(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);

        String extractedRelationshipStatus = root.path("relationshipStatus").asText("无法判断").trim();
        String preferredAnswerStyle = root.path("preferredAnswerStyle").asText("无法判断").trim();
        String nickname = root.path("nickname").asText("无法判断").trim();
        String communicationPreference = root.path("communicationPreference").asText("无法判断").trim();
        String longTermGoal = root.path("longTermGoal").asText("无法判断").trim();

        List<String> notableFacts = new ArrayList<>();
        JsonNode factsNode = root.path("notableFacts");
        if (factsNode.isArray()) {
            for (JsonNode factNode : factsNode) {
                String fact = factNode.asText("").trim();
                if (isAllowedValue(fact, "异地恋", "相亲", "复合", "结婚", "分手")) {
                    notableFacts.add(fact);
                }
            }
        }

        return new UserMemoryExtraction(
                extractedRelationshipStatus,
                preferredAnswerStyle,
                nickname,
                communicationPreference,
                longTermGoal,
                notableFacts
        );
    }
    private boolean isValidFreeText(String value, int maxLength) {
        return StrUtil.isNotBlank(value)
                && !"无法判断".equals(value.trim())
                && value.trim().length() <= maxLength;
    }
    private boolean isAllowedValue(String value, String... allowedValues) {
        if (StrUtil.isBlank(value) || "无法判断".equals(value.trim())) {
            return false;
        }
        for (String allowedValue : allowedValues) {
            if (allowedValue.equals(value.trim())) {
                return true;
            }
        }
        return false;
    }
    /**
     * 滚动摘要（单用户单会话，全局一份）：把「上一版摘要 + 最新交互」合并成新版。
     * <p>
     * 单会话收敛后这一份摘要承载全程历史——它是「全量加载上一次会话历史」里的"全程"部分
     * （近场原文由 history.jsonl 恢复）。
     */
    private void updateRecentSummary(String mode,
                                     String requestType,
                                     String userMessage,
                                     String assistantMessage) {
        RecentSummary existing = recentSummaryRepository.find();
        String previousSummary = existing == null ? "" : StrUtil.blankToDefault(existing.getSummary(), "");
        String latestExchange = """
                [Mode]
                %s

                [Request Type]
                %s

                [Latest User Message]
                %s

                [Latest Assistant Output]
                %s
                """.formatted(
                StrUtil.blankToDefault(mode, "general"),
                StrUtil.blankToDefault(requestType, "chat"),
                StrUtil.blankToDefault(userMessage, ""),
                StrUtil.blankToDefault(assistantMessage, "")
        );
        String summary = summarize(previousSummary, latestExchange);
        RecentSummary recentSummary = RecentSummary.builder()
                .summary(summary)
                .updatedAt(OffsetDateTime.now().toString())
                .build();
        recentSummaryRepository.save(recentSummary);
    }

    private String summarize(String previousSummary, String latestExchange) {
        try {
            String summary = chatClient.prompt()
                    .system("""
                            You maintain recent conversation memory for an AI assistant.
                            Merge the previous summary and latest exchange into a concise working summary.
                            Preserve:
                            1. current user goal or concern,
                            2. important emotional or task state,
                            3. commitments, unfinished items, and recent outcomes,
                            4. user preferences if explicitly mentioned.
                            Keep the summary under 220 Chinese characters when possible.
                            """)
                    .user("""
                            [Previous Summary]
                            %s

                            [Latest Exchange]
                            %s
                            """.formatted(
                            StrUtil.blankToDefault(previousSummary, "None"),
                            latestExchange
                    ))
                    .call()
                    .content();
            if (StrUtil.isNotBlank(summary)) {
                return summary.trim();
            }
        } catch (Exception ignored) {
            // 降级为本地截断摘要
        }
        String fallback = """
                上次摘要：%s
                最新交互：%s
                """.formatted(
                StrUtil.blankToDefault(previousSummary, "无"),
                latestExchange
        );
        return fallback.substring(0, Math.min(300, fallback.length()));
    }
}
