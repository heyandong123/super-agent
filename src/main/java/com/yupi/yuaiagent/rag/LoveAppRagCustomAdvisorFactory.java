/*
package com.yupi.yuaiagent.rag;

import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

*/
/**
 * 创建自定义的 RAG 检索增强顾问的工厂
 *//*

public class LoveAppRagCustomAdvisorFactory {

    */
/**
     * 创建自定义的 RAG 检索增强顾问
     *
     * @param vectorStore 向量存储
     * @param status      状态
     * @return 自定义的 RAG 检索增强顾问
     *//*

    public static Advisor createLoveAppRagCustomAdvisor(VectorStore vectorStore, String status) {
        // 过滤特定状态的文档
        Filter.Expression expression = new FilterExpressionBuilder()
                .eq("status", status)
                .build();
        // 创建文档检索器
        DocumentRetriever documentRetriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .filterExpression(expression) // 过滤条件
                .similarityThreshold(0.5) // 相似度阈值
                .topK(3) // 返回文档数量
                .build();
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(documentRetriever)
                .queryAugmenter(LoveAppContextualQueryAugmenterFactory.createInstance())
                .build();
    }
}
*/

// package 路径
package com.yupi.yuaiagent.rag;

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
    public static Advisor createLoveAppRagCustomAdvisor(VectorStore vectorStore, String status) {
        // =========================================================================
        // TODO 1: 构造元数据过滤条件 (Metadata Filtering)
        // 核心原理：大模型的向量检索是“模糊匹配（软性规则）”。但有时我们需要“精确匹配（硬性规则）”。
        // 还记得我们存入数据库的 JSON 元数据吗？里面有一个 "status" 字段。
        // 这里我们要告诉数据库：“在计算向量相似度之前，先帮我把那些 status 不等于用户当前状态的文档直接剔除掉！”
        // 提示：使用 new FilterExpressionBuilder() 开启链式调用，
        // 调用 .eq("status", status) 表示要求属性 "status" 等于传入的参数，
        // 最后 .build() 生成 Filter.Expression 表达式。
        // =========================================================================
        Filter.Expression expression = new FilterExpressionBuilder()
                .eq("status",status)
                .build(); // 请替换为实际的过滤构建代码
        // =========================================================================
        // TODO 2: 创建高阶文档检索器 (DocumentRetriever)
        // 核心原理：VectorStore 只是底层的数据库，而 DocumentRetriever 封装了具体的“检索策略”。
        // 提示：使用 VectorStoreDocumentRetriever.builder() 构建器，按需配置以下四项：
        // 1. .vectorStore(vectorStore) -> 绑定底层数据库
        // 2. .filterExpression(expression) -> 塞入 TODO 1 中写好的硬性过滤条件
        // 3. .similarityThreshold(0.5) -> 面试考点：相似度阈值。设置 0.5 意味着，如果查出来的文档相似度连 50% 都不到（说明根本不相关），就宁可不返回，也不要给大模型提供垃圾信息导致幻觉。
        // 4. .topK(3) -> 面试考点：召回数量。只取最相似的前 3 条文档，防止查出来的字数太多，把大模型的上下文 Token 撑爆，既费钱又容易导致 LLM "遗忘"。
        // 最后 .build() 结束。
        // =========================================================================
        DocumentRetriever documentRetriever = VectorStoreDocumentRetriever
                .builder()
                .vectorStore(vectorStore)
                .filterExpression(expression)
                .similarityThreshold(0.1)
                .topK(3)
                .build(); // 请替换为实际的构建代码
        // =========================================================================
        // TODO 3: 组装最终的 RAG 拦截器 (RetrievalAugmentationAdvisor)
        // 核心原理：把我们刚刚精心调教好的检索器（DocumentRetriever）和自定义的上下文拼接器（QueryAugmenter）
        // 组装成一个可以随时挂载到 ChatClient 上的拦截器。
        // 提示：使用 RetrievalAugmentationAdvisor.builder() 开启构建。
        // 1. 调用 .documentRetriever(documentRetriever) 放入检索器。
        // 2. 调用 .queryAugmenter(LoveAppContextualQueryAugmenterFactory.createInstance())
        //    放入自定义的提示词增强器（它负责把查出来的文档优美地拼接到 System Prompt 里）。
        // 3. .build() 并 return。
        // =========================================================================
        return RetrievalAugmentationAdvisor
                .builder()
                .documentRetriever(documentRetriever)
                .queryAugmenter(LoveAppContextualQueryAugmenterFactory.createInstance())
                .build(); // 请替换为最终的返回值
    }
}
