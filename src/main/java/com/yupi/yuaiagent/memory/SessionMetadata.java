package com.yupi.yuaiagent.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 第一层记忆：会话元数据
 * 仅用于本次请求的风格调节，不参与长期检索。
 * <p>
 * 2026-10-11：conversationId 字段删除——单会话语义下它没有记忆含义，
 * 请求级的会话 ID 仍由 BaseAgent 维护（SSE 回执/日志追踪），但不进记忆层。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionMetadata {

    private String channel;

    private String locale;

    private String timezone;

    private String mode;

    private String requestType;
}
