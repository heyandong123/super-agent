package com.yupi.yuaiagent.memory;

import com.yupi.yuaiagent.context.ContextProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一拼装记忆上下文（单用户单会话版，2026-10-11）。
 * <p>
 * 职责一：把画像 + 滚动摘要 + 会话元数据渲染成注入用的文本块（常驻层，刻意做小）；
 * 职责二：加载「近场恢复」用的最近 N 轮原文（{@link #loadHistoryTurns()}），
 * 两者一起构成「全量加载上一次会话历史」的落地形态：
 * <b>近场原文 + 全程摘要 + 全量归档检索</b>三段式（对标 Claude Code 的
 * 「压缩 = 摘要 + 保留近场」；真·逐字全量每轮重新付费，成本不可接受）。
 */
@Component
public class MemoryAssembler {

    private final SessionMetadataResolver sessionMetadataResolver;

    private final FileUserProfileRepository userProfileRepository;

    private final FileRecentSummaryRepository recentSummaryRepository;

    private final FileConversationHistoryRepository historyRepository;

    private final ContextProperties contextProperties;

    private final MemoryHistoryProperties historyProperties;

    public MemoryAssembler(SessionMetadataResolver sessionMetadataResolver,
                           FileUserProfileRepository userProfileRepository,
                           FileRecentSummaryRepository recentSummaryRepository,
                           FileConversationHistoryRepository historyRepository,
                           ContextProperties contextProperties,
                           MemoryHistoryProperties historyProperties) {
        this.sessionMetadataResolver = sessionMetadataResolver;
        this.userProfileRepository = userProfileRepository;
        this.recentSummaryRepository = recentSummaryRepository;
        this.historyRepository = historyRepository;
        this.contextProperties = contextProperties;
        this.historyProperties = historyProperties;
    }

    /**
     * 组装并渲染成注入用的文本块，带常驻层容量上限（2026-10 上下文重构 · 阶段 2）。
     * <p>
     * 为什么要有上限：记忆块是每一轮请求的常驻前缀，画像/摘要持续累积之后，
     * 不设限就是一个新的膨胀源——对标 Claude Code 把 MEMORY.md 前 200 行 / CLAUDE.md ≤200 行
     * 常驻的做法：每轮都在的东西，刻意做小。
     * <p>
     * 截断顺序天然对模型友好：渲染顺序是 [Session Metadata] → [User Profile] → [Recent Summary]，
     * 上限先削掉的正是最长、最可再生的部分（近期摘要），身份与偏好这类短字段排在前面。
     */
    public String buildPromptSection(String mode, String requestType) {
        String section = assemble(mode, requestType).toPromptSection();
        int maxChars = contextProperties.getMemoryInjectMaxChars();
        if (maxChars <= 0 || section.length() <= maxChars) {
            return section;
        }
        return section.substring(0, maxChars) + "\n…（记忆注入已截断）";
    }

    public MemoryContext assemble(String mode, String requestType) {
        SessionMetadata sessionMetadata = sessionMetadataResolver.resolve(mode, requestType);
        // 单用户单会话：画像与摘要各自全局一份，读取不再有任何键
        UserProfile userProfile = userProfileRepository.find();
        RecentSummary recentSummary = recentSummaryRepository.find();
        return MemoryContext.builder()
                .sessionMetadata(sessionMetadata)
                .userProfile(userProfile)
                .recentSummary(recentSummary)
                .build();
    }

    /**
     * 加载近场历史（最近 restore-turns 轮，已按单条字符上限截断）。
     * <p>
     * 由调用方（BaseAgent）在每次 run 起始转成 UserMessage/AssistantMessage 对注入。
     * <b>实现硬约束：返回的每一轮严格对应 2 条消息（不加包装 Head/分隔符）</b>——
     * 「默认 6 轮 + 记忆 SM + 用户消息 + nextStepPrompt = 15，不触发 legacy 压缩」的算术依赖它。
     */
    public List<ConversationTurn> loadHistoryTurns() {
        if (!historyProperties.isEnabled() || historyProperties.getRestoreTurns() <= 0) {
            return List.of();
        }
        List<ConversationTurn> turns = historyRepository.loadRecentTurns(historyProperties.getRestoreTurns());
        List<ConversationTurn> truncated = new ArrayList<>(turns.size());
        for (ConversationTurn turn : turns) {
            truncated.add(new ConversationTurn(
                    turn.ts(),
                    truncate(turn.user()),
                    truncate(turn.assistant()),
                    turn.source()));
        }
        return truncated;
    }

    /** 单条恢复截断：一条超长回答若原样回放，会在每次 run 的每一步 LLM 调用上重复付费 */
    private String truncate(String text) {
        int max = historyProperties.getMaxCharsPerMessage();
        if (text == null || max <= 0 || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "…（历史已截断）";
    }
}
