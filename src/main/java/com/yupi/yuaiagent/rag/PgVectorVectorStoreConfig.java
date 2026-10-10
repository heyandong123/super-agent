package com.yupi.yuaiagent.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType.COSINE_DISTANCE;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType.HNSW;

/**
 * LoveApp PgVector 向量库配置
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "yuai.rag", name = "store-type", havingValue = "pgvector")
@Slf4j
public class PgVectorVectorStoreConfig {

    @Bean(name = {"pgVectorVectorStore", "loveAppRagVectorStore"})
    public VectorStore pgVectorVectorStore(JdbcTemplate jdbcTemplate,
                                           EmbeddingModel dashscopeEmbeddingModel,
                                           LoveAppRagIngestionPipeline loveAppRagIngestionPipeline,
                                           LoveAppRagProperties loveAppRagProperties) {
        LoveAppRagProperties.Pgvector pgvector = loveAppRagProperties.getPgvector();
        VectorStore vectorStore = PgVectorStore.builder(jdbcTemplate, dashscopeEmbeddingModel)
                .dimensions(pgvector.getDimensions())
                .distanceType(COSINE_DISTANCE)
                .indexType(HNSW)
                .initializeSchema(pgvector.isInitializeSchema())
                .schemaName(pgvector.getSchemaName())
                .vectorTableName(pgvector.getVectorTableName())
                .maxDocumentBatchSize(pgvector.getMaxDocumentBatchSize())
                .build();
        if (!loveAppRagProperties.isBootstrapEnabled()) {
            log.info("LoveApp PgVector 向量库已跳过启动期建库（yuai.rag.bootstrap-enabled=false）");
            return vectorStore;
        }
        try {
            // 灌库前先清空旧数据,避免每次启动重复累积
            // or(eq(id,'0'), ne(id,'0')) 恒为 true,匹配全部行
            FilterExpressionBuilder builder = new FilterExpressionBuilder();
            Filter.Expression deleteAllFilter = builder.or(
                    builder.eq("id", "0"),
                    builder.ne("id", "0")
            ).build();
            vectorStore.delete(deleteAllFilter);
            log.info("LoveApp PgVector 灌库前清空旧数据完成");
            List<Document> preparedDocuments = loveAppRagIngestionPipeline.prepareDocumentsForIndexing();
            // DashScope Embedding API 单次最多 25 条文本,必须手动分批 add,
            // 因为 PgVectorStore 内部的 BatchingStrategy 按 token 分批,不会按文档数截断
            final int EMBED_BATCH_SIZE = 20;
            for (int i = 0; i < preparedDocuments.size(); i += EMBED_BATCH_SIZE) {
                int end = Math.min(i + EMBED_BATCH_SIZE, preparedDocuments.size());
                vectorStore.add(preparedDocuments.subList(i, end));
                log.info("LoveApp PgVector 灌库进度: {}/{}", end, preparedDocuments.size());
            }
            log.info("LoveApp PgVector 灌库完成,共 {} 个文档片段", preparedDocuments.size());
        } catch (Exception e) {
            log.warn("LoveApp PgVector 向量库初始化失败，降级为保留空库启动：{}", e.getMessage());
        }
        return vectorStore;
    }
}
