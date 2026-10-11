package com.yupi.yuaiagent.memory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 第二层记忆：用户稳定画像（单用户，全局一份）
 * <p>
 * 2026-10 记忆重构：画像从「会话维度」改为「用户维度」——按会话存等于新会话永远从零开始，
 * 「跨会话记得你」完全失效（曾实测：15 个会话 → 15 份互相独立的空画像）。
 * 2026-10-11 单用户单会话重构：userId 字段整体删除——项目没有登录体系，它恒为 "default"，
 * 是纯粹的字段噪声；「所有会话视为一条连续会话」后，画像也不再需要任何键。
 * <p>
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)}：老文件里的 userKey/userId 字段
 * 读取时忽略，不做数据迁移（tmp 是临时目录，跨版本兼容的成本高于重建）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserProfile {

    private String relationshipStatus;

    private String nickname;

    private String preferredAnswerStyle;

    private String communicationPreference;

    private String longTermGoal;

    @Builder.Default
    private List<String> notableFacts = new ArrayList<>();

    private String updatedAt;
}
