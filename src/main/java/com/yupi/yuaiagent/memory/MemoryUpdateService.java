package com.yupi.yuaiagent.memory;

import cn.hutool.core.util.StrUtil;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 统一更新第二层和第三层记忆。
 */
@Service
public class MemoryUpdateService {

    private final FileUserProfileRepository userProfileRepository;

    private final FileRecentSummaryRepository recentSummaryRepository;

    private final ConversationMemoryService conversationMemoryService;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MemoryUpdateService(FileUserProfileRepository userProfileRepository,
                               FileRecentSummaryRepository recentSummaryRepository,
                               ConversationMemoryService conversationMemoryService,
                               ChatModel dashscopeChatModel) {
        this.userProfileRepository = userProfileRepository;
        this.recentSummaryRepository = recentSummaryRepository;
        this.conversationMemoryService = conversationMemoryService;
        this.chatClient = ChatClient.builder(dashscopeChatModel).build();
    }

    /**
     * 恋爱大师路径（无用户体系），默认归入 "default" 用户。
     */
    public void recordLoveAppInteraction(String conversationId,
                                         String relationshipStatus,
                                         String userMessage,
                                         String assistantMessage,
                                         String requestType) {
        recordLoveAppInteraction("default", conversationId, relationshipStatus,
                userMessage, assistantMessage, requestType);
    }

    /**
     * 复合键写入：userId + conversationId。
     */
    public void recordLoveAppInteraction(String userId,
                                         String conversationId,
                                         String relationshipStatus,
                                         String userMessage,
                                         String assistantMessage,
                                         String requestType) {
        if (StrUtil.isBlank(conversationId)) {
            return;
        }
        updateUserProfile(userId, conversationId, relationshipStatus, userMessage);
        updateRecentSummary(userId, conversationId, "love_app", requestType, userMessage, assistantMessage);
        // 第五层记忆：每轮对话全文原样入库（画像/摘要会丢细节，向量库做完整回溯兜底）
        conversationMemoryService.saveExchange(userId, conversationId, userMessage, assistantMessage);
    }

    public void recordAgentInteraction(String userId,
                                       String conversationId,
                                       String agentName,
                                       String userMessage,
                                       String agentOutput) {
        if (StrUtil.isBlank(conversationId)) {
            return;
        }
        updateUserProfile(userId, conversationId, null, userMessage);
        updateRecentSummary(userId, conversationId, agentName, "agent_task", userMessage, agentOutput);
        conversationMemoryService.saveExchange(userId, conversationId, userMessage, agentOutput);
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
    public void updateUserProfile(String userId, String conversationId, String relationshipStatus, String userMessage) {
        UserProfile userProfile = userProfileRepository.findByUserKey(userId, conversationId);
        if (userProfile == null) {
            userProfile = UserProfile.builder()
                    .userKey(conversationId)
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
        userProfileRepository.save(userId, userProfile);
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
    private void updateRecentSummary(String userId,
                                     String conversationId,
                                     String mode,
                                     String requestType,
                                     String userMessage,
                                     String assistantMessage) {
        RecentSummary existing = recentSummaryRepository.findByConversationKey(userId, conversationId);
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
                .conversationId(conversationId)
                .summary(summary)
                .updatedAt(OffsetDateTime.now().toString())
                .build();
        recentSummaryRepository.save(userId, recentSummary);
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
