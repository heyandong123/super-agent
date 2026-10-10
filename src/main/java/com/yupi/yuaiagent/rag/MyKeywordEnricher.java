/*
package com.yupi.yuaiagent.rag;

import jakarta.annotation.Resource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.transformer.KeywordMetadataEnricher;
import org.springframework.stereotype.Component;

import java.util.List;

*/
/**
 * 基于 AI 的文档元信息增强器（为文档补充元信息）
 *//*

@Component
public class MyKeywordEnricher {

    @Resource(name = "deepSeekChatModel")
    private ChatModel chatModel;

    public List<Document> enrichDocuments(List<Document> documents) {
        KeywordMetadataEnricher keywordMetadataEnricher = new KeywordMetadataEnricher(dashscopeChatModel, 5);
        return  keywordMetadataEnricher.apply(documents);
    }
}
*/
package com.yupi.yuaiagent.rag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.transformer.KeywordMetadataEnricher;
import org.springframework.stereotype.Component;
import java.util.List;
/**
 * 基于 AI 的文档元信息增强器（为文档自动提取并补充关键词元信息）
 */
@Component
@Slf4j
public class MyKeywordEnricher {
    /**
     * 注入大模型底层引擎。
     * 因为提取关键词需要 LLM 的理解能力，所以这里必须引入 ChatModel。
     */
    @Resource(name = "deepSeekChatModel")
    private ChatModel chatModel;

    @Resource
    private LoveAppRagProperties loveAppRagProperties;

    public List<Document> enrichDocuments(List<Document> documents) {
        try {
            KeywordMetadataEnricher keywordMetadataEnricher = new KeywordMetadataEnricher(
                    chatModel,
                    loveAppRagProperties.getKeywordCount()
            );
            return keywordMetadataEnricher.apply(documents);
        } catch (Exception e) {
            log.warn("关键词增强失败，降级为仅使用切分文档入库：{}", e.getMessage());
            return documents;
        }
    }
}
