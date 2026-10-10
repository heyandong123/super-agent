package com.yupi.yuaiagent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 统一的 RAG 文档预处理流水线
 */
@Component
@Slf4j
public class LoveAppRagIngestionPipeline {

    private final LoveAppDocumentLoader loveAppDocumentLoader;

    private final MyKeywordEnricher myKeywordEnricher;

    public LoveAppRagIngestionPipeline(LoveAppDocumentLoader loveAppDocumentLoader,
                                       MyKeywordEnricher myKeywordEnricher) {
        this.loveAppDocumentLoader = loveAppDocumentLoader;
        this.myKeywordEnricher = myKeywordEnricher;
    }

    public List<Document> prepareDocumentsForIndexing() {
        // loader（MarkdownDocumentReader）已按 "#### 问题标题" 切分成问答对 Document，
        // 且标题已合并回 content（见 LoveAppDocumentLoader），此处不再二次切分
        List<Document> rawDocuments = loveAppDocumentLoader.loadMarkdowns();
        List<Document> enrichedDocuments = myKeywordEnricher.enrichDocuments(rawDocuments);
        log.info("LoveApp RAG ingestion prepared: raw={}, enriched={}",
                rawDocuments.size(),
                enrichedDocuments.size());
        return enrichedDocuments;
    }
}
