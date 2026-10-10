package com.yupi.yuaiagent.tools;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class TerminalOperationToolTest {

    @Test
    void executeTerminalCommand() {
        TerminalOperationTool terminalOperationTool = new TerminalOperationTool();
        String command = "ls";
        String result = terminalOperationTool.executeTerminalCommand(command);
        Assertions.assertNotNull(result);
    }

    @Test
    @Timeout(20)
    void shouldCaptureStderr() {
        TerminalOperationTool terminalOperationTool = new TerminalOperationTool();
        String result = terminalOperationTool.executeTerminalCommand("echo err-visible 1>&2");
        assertTrue(result.contains("err-visible"), "stderr 内容应该出现在结果里，实际: " + result);
    }

    /**
     * 旧实现只读 stdout，这条命令会写满 stderr 管道（约 64KB）后永久阻塞在写上，
     * waitFor() 永不返回 —— 整条 Agent 线程就挂在这里。@Timeout 是防回归的兜底。
     */
    @Test
    @Timeout(20)
    void shouldNotHangWhenStderrIsFlooded() {
        TerminalOperationTool terminalOperationTool = new TerminalOperationTool();
        String result = terminalOperationTool.executeTerminalCommand("seq 1 100000 1>&2");
        assertTrue(result.contains("truncated"), "超长输出应该被截断而不是挂死，实际: " + result);
    }

    @Test
    @Timeout(20)
    void shouldTruncateButKeepDrainingHugeStdout() {
        TerminalOperationTool terminalOperationTool = new TerminalOperationTool();
        String result = terminalOperationTool.executeTerminalCommand("seq 1 100000");
        assertTrue(result.contains("truncated"), "超长输出应该被截断，实际: " + result);
    }

    @Test
    @Timeout(20)
    void shouldKillCommandOnTimeout() {
        // 1 秒超时，命令本身要睡 5 秒
        TerminalOperationTool terminalOperationTool = new TerminalOperationTool(1);
        long start = System.currentTimeMillis();
        String result = terminalOperationTool.executeTerminalCommand("sleep 5");
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(result.contains("timed out"), "应该报告超时，实际: " + result);
        assertTrue(elapsed < 5000, "超时后应该立刻返回，实际耗时 " + elapsed + "ms");
    }

    @Test
    @Timeout(20)
    void shouldReportNonZeroExitCode() {
        TerminalOperationTool terminalOperationTool = new TerminalOperationTool();
        String result = terminalOperationTool.executeTerminalCommand("exit 3");
        assertTrue(result.contains("exit code: 3"), "非零退出码应该被报告，实际: " + result);
    }
}
