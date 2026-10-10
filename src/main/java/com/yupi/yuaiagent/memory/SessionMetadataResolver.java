package com.yupi.yuaiagent.memory;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.Locale;

/**
 * 第一层记忆解析器：生成请求级会话元数据。
 */
@Component
public class SessionMetadataResolver {

    public SessionMetadata resolve(String conversationId, String mode, String requestType) {
        return SessionMetadata.builder()
                .conversationId(conversationId)
                .channel("web")
                .locale(Locale.getDefault().toLanguageTag())
                .timezone(ZoneId.systemDefault().getId())
                .mode(StrUtil.blankToDefault(mode, "general"))
                .requestType(StrUtil.blankToDefault(requestType, "chat"))
                .build();
    }
}
