package com.yupi.yuaiagent.toolrouter;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 工具向量路由配置属性
 */
@Component
@ConfigurationProperties(prefix = "yuai.tool-router")
@Data
public class ToolVectorRouterProperties {

    /**
     * 是否启用语义向量工具路由
     */
    private boolean enabled = true;

    /**
     * 语义搜索返回的工具数
     */
    private int topK = 5;

    /**
     * 向量相似度阈值（0~1），低于此值的结果会被过滤
     */
    private double similarityThreshold = 0.2;

    /**
     * 向量维度（需与 EmbeddingModel 输出维度一致）
     */
    private int dimensions = 1536;

    /**
     * 存储工具向量的表名
     */
    private String vectorTableName = "tool_vector_store";

    /**
     * 工具向量索引的 schema 版本号。
     * 当工具集发生变更（新增/删除/修改工具，或修改中文场景关键词）时，
     * 手动递增此版本号，下次启动时会自动清空旧索引并重建。
     */
    private int schemaVersion = 1;
}
