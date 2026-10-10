package com.yupi.yuaiagent.toolrouter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType.COSINE_DISTANCE;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType.HNSW;

/**
 * 工具向量路由器配置
 * 创建独立的 PgVectorStore 实例，用单独的表存储工具向量
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "yuai.tool-router", name = "enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class ToolVectorRouterConfig {

    @Bean(name = {"toolRouterVectorStore"})
    public VectorStore toolRouterVectorStore(JdbcTemplate jdbcTemplate,
                                              EmbeddingModel dashscopeEmbeddingModel,
                                              ToolVectorRouterProperties properties) {
        VectorStore vectorStore = PgVectorStore.builder(jdbcTemplate, dashscopeEmbeddingModel)
                .dimensions(properties.getDimensions())
                .distanceType(COSINE_DISTANCE)
                .indexType(HNSW)
                .initializeSchema(true)
                .schemaName("public")
                .vectorTableName(properties.getVectorTableName())
                .build();
        log.info("ToolRouter PgVector 向量存储已就绪，表名：{}", properties.getVectorTableName());
        return vectorStore;
    }
}
