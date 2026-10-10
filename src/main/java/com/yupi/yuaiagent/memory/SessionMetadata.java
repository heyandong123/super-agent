package com.yupi.yuaiagent.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 第一层记忆：会话元数据
 * 仅用于本次请求的风格调节，不参与长期检索。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionMetadata {

    private String conversationId;

    private String channel;

    private String locale;

    private String timezone;

    private String mode;

    private String requestType;
}
