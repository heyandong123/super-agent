package com.yupi.yuaiagent.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.knuddels.jtokkit.api.EncodingType.CL100K_BASE;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType.COSINE_DISTANCE;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType.HNSW;

/**
 * 对话记忆向量库配置
 * 创建独立的 PgVectorStore 实例，用单独的表存储每轮对话全文。
 * 容器里现在有 3 个 VectorStore Bean（知识库 loveAppRagVectorStore / 工具路由
 * toolRouterVectorStore / 本对话记忆 chatMemoryVectorStore），注入必须 @Qualifier 限定。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "yuai.memory-vector", name = "enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class MemoryVectorStoreConfig {

    @Bean(name = {"chatMemoryVectorStore"})
    public VectorStore chatMemoryVectorStore(JdbcTemplate jdbcTemplate,
                                             EmbeddingModel dashscopeEmbeddingModel,
                                             MemoryVectorProperties properties) {
        VectorStore vectorStore = PgVectorStore.builder(jdbcTemplate, dashscopeEmbeddingModel)
                .dimensions(properties.getDimensions())
                .distanceType(COSINE_DISTANCE)
                .indexType(HNSW)
                .initializeSchema(true)
                .schemaName("public")
                .vectorTableName(properties.getVectorTableName())
                // 客户端侧 token 分批策略（2026-10 修复）：默认策略按 8191×0.9 token 分批，
                // 而 DashScope text-embedding-v1 单条上限是 2048 —— 默认值意味着超长文档
                // 一定会打到服务端 400（本项目实测过一次 143~146 秒的重试风暴，
                // 见 CLAUDE.md 的嵌入事故记录）。这里的 2048/0.1 让客户端**提前**把
                // 超长文档拆开，而不是送去服务端挨 400。
                .batchingStrategy(new TokenCountBatchingStrategy(CL100K_BASE, 2048, 0.1))
                .maxDocumentBatchSize(20)
                .build();
        log.info("对话记忆 PgVector 向量存储已就绪，表名：{}", properties.getVectorTableName());
        return vectorStore;
    }
}
