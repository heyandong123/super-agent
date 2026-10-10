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

    @Resource
    private ChatModel dashscopeChatModel;

    public List<Document> enrichDocuments(List<Document> documents) {
        KeywordMetadataEnricher keywordMetadataEnricher = new KeywordMetadataEnricher(dashscopeChatModel, 5);
        return  keywordMetadataEnricher.apply(documents);
    }
}
*/
package com.yupi.yuaiagent.rag;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.transformer.KeywordMetadataEnricher;
import org.springframework.stereotype.Component;
import java.util.List;
/**
 * 基于 AI 的文档元信息增强器（为文档自动提取并补充关键词元信息）
 */
@Component
public class MyKeywordEnricher {
    /**
     * 注入大模型底层引擎。
     * 因为提取关键词需要 LLM 的理解能力，所以这里必须引入 ChatModel。
     */
    @Resource
    private ChatModel dashscopeChatModel;
    public List<Document> enrichDocuments(List<Document> documents) {
        // =========================================================================
        // TODO 1: 实例化 Spring AI 内置的关键词增强器
        // 核心原理: KeywordMetadataEnricher 内部其实封装着一段内置的 Prompt：
        // "Here is some text. Please extract the top N keywords..."
        // 它需要你提供两样东西：
        // 1. 负责干活的大模型 (dashscopeChatModel)
        // 2. 打算为每段文本提取几个关键词 (这里硬编码传入了 5)
        // 提示: new 一个 KeywordMetadataEnricher 对象，传入上述两个参数。
        // =========================================================================
        KeywordMetadataEnricher keywordMetadataEnricher = new KeywordMetadataEnricher(dashscopeChatModel,5); // 替换此处代码
        // =========================================================================
        // TODO 2: 执行增强动作并返回结果
        // 核心原理: 调用 apply() 方法时，程序会遍历你传入的 documents 列表。
        // 针对每一篇 Document，它都会向阿里云大模型发一次网络请求，拿到 5 个关键词后，
        // 自动塞进这篇 Document 的 metadata 字典里（通常对应的 Key 是 "keywords"）。
        // 提示: 调用 keywordMetadataEnricher 的 apply() 方法，把你传入的 documents 丢进去，并 return 结果。
        // =========================================================================
        return keywordMetadataEnricher.apply(documents); // 替换此处代码
    }
}
