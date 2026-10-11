package com.yupi.yuaiagent.stream;

/**
 * SSE 文本增量发送失败（客户端断开 / 连接超时）后，用来中断当前流式 LLM 调用的专用异常（2026-10 流式轮）。
 * <p>
 * 为什么必须是「专用类型」而不是随便一个 RuntimeException：流式把发送频率从「每步一次」放大成
 * 「每 token 一次」，发送失败的异常如果和普通业务异常一样被 {@code think()} 的 catch 吞掉，
 * 后果是——状态已被置为 ERROR，但异常被吞，循环继续尝试后续步骤继续烧 token。
 * 所以 {@code think()} 与 {@code ReActAgent.step()} 都对这个类型<b>单独 catch 并原样上抛</b>，
 * 一路传到 {@code runStream} 的收尾逻辑，与改造前「process 发送失败即停」的语义完全对齐。
 * <p>
 * 抛出前调用方已做两件事：① {@code state = ERROR}（循环条件立刻失效，最多损失当前一次 LLM 调用）；
 * ② 清空文本增量出口（后续内部调用回落阻塞，不会再触发第二次失败）。
 */
public class StreamDisconnectedException extends RuntimeException {

    public StreamDisconnectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
