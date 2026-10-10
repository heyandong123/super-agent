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
/**
 * 终端操作工具（赋予大模型在本地系统执行命令的权限）
 */
public class TerminalOperationTool {
    // TODO 1: 添加大模型工具注解
    // 提示: 使用 @Tool(description = "Execute a command in the terminal")
    @Tool(description = "Execute a command in the terminal")
    public String executeTerminalCommand(
            // TODO 2: 描述命令参数
            // 提示: 使用 @ToolParam(description = "Command to execute in the terminal")
            @ToolParam(description = "Command to execute in the terminal")
            String command) {
        StringBuilder output = new StringBuilder();
        try {
            // TODO 3: 构建进程执行器 (ProcessBuilder)
            // 核心原理: JVM 向底层操作系统派生子进程执行命令。已适配 macOS。
            // 提示: new 一个 ProcessBuilder，传入 "sh", "-c", command
            ProcessBuilder builder = new ProcessBuilder("sh","-c",command); // 替换此处代码
            // TODO 4: 启动子进程
            // 提示: 调用 builder.start() 启动进程
            Process process = builder.start(); // 替换此处代码
            // TODO 5: 读取终端标准输出 (Standard Output)
            // 核心原理: 将终端打印的字符流抓取回 Java 内存，否则大模型无法得知命令执行结果。
            // 提示: 用 BufferedReader 包装 process.getInputStream()，并通过 while 循环逐行 append 到 output。
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            // TODO 6: 阻塞等待并获取退出码
            // 核心原理: process.waitFor() 返回 0 表示成功，非 0 表示命令执行出错。
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