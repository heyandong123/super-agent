package com.yupi.yuaiagent.memory;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;

/**
 * 统一拼装四层记忆上下文。
 */
@Component
public class MemoryAssembler {

    private final SessionMetadataResolver sessionMetadataResolver;

    private final FileUserProfileRepository userProfileRepository;

    private final FileRecentSummaryRepository recentSummaryRepository;

    public MemoryAssembler(SessionMetadataResolver sessionMetadataResolver,
                           FileUserProfileRepository userProfileRepository,
                           FileRecentSummaryRepository recentSummaryRepository) {
        this.sessionMetadataResolver = sessionMetadataResolver;
        this.userProfileRepository = userProfileRepository;
        this.recentSummaryRepository = recentSummaryRepository;
    }

    public MemoryContext assemble(String userId, String conversationId, String mode, String requestType) {
        String resolvedUserId = StrUtil.blankToDefault(userId, "default");
        String resolvedConversationId = StrUtil.blankToDefault(conversationId, "anonymous");
        SessionMetadata sessionMetadata = sessionMetadataResolver.resolve(resolvedConversationId, mode, requestType);
        // 复合键：userId + conversationId（画像与摘要都以用户维度隔离）
        UserProfile userProfile = userProfileRepository.findByUserKey(resolvedUserId, resolvedConversationId);
        RecentSummary recentSummary = recentSummaryRepository.findByConversationKey(resolvedUserId, resolvedConversationId);
        return MemoryContext.builder()
                .sessionMetadata(sessionMetadata)
                .userProfile(userProfile)
                .recentSummary(recentSummary)
                .build();
    }
}
