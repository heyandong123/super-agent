package com.yupi.yuaiagent.rag;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LoveApp RAG 配置项
 */
@Component
@ConfigurationProperties(prefix = "yuai.rag")
@Data
public class LoveAppRagProperties {

    /**
     * 可选值：memory / pgvector
     */
    private String storeType = "memory";

    /**
     * 是否在应用启动阶段执行 RAG 文档预处理和向量库建库。
     * 本地开发可以关闭，避免启动时联网调用 embedding / 关键词增强模型。
     */
    private boolean bootstrapEnabled = true;

    /**
     * 为每个文档片段提取的关键词数量
     */
    private int keywordCount = 5;

    private Retrieval retrieval = new Retrieval();

    private Splitter splitter = new Splitter();

    private Pgvector pgvector = new Pgvector();

    @Data
    public static class Retrieval {

        private double similarityThreshold = 0.1;

        private int topK = 3;
    }

    @Data
    public static class Splitter {

        private int chunkSize = 200;

        private int minChunkSizeChars = 100;

        private int minChunkLengthToEmbed = 10;

        private int maxNumChunks = 5000;

        private boolean keepSeparator = true;
    }

    @Data
    public static class Pgvector {

        private int dimensions = 1536;

        private boolean initializeSchema = true;

        private String schemaName = "public";

        private String vectorTableName = "vector_store";

        private int maxDocumentBatchSize = 10000;
    }
}
