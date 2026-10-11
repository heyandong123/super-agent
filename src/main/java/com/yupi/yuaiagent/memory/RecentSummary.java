package com.yupi.yuaiagent.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 第三层记忆：滚动摘要（单用户单会话，全局一份）
 * <p>
 * 2026-10-11 单会话收敛：所有会话视为一条连续会话后，摘要承载「全程历史的滚动压缩」，
 * conversationId 字段删除——它已不参与任何寻址（仅曾用于复合键文件名）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecentSummary {

    private String summary;

    private String updatedAt;
}
