package com.yupi.yuaiagent.memory;

/**
 * 单轮对话记录（history.jsonl 的一行）——单用户单会话连续模型（2026-10-11）的近场恢复源。
 * <p>
 * 只存「用户原话 + 给用户的最终答案」（与 L5 归档同口径：步骤轨迹检索价值低、token 成本高）。
 * {@code source} 记录来源（agentName / love_app），保留它便于将来按来源过滤，
 * 本轮检索不过滤（单用户单会话语义下所有来源本就是一条线）。
 *
 * @param ts        写入时间（ISO-8601 字符串，人可读、可排序）
 * @param user      用户原话
 * @param assistant 给用户的最终答案
 * @param source    来源标识（yuManus / love_app / ...）
 */
public record ConversationTurn(String ts, String user, String assistant, String source) {
}
