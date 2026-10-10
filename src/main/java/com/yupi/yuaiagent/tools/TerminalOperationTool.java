/*
package com.yupi.yuaiagent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

*/
/**
 * 终端操作工具
 *//*

public class TerminalOperationTool {

    @Tool(description = "Execute a command in the terminal")
    public String executeTerminalCommand(@ToolParam(description = "Command to execute in the terminal") String command) {
        StringBuilder output = new StringBuilder();
        try {
            ProcessBuilder builder = new ProcessBuilder("cmd.exe", "/c", command);
//            Process process = Runtime.getRuntime().exec(command);
            Process process = builder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                output.append("Command execution failed with exit code: ").append(exitCode);
            }
        } catch (IOException | InterruptedException e) {
            output.append("Error executing command: ").append(e.getMessage());
        }
        return output.toString();
    }
}
*/

package com.yupi.yuaiagent.tools;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
/**
 * 终端操作工具（赋予大模型在本地系统执行命令的权限）
 */
public class TerminalOperationTool {

    private static final int MAX_OUTPUT_LINES = 50;

    /**
     * 单条命令最长执行时间（秒）。超时强杀：命令挂住时外层 Agent 只能放弃等待，
     * 子进程却会一直留在系统里占着管道。
     */
    private static final long DEFAULT_TIMEOUT_SECONDS = 30;

    /** 等待读取线程收尾的上限（毫秒）。进程被强杀后管道即关闭，读线程会立刻拿到 EOF。 */
    private static final long READER_JOIN_MILLIS = 1000;

    private final long timeoutSeconds;

    public TerminalOperationTool() {
        this(DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * 包级可见：让测试注入一个很短的超时来验证超时路径，不必真的等 30 秒。
     */
    TerminalOperationTool(long timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    @Tool(description = """
            Execute a shell command in the terminal. ONLY use this for system-level tasks
            such as installing software, checking the environment, or running shell commands
            the user explicitly asked for. For reading/writing files or generating PDFs,
            use the dedicated tools (readFile/writeFile/generatePDF) instead.
            """)
    public String executeTerminalCommand(
            @ToolParam(description = "Command to execute in the terminal")
            String command) {
        // StringBuffer 而不是 StringBuilder：读取在另一个线程里并发写入
        StringBuffer output = new StringBuffer();
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder("sh","-c",command);
            // stderr 并入 stdout：只需要消费一个流，就不会出现「stderr 管道写满、子进程永久阻塞」
            // ——旧实现只读 stdout，命令往 stderr 写超过管道缓冲区（约 64KB）就会挂死
            builder.redirectErrorStream(true);
            process = builder.start();

            Thread outputReader = startOutputReader(process, output);

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                killProcessTree(process);
                outputReader.join(READER_JOIN_MILLIS);
                output.append("Command timed out after ").append(timeoutSeconds)
                        .append(" seconds and was killed.\n");
                return output.toString();
            }
            // 正常退出：读线程此时已读到 EOF，等它把最后几行写完再取结果
            outputReader.join(READER_JOIN_MILLIS);

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                output.append("Command failed with exit code: ").append(exitCode);
            }
        } catch (InterruptedException e) {
            // 恢复中断标记：上层靠它感知「这次调用是被取消的」，而不是把中断吞掉
            Thread.currentThread().interrupt();
            if (process != null) {
                killProcessTree(process);
            }
            output.append("Error executing command: interrupted");
        } catch (IOException e) {
            output.append("Error executing command: ").append(e.getMessage());
        }
        return output.toString();
    }

    /**
     * 用独立线程边读边丢，必须与 waitFor 并发进行。
     * 若先同步读完再 waitFor，一个只睡不输出的命令会让 readLine 一直阻塞，
     * 超时判断根本没机会执行（读阻塞是不可中断的）。
     */
    private Thread startOutputReader(Process process, StringBuffer output) {
        Thread outputReader = new Thread(() -> {
            int lineCount = 0;
            boolean truncated = false;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (lineCount < MAX_OUTPUT_LINES) {
                        output.append(line).append("\n");
                        lineCount++;
                    } else {
                        // 截断之后继续读并丢弃：不把管道排空，子进程照样会阻塞在写上，waitFor 永不返回
                        truncated = true;
                    }
                }
            } catch (IOException e) {
                output.append("Error reading command output: ").append(e.getMessage()).append("\n");
            }
            if (truncated) {
                output.append("...(output truncated at ").append(MAX_OUTPUT_LINES).append(" lines)\n");
            }
        }, "terminal-output-reader");
        // 守护线程：万一某个子进程顽固持有管道，也不会阻止 JVM 退出
        outputReader.setDaemon(true);
        outputReader.start();
        return outputReader;
    }

    /**
     * sh -c 常把真正干活的进程放在子进程里（如 sh -c "sleep 30"），
     * 只杀 sh 会留下孤儿进程继续占着管道，所以先杀后代再杀本体。
     */
    private void killProcessTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
