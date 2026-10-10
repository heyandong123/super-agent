package com.yupi.yuaiagent.rag;

import cn.hutool.core.util.StrUtil;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

/**
 * 创建自定义的 RAG 检索增强顾问的工厂
 * 【架构思维】：为什么用 Factory (工厂模式)？
 * 因为在复杂的 Agent 对话中，我们可能需要根据不同用户的状态（单身、热恋、失恋），
 * 动态地生成带有不同过滤条件的 Advisor。用静态工厂方法统一创建，代码更内聚。
 */
public class LoveAppRagCustomAdvisorFactory {
    /**
     * 创建自定义的 RAG 检索增强顾问 (核心方法)
     *
     * @param vectorStore 向量数据库实例（里面存了我们之前灌入的恋爱指南）
     * @param status      用户的当前情感状态（比如传入 "单身" 或 "热恋"）
     * @return 组装好各种检索策略的 Advisor 拦截器
     */
    public static Advisor createLoveAppRagCustomAdvisor(VectorStore vectorStore,
                                                        String status,
                                                        LoveAppRagProperties loveAppRagProperties) {
        LoveAppRagProperties.Retrieval retrieval = loveAppRagProperties.getRetrieval();
        VectorStoreDocumentRetriever.Builder retrieverBuilder = VectorStoreDocumentRetriever
                .builder()
                .vectorStore(vectorStore)
                .similarityThreshold(retrieval.getSimilarityThreshold())
                .topK(retrieval.getTopK());
        if (StrUtil.isNotBlank(status)) {
            Filter.Expression expression = new FilterExpressionBuilder()
                    .eq("status", status)
                    .build();
            retrieverBuilder.filterExpression(expression);
        }
        DocumentRetriever documentRetriever = retrieverBuilder.build();
        return RetrievalAugmentationAdvisor
                .builder()
                .documentRetriever(documentRetriever)
                .queryAugmenter(LoveAppContextualQueryAugmenterFactory.createInstance())
                .build(); // 请替换为最终的返回值
    }
}
