package com.yupi.yuaiagent.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 第三层记忆：近期摘要
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecentSummary {

    private String conversationId;

    private String summary;

    private String updatedAt;
}
