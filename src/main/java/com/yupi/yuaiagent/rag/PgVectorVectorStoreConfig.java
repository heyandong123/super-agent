/*
package com.yupi.yuaiagent.rag;

import jakarta.annotation.Resource;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType.COSINE_DISTANCE;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType.HNSW;

// 为方便开发调试和部署，临时注释，如果需要使用 PgVector 存储知识库，取消注释即可
@Configuration
public class PgVectorVectorStoreConfig {

    @Resource
    private LoveAppDocumentLoader loveAppDocumentLoader;

    @Bean
    public VectorStore pgVectorVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel dashscopeEmbeddingModel) {
        VectorStore vectorStore = PgVectorStore.builder(jdbcTemplate, dashscopeEmbeddingModel)
                .dimensions(1536)                    // Optional: defaults to model dimensions or 1536
                .distanceType(COSINE_DISTANCE)       // Optional: defaults to COSINE_DISTANCE
                .indexType(HNSW)                     // Optional: defaults to HNSW
                .initializeSchema(true)              // Optional: defaults to false
                .schemaName("public")                // Optional: defaults to "public"
                .vectorTableName("vector_store")     // Optional: defaults to "vector_store"
                .maxDocumentBatchSize(10000)         // Optional: defaults to 10000
                .build();
        // 加载文档
        List<Document> documents = loveAppDocumentLoader.loadMarkdowns();
        vectorStore.add(documents);
        return vectorStore;
    }
}
*/

package com.yupi.yuaiagent.rag;

import jakarta.annotation.Resource;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

// 静态导入枚举类，让代码更简洁
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType.COSINE_DISTANCE;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType.HNSW;

/**
 * PostgreSQL (pgvector) 向量数据库配置类
 * * 【面试级核心考点】：为什么这里把 @Configuration 注释掉了？
 * 在实际开发中，如果我们同时写了 SimpleVectorStore (内存库) 和 PgVectorStore (本地库)，
 * Spring 启动时会因为“不知道该用哪个 VectorStore”而报错（Bean 冲突）。
 * 这种通过注释来控制组件是否生效的方式是“硬开关”。
 * 在企业里，更优雅的做法是使用 @ConditionalOnProperty 注解，通过修改 yml 配置文件来动态决定启用哪个库。
 * 既然教程里写了临时注释，你目前可以直接保持注释状态，或者按需放开。
 */
@Configuration
public class PgVectorVectorStoreConfig {
    /**
     * 注入我们自定义的文档加载器。
     * 它的底层会去读取你项目里的 Markdown 知识库文件。
     */
    @Resource
    private LoveAppDocumentLoader loveAppDocumentLoader;
    /**
     * @Bean 声明这是一个交给 Spring 管理的组件。
     * * 【重点提示】：这里注入了两个极其关键的引擎。
     * 1. JdbcTemplate：Spring 提供操作关系型数据库的经典工具。它的出现意味着底层确实是在执行 SQL 语句。
     * 2. EmbeddingModel：大模型文本向量化引擎。存数据前需要靠它把文字变成数字。
     */
    @Bean
    public VectorStore pgVectorVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel dashscopeEmbeddingModel) {
        // =========================================================================
        // TODO: 构建并配置 PgVectorStore
        // 核心原理：这是一个非常经典的“核心引擎模式” Builder。
        // jdbcTemplate 和 dashscopeEmbeddingModel 是必须传入的“发动机”，
        // 剩下的都是微调数据库性能和结构的“选配参数”。
        // =========================================================================
        VectorStore vectorStore = PgVectorStore.builder(jdbcTemplate, dashscopeEmbeddingModel)
                // 1. 设置向量维度 (Dimensions)
                // 面试考点：大模型生成的向量长度是固定的。比如阿里的某些模型或 OpenAI 的 text-embedding-ada-002
                // 生成的都是 1536 维的浮点数。数据库的表结构必须和这个维度严格对应，否则存不进去。
                // 提示：调用 .dimensions() 方法，传入整数 1536。
                // TODO: 填写代码
                .dimensions(1536)
                // 2. 设置距离计算方式 (Distance Type)
                // 面试考点：怎么判断两句话意思相近？在多维空间中，通常计算两个向量夹角的余弦值。
                // 余弦距离 (Cosine Distance) 是目前文本语义检索最常用、效果最好的数学度量方式。
                // 提示：调用 .distanceType() 方法，传入枚举值 COSINE_DISTANCE (需要静态导入，或者通过 PgVectorStore.PgDistanceType.COSINE_DISTANCE)。
                // TODO: 填写代码
                .distanceType(COSINE_DISTANCE)
                // 3. 设置索引类型 (Index Type)
                // 面试考点：如果你有 1000 万条数据，每次检索都要和这 1000 万条挨个计算一遍距离吗？那太慢了！
                // HNSW (分层导航小世界) 是一种极速的“近似最近邻”搜索算法，它通过在不同层级建立图状网络，
                // 能在毫秒级找出最相似的向量，是现代向量数据库的性能核心。
                // 提示：调用 .indexType() 方法，传入枚举值 HNSW (通常也是枚举常量)。
                // TODO: 填写代码
                .indexType(HNSW)
                // 4. 是否自动初始化表结构 (Initialize Schema)
                // 提示：在开发阶段，我们希望 Spring 启动时自动去 Postgres 里建好存向量的表。
                // 提示：调用 .initializeSchema() 方法，传入 boolean 值 true。
                // TODO: 填写代码
                .initializeSchema(true)
                // 5. 设置数据库 Schema 名称
                // 提示：PostgreSQL 中的 Schema 类似于命名空间，"public" 是默认的公共空间。
                // 提示：调用 .schemaName()，传入字符串 "public"。
                // TODO: 填写代码
                .schemaName("public")
                // 6. 设置存向量的表名 (Vector Table Name)
                // 提示：指定表名，后续你可以直接用 Navicat 等数据库工具去查看这张表里的数据。
                // 提示：调用 .vectorTableName()，传入字符串 "vector_store"。
                // TODO: 填写代码
                .vectorTableName("vector_store")
                // 7. 设置最大批量插入大小 (Max Document Batch Size)
                // 提示：如果一次性要存几万篇文档，不能一次全塞给数据库，会把内存撑爆。这里设置分批处理的阈值。
                // 提示：调用 .maxDocumentBatchSize()，传入整数 10000。
                // TODO: 填写代码
                .maxDocumentBatchSize(10000)
                // 8. 完成构建
                // 提示：调用 .build() 结束链式调用。
                // TODO: 填写代码
                .build()
                ;
        // =========================================================================
        // TODO 2: 加载本地 Markdown 文档
        // 核心原理：在 Spring 启动、初始化这个 Bean 的时候，我们就顺手把知识库文件读到内存里。
        // 提示：调用上方注入的 loveAppDocumentLoader 的 loadMarkdowns() 方法，
        // 使用一个 List<Document> 类型的变量来接收返回值。
        // =========================================================================
        List<Document> documents = loveAppDocumentLoader.loadMarkdowns();
        // =========================================================================
        // TODO 3: 将文档持久化存入 PostgreSQL 向量库
        // 核心原理：调用 add() 方法时，Spring AI 底层会自动拿着这些 Document，
        // 去调用 dashscopeEmbeddingModel 把文本变成向量，然后再执行 INSERT SQL 语句存入数据库。
        // 提示：调用 vectorStore 的 add() 方法，把你 TODO 2 拿到的文档集合传进去。
        // =========================================================================
        vectorStore.add(documents);
        // =========================================================================
        // TODO 4: 返回完全就绪的 vectorStore
        // =========================================================================
        return vectorStore;
    }
}