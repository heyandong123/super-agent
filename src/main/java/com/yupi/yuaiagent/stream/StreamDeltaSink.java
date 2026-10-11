package com.yupi.yuaiagent.stream;

import java.io.IOException;

/**
 * 文本增量出口（2026-10 流式轮）
 * <p>
 * 把「增量文本往哪发」与 Agent 解耦：生产链路上 {@code BaseAgent.runStream} 安装的是
 * SSE 发送器（发 {@code delta} 事件）；离线单测里安装的是计数器，同一份 Agent 代码两条路复用，
 * 不必起 HTTP 就能验证「流式真的发生过、顺序与内容对不对」。
 * <p>
 * 只在 {@code runStream}（有人在看）时安装——{@code run()}（评测、多智能体子 Agent）不安装，
 * 于是子 Agent 与评测链路天然保持阻塞调用，零行为变化。
 */
@FunctionalInterface
public interface StreamDeltaSink {

    /**
     * 发送一段增量文本。
     *
     * @param scope 文本所属阶段：{@code think}（步骤思考叙述）| {@code final}（最终答案草稿）
     * @param text  本次到达的增量（非空；调用方已过滤空串）
     * @throws IOException 客户端已断开等发送失败——由调用方转成中断当前流式调用的信号
     */
    void onTextDelta(String scope, String text) throws IOException;
}
