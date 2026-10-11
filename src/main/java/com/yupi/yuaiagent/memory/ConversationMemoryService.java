package com.yupi.yuaiagent.memory;

import cn.hutool.core.util.StrUtil;
import com.yupi.yuaiagent.context.ContextTokenEstimator;
import com.yupi.yuaiagent.rag.QueryRewriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
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
 * <b>2026-10-11 单用户单会话重构：检索不再做键过滤。</b>旧的 (userId, chatId) 双键过滤是
 * 「多用户租户隔离」的产物；单用户单会话语义下，agent 与恋爱大师本就是同一条线、
 * 互相可见是正确行为（历史上曾按 chatId 过滤，那是"会话隔离"语义的遗留）。
 * PG 里带旧 metadata 的档案仍会被命中——符合语义，不清库。
 * 归档 metadata 保留 {@code source}（agentName / love_app），便于将来按来源过滤。
 */
@Service
@Slf4j
public class ConversationMemoryService {

    /** 归档来源标识（agentName / love_app）：本轮不过滤，仅为将来可能的来源过滤留口 */
    private static final String METADATA_KEY_SOURCE = "source";

    /**
     * 单条归档文本的 token 上限：DashScope text-embedding-v1 单条硬上限 2048，
     * 这里按 1800 截断（留约 12% 余量）。
     * <p>
     * 为什么必须在写入前截断：超长文档会被服务端 400 拒绝，而客户端重试策略
     * 会把它当成可重试错误反复重试（实测一次超长归档要 143~146 秒才最终失败，
     * 期间一直占着请求线程，然后 fail-open 静默丢掉这条记忆——又慢又没存上）。
     * <p>
     * 口径：这里是<b>精确 token 数</b>（CL100K_BASE，与客户端 batching 策略同源），
     * 不是估算值。踩过的坑：最早用「启发式上界口径」截到 1800，实测仍有约 2000 token
     * （中文常见字在 CL100K 里常是 1~2 token/字，启发式上界低估约 10%），
     * 于是文档照样过不了 2048×(1-10%)=1843 的 batching 闸门，被拒绝后 fail-open 静默丢弃——
     * 又变成「不烧钱了但记忆也没了」。硬上限场景必须用精确口径。
     */
    private static final int MAX_ARCHIVE_TOKENS = 1800;

    /** 助手段的独立预算：超出时先截助手段（用户原话是检索时的主锚点，优先保用户段） */
    private static final int ASSISTANT_ARCHIVE_TOKENS = 1200;

    /** 用户段至少保留的 token（截到只剩个位数的用户消息没有检索价值） */
    private static final int MIN_USER_ARCHIVE_TOKENS = 200;

    /** 截断标记：写进文本本身，检索命中时模型能看到「这里被截过」 */
    private static final String TRUNCATED_SUFFIX = "…（归档已截断）";

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
     *
     * @param source 来源标识（agentName / love_app），只进 metadata 供将来过滤，本轮检索不用
     */
    public void saveExchange(String userMessage, String assistantMessage, String source) {
        if (StrUtil.isBlank(userMessage) && StrUtil.isBlank(assistantMessage)) {
            return;
        }
        try {
            String text = buildArchiveText(userMessage, assistantMessage);
            Document document = new Document(text, Map.of(
                    METADATA_KEY_SOURCE, StrUtil.blankToDefault(source, "unknown"),
                    "timestamp", OffsetDateTime.now().toString()
            ));
            chatMemoryVectorStore.add(List.of(document));
        } catch (Exception e) {
            log.warn("对话记忆存储失败（不影响主链路）: {}", e.getMessage());
        }
    }

    /**
     * 构造归档文本：[User] + [Assistant]，并保证单条不超过嵌入服务端的硬上限。
     * <p>
     * 截断优先级：先截助手段（它的价值是补充细节），再截用户段（检索锚点，尽量保），
     * 最后整体兜底截断——兜底那一步是「无论输入多离谱都不触发 400」的硬保证。
     * <p>
     * 纯函数（不依赖实例状态）且包级可见：离线单测直接验「任意长度输入都落在嵌入上限内」，
     * 不需要起 Spring 容器也不需要真实 embedding 调用。
     */
    static String buildArchiveText(String userMessage, String assistantMessage) {
        String user = StrUtil.blankToDefault(userMessage, "");
        String assistant = ContextTokenEstimator.truncateToExactTokens(
                StrUtil.blankToDefault(assistantMessage, ""), ASSISTANT_ARCHIVE_TOKENS, TRUNCATED_SUFFIX);
        String text = renderArchiveText(user, assistant);
        if (ContextTokenEstimator.countTokensExact(text) > MAX_ARCHIVE_TOKENS) {
            int excess = ContextTokenEstimator.countTokensExact(text) - MAX_ARCHIVE_TOKENS;
            int userBudget = Math.max(MIN_USER_ARCHIVE_TOKENS,
                    ContextTokenEstimator.countTokensExact(user) - excess);
            user = ContextTokenEstimator.truncateToExactTokens(user, userBudget, TRUNCATED_SUFFIX);
            text = renderArchiveText(user, assistant);
        }
        return ContextTokenEstimator.truncateToExactTokens(text, MAX_ARCHIVE_TOKENS, TRUNCATED_SUFFIX);
    }

    static String renderArchiveText(String user, String assistant) {
        return """
                [User]
                %s

                [Assistant]
                %s
                """.formatted(user, assistant);
    }

    /**
     * 检索历史对话：QueryRewriter 重写查询词 → 整库 top-k（单用户单会话，无键过滤，见类注释）。
     *
     * @param query 当前需求描述（用户消息或模型自述）
     * @return 格式化的历史对话片段；无命中或异常返回空串
     */
    public String retrieveMemory(String query) {
        if (StrUtil.isBlank(query)) {
            return "";
        }
        try {
            // 查询重写：用户提问往往简短/指代不清（"那她后来怎么说？"），
            // 直接拿原文检索命中率低，先补全语义再检索
            String rewrittenQuery = queryRewriter.doQueryRewrite(query);
            SearchRequest request = SearchRequest.builder()
                    .query(rewrittenQuery)
                    .topK(properties.getTopK())
                    .similarityThreshold(properties.getSimilarityThreshold())
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
