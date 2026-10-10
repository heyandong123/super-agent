package com.yupi.yuaiagent.memory;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 对话记忆向量库配置（yuai.memory-vector）
 * 记忆架构的"完整档案"层：每轮对话全文入向量库，平时不加载，按需检索。
 */
@Data
@Component
@ConfigurationProperties(prefix = "yuai.memory-vector")
public class MemoryVectorProperties {

    /** 是否启用对话记忆向量库（独立表，与知识库/工具路由库三表隔离） */
    private boolean enabled = true;

    /** 检索 Top-K */
    private int topK = 3;

    /**
     * 检索相似度阈值。
     * 对话记忆检索必须放宽（知识库 0.45 那套不适用）：用户回忆的关键信息
     * 往往与检索词措辞差异很大，宁可多召回让模型自己挑，也不要少召回。
     */
    private double similarityThreshold = 0.1;

    /** 向量维度（与 dashscope text-embedding-v2 一致，1536） */
    private int dimensions = 1536;

    /** 独立向量表名（不与知识库 vector_store、工具路由 tool_vector_store 共用） */
    private String vectorTableName = "chat_memory_store";
}
