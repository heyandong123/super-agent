package com.yupi.yuaiagent.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * LoveApp 内存向量库配置
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "yuai.rag", name = "store-type", havingValue = "memory", matchIfMissing = true)
@Slf4j
public class LoveAppVectorStoreConfig {

    @Bean(name = {"loveAppVectorStore", "loveAppRagVectorStore"})
    VectorStore loveAppVectorStore(EmbeddingModel dashscopeEmbeddingModel,
                                   LoveAppRagIngestionPipeline loveAppRagIngestionPipeline,
                                   LoveAppRagProperties loveAppRagProperties) {
        SimpleVectorStore simpleVectorStore = SimpleVectorStore.builder(dashscopeEmbeddingModel).build();
        if (!loveAppRagProperties.isBootstrapEnabled()) {
            log.info("LoveApp 内存向量库已跳过启动期建库（yuai.rag.bootstrap-enabled=false）");
            return simpleVectorStore;
        }
        try {
            List<Document> preparedDocuments = loveAppRagIngestionPipeline.prepareDocumentsForIndexing();
            simpleVectorStore.add(preparedDocuments);
        } catch (Exception e) {
            log.warn("LoveApp 内存向量库初始化失败，降级为空向量库启动：{}", e.getMessage());
        }
        return simpleVectorStore;
    }
}
