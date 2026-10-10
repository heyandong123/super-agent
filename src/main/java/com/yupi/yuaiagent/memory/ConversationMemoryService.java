package com.yupi.yuaiagent.memory;

import cn.hutool.core.util.StrUtil;
import com.yupi.yuaiagent.rag.QueryRewriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 对话记忆向量服务（记忆架构第五层：外置完整档案）
 * <p>
 * 设计动机：画像（长期稳定事实）和摘要（近期压缩概览）都会丢失具体细节——
 * 摘要压缩是有损的。本服务把每轮对话全文原样存进独立向量库（平时不加载），
 * 模型在当前上下文里找不到信息时按需检索，作为摘要层的完整回溯兜底。
 * <p>
 * 两个检索触发方式：
 * 1. agent 链路：searchMemory 元工具，模型自述缺信息时显式调用（可观测、可审计）；
 * 2. LoveApp 链路：规则触发，每轮请求检索 top-k 拼进 system prompt。
 * <p>
 * 检索安全性：按 chatId 做 metadata 过滤，防止跨会话/跨用户串记忆。
 */
@Service
@Slf4j
public class ConversationMemoryService {

    private static final String METADATA_KEY_CHAT_ID = "chatId";

    private static final String METADATA_KEY_USER_ID = "userId";

    private final VectorStore chatMemoryVectorStore;

    private final QueryRewriter queryRewriter;

    private final MemoryVectorProperties properties;

    public ConversationMemoryService(@Qualifier("chatMemoryVectorStore") VectorStore chatMemoryVectorStore,
                                     QueryRewriter queryRewriter,
                                     MemoryVectorProperties properties) {
        this.chatMemoryVectorStore = chatMemoryVectorStore;
        this.queryRewriter = queryRewriter;
        this.properties = properties;
    }

    /**
     * 存储一轮对话（用户消息 + 助手回复拼成一条 Document 入库）。
     * 失败静默降级（fail-open）：记忆存储失败不影响对话主链路。
     */
    public void saveExchange(String userId, String chatId, String userMessage, String assistantMessage) {
        if (StrUtil.isBlank(chatId)) {
            return;
        }
        if (StrUtil.isBlank(userMessage) && StrUtil.isBlank(assistantMessage)) {
            return;
        }
        try {
            String text = """
                    [User]
                    %s

                    [Assistant]
                    %s
                    """.formatted(
                    StrUtil.blankToDefault(userMessage, ""),
                    StrUtil.blankToDefault(assistantMessage, "")
            );
            Document document = new Document(text, Map.of(
                    METADATA_KEY_CHAT_ID, chatId,
                    METADATA_KEY_USER_ID, StrUtil.blankToDefault(userId, "default"),
                    "timestamp", OffsetDateTime.now().toString()
            ));
            chatMemoryVectorStore.add(List.of(document));
        } catch (Exception e) {
            log.warn("对话记忆存储失败（不影响主链路）: {}", e.getMessage());
        }
    }

    /**
     * 检索历史对话：QueryRewriter 重写查询词 → metadata 过滤 chatId → top-k。
     *
     * @param chatId 会话 ID（隔离键，防止跨会话串记忆）
     * @param query  当前需求描述（用户消息或模型自述）
     * @return 格式化的历史对话片段；无命中或异常返回空串
     */
    public String retrieveMemory(String chatId, String query) {
        if (StrUtil.isBlank(chatId) || StrUtil.isBlank(query)) {
            return "";
        }
        try {
            // 查询重写：用户提问往往简短/指代不清（"那她后来怎么说？"），
            // 直接拿原文检索命中率低，先补全语义再检索
            String rewrittenQuery = queryRewriter.doQueryRewrite(query);
            Filter.Expression chatIdFilter = new FilterExpressionBuilder()
                    .eq(METADATA_KEY_CHAT_ID, chatId)
                    .build();
            SearchRequest request = SearchRequest.builder()
                    .query(rewrittenQuery)
                    .topK(properties.getTopK())
                    .similarityThreshold(properties.getSimilarityThreshold())
                    .filterExpression(chatIdFilter)
                    .build();
            List<Document> docs = chatMemoryVectorStore.similaritySearch(request);
            if (docs.isEmpty()) {
                return "";
            }
            return docs.stream()
                    .map(doc -> "- " + doc.getText().replace("\n", " "))
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.warn("对话记忆检索失败（降级为空）: {}", e.getMessage());
            return "";
        }
    }
}
