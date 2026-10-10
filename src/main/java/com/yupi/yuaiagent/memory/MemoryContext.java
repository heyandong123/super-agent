package com.yupi.yuaiagent.memory;

import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.StringJoiner;

/**
 * 四层记忆聚合视图
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryContext {

    private SessionMetadata sessionMetadata;

    private UserProfile userProfile;

    private RecentSummary recentSummary;

    public String toPromptSection() {
        // StringJoiner 用于高效地构建由分隔符连接的字符串序列，避免手动处理分隔符和空值问题
        StringJoiner joiner = new StringJoiner("\n");
        if (sessionMetadata != null) {
            joiner.add("[Session Metadata]");
            joiner.add("channel=" + StrUtil.blankToDefault(sessionMetadata.getChannel(), "web"));
            joiner.add("locale=" + StrUtil.blankToDefault(sessionMetadata.getLocale(), "zh-CN"));
            joiner.add("timezone=" + StrUtil.blankToDefault(sessionMetadata.getTimezone(), "Asia/Shanghai"));
            joiner.add("mode=" + StrUtil.blankToDefault(sessionMetadata.getMode(), "general"));
            joiner.add("requestType=" + StrUtil.blankToDefault(sessionMetadata.getRequestType(), "chat"));
        }
        if (userProfile != null && hasUserProfileContent(userProfile)) {
            joiner.add("[User Profile]");
            if (StrUtil.isNotBlank(userProfile.getRelationshipStatus())) {
                joiner.add("relationshipStatus=" + userProfile.getRelationshipStatus());
            }
            if (StrUtil.isNotBlank(userProfile.getNickname())) {
                joiner.add("nickname=" + userProfile.getNickname());
            }
            if (StrUtil.isNotBlank(userProfile.getPreferredAnswerStyle())) {
                joiner.add("preferredAnswerStyle=" + userProfile.getPreferredAnswerStyle());
            }
            if (StrUtil.isNotBlank(userProfile.getCommunicationPreference())) {
                joiner.add("communicationPreference=" + userProfile.getCommunicationPreference());
            }
            if (StrUtil.isNotBlank(userProfile.getLongTermGoal())) {
                joiner.add("longTermGoal=" + userProfile.getLongTermGoal());
            }
            if (userProfile.getNotableFacts() != null && !userProfile.getNotableFacts().isEmpty()) {
                joiner.add("notableFacts=" + String.join("; ", userProfile.getNotableFacts()));
            }
        }
        if (recentSummary != null && StrUtil.isNotBlank(recentSummary.getSummary())) {
            joiner.add("[Recent Summary]");
            joiner.add(recentSummary.getSummary());
        }
        return joiner.toString();
    }

    private boolean hasUserProfileContent(UserProfile profile) {
        return StrUtil.isNotBlank(profile.getRelationshipStatus())
                || StrUtil.isNotBlank(profile.getNickname())
                || StrUtil.isNotBlank(profile.getPreferredAnswerStyle())
                || StrUtil.isNotBlank(profile.getCommunicationPreference())
                || StrUtil.isNotBlank(profile.getLongTermGoal())
                || (profile.getNotableFacts() != null && !profile.getNotableFacts().isEmpty());
    }
}
