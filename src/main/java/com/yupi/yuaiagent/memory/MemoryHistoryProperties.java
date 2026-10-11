package com.yupi.yuaiagent.memory;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 单会话历史日志配置（2026-10-11 单用户单会话重构）
 * <p>
 * <b>为什么需要 history.jsonl 这一层</b>：画像（稳定事实）与摘要（滚动概览）都是有损的，
 * 关闭页面 / 换 chatId 后上一段对话的逐字内容全部丢失。对标 Claude Code 的单会话连续模型
 * （{@code --continue} 从 transcript 恢复会话）：每次 run 起始恢复最近 N 轮原文，
 * 让"新对话接着上一段继续"成为默认行为。
 * <p>
 * <b>为什么默认 restore-turns=6</b>（不是随便拍的数）：legacy 压缩的触发条件是消息数严格
 * {@code > 15}，恢复 6 轮 = 12 条消息，加上记忆 SystemMessage + 用户消息 + nextStepPrompt
 * 恰好 15 条——纯聊天与单步任务<b>不因恢复触发压缩</b>；7 轮则首查即 16 条、必然触发。
 * 这个算术有钉死单测（BaseAgentMemoryRestoreTest），改默认值前先看它。
 * <p>
 * {@code max-chars-per-message} 是恢复内容进入上下文的唯一边界：不截断的话，
 * 一条超长回答会在每次 run 起始被重新灌进上下文（每步 LLM 调用都重新付费）。
 */
@Component
@ConfigurationProperties(prefix = "yuai.memory.history")
@Data
public class MemoryHistoryProperties {

    /** 总开关：false 时不写也不恢复（评测隔离/回滚用） */
    private boolean enabled = true;

    /** 每次 run 起始恢复的最近轮数；0=不恢复。默认 6 与 legacy 压缩阈值 15 对齐（见类注释） */
    private int restoreTurns = 6;

    /** 文件容量上限（轮）：超出后原子裁剪，只留最近 max-turns 轮 */
    private int maxTurns = 200;

    /** 单条消息恢复时的字符上限，超出补「…（历史已截断）」 */
    private int maxCharsPerMessage = 1200;
}
