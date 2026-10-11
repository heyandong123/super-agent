package com.yupi.yuaiagent.context;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 上下文预算模式配置（2026-10 上下文重构 · 阶段 2）
 * <p>
 * 对标 Claude Code 的运行层上下文管理：不看"消息条数"，看"token 预算占比"；
 * 分两级动手——先清旧工具输出（零 LLM 成本，需要时能重取），不够再整体摘要（一次 LLM，不可逆）。
 * 参数含义与参照对象见 {@link ContextWindowManager} 的类注释与 docs/context-memory-refactor.md。
 * <p>
 * 唯一开关是 {@link #mode}：默认 {@code legacy}（完全保持旧行为，一行旧代码都不改），
 * 阶段 4 的 A/B 达标后才翻 {@code budget}；回滚 = 改回 legacy。
 * <p>
 * 刻意<b>没有</b> {@code yuai.context.estimator.*} 子配置：估算器
 * {@link ContextTokenEstimator} 的权重是编译期常量（无 Spring 依赖，便于离线单测），
 * 配了也不生效的键比没有键更糟——误差校准靠阶段 4 的「估算/实测比值」，不是靠调这些数。
 */
@Component
@ConfigurationProperties(prefix = "yuai.context")
@Data
public class ContextProperties {

    /** legacy（默认，旧行为）| budget（token 预算模式） */
    private String mode = "legacy";

    /** 模型可用上下文窗口（保守取值；DeepSeek 兼容接口 64K，取 32K 留足输出与工具 schema 空间） */
    private int windowTokens = 32768;

    /**
     * 常驻开销：system prompt + nextStepPrompt + 记忆注入 + 工具 schema。
     * full 模式实测常驻 9 个工具约 1465 token（见 ToolSchemaBudgetTest），
     * 加上系统提示与记忆块取整到 2200——它每轮都在，必须计入预算，否则会"以为还有空间，其实已经超了"。
     */
    private int staticOverheadTokens = 2200;

    /** 达到窗口比例触发整体压缩（对标 Claude Code 的 trigger 阈值思路） */
    private double compactTriggerRatio = 0.5;

    /** 压缩后保留的最近消息数（与旧 RECENT_MESSAGES_TO_KEEP 一致，保持行为可比） */
    private int compactKeepMessages = 6;

    /** 重注入的"最近使用过的文件"路径数上限（对标 Claude Code 压缩后重注入最近 5 个文件） */
    private int compactKeepRecentFiles = 5;

    /** 单次 run 最多压缩次数，超出只允许硬丢最老轮次（防"压了又满、满了又压"的抖动） */
    private int compactMaxPerRun = 3;

    /** 两次压缩之间至少间隔的步数（防 thrashing：小碎步压缩会反复打穿 prompt cache 前缀） */
    private int compactMinStepsBetween = 2;

    /** 摘要结果硬上限（字符）：摘要本身又撑爆上下文是最容易犯的二次错误 */
    private int summaryMaxChars = 4000;

    /** 摘要调用失败时保留"最近"多少字符（修旧实现截最老 1200 字符的 bug） */
    private int summaryFallbackKeepChars = 4000;

    /** 送摘要的每条消息渲染后的截断长度（含工具输出，旧实现用 getText() 把工具输出全丢了） */
    private int summaryInputItemMaxChars = 2000;

    private boolean microcompactEnabled = true;

    /** 达到窗口比例先做一级微压缩（比整体压缩更低：能不清就不清） */
    private double microcompactTriggerRatio = 0.35;

    /** 保留最近 N 条工具结果不清理（对标 Anthropic clear_tool_uses 的 keep=3） */
    private int microcompactKeepToolResults = 3;

    /**
     * 一次至少清够这么多 token 才动手（对标 Anthropic 的 clear_at_least）：
     * 只清掉一两百 token 却打穿了 prompt cache 前缀，是净亏。
     */
    private int microcompactClearAtLeastTokens = 1500;

    private boolean transcriptEnabled = true;

    /** 压缩前落盘的完整对话（相对于 ${user.dir}） */
    private String transcriptDir = "tmp/transcripts";

    /** 每个会话最多留几份 transcript（有界版 /resume：只留证据，不做恢复） */
    private int transcriptKeepPerConversation = 5;

    /**
     * 记忆块注入上限（字符）：常驻层要刻意做小——L2 画像改按用户跨会话累积后，
     * 不设限就是新的膨胀源（对标 Claude Code 把 MEMORY.md 前 200 行 / CLAUDE.md ≤200 行常驻的做法）。
     */
    private int memoryInjectMaxChars = 1200;

    /** 是否处于预算模式（唯一的模式判定入口，避免各处散落字符串比较） */
    public boolean isBudgetMode() {
        return "budget".equalsIgnoreCase(mode);
    }
}
