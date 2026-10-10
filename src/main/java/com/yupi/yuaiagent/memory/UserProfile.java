package com.yupi.yuaiagent.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 第二层记忆：用户稳定画像
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfile {

    private String userKey;

    private String relationshipStatus;

    private String nickname;

    private String preferredAnswerStyle;

    private String communicationPreference;

    private String longTermGoal;

    @Builder.Default
    private List<String> notableFacts = new ArrayList<>();

    private String updatedAt;
}
