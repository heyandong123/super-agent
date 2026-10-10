/*
package com.yupi.yuaiagent.tools;

import cn.hutool.core.io.FileUtil;
import com.yupi.yuaiagent.constant.FileConstant;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

*/
/**
 * 文件操作工具类（提供文件读写功能）
 *//*

public class FileOperationTool {

    private final String FILE_DIR = FileConstant.FILE_SAVE_DIR + "/file";

    @Tool(description = "Read content from a file")
    public String readFile(@ToolParam(description = "Name of a file to read") String fileName) {
        String filePath = FILE_DIR + "/" + fileName;
        try {
            return FileUtil.readUtf8String(filePath);
        } catch (Exception e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    @Tool(description = "Write content to a file")
    public String writeFile(@ToolParam(description = "Name of the file to write") String fileName,
                            @ToolParam(description = "Content to write to the file") String content
    ) {
        String filePath = FILE_DIR + "/" + fileName;

        try {
            // 创建目录
            FileUtil.mkdir(FILE_DIR);
            FileUtil.writeUtf8String(content, filePath);
            return "File written successfully to: " + filePath;
        } catch (Exception e) {
            return "Error writing to file: " + e.getMessage();
        }
    }
}
*/

package com.yupi.yuaiagent.tools;
import cn.hutool.core.io.FileUtil;
import com.yupi.yuaiagent.constant.FileConstant;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
/**
 * 文件操作工具类（为大模型提供读写本地文件的“物理外挂”）
 */
public class FileOperationTool {
    // 划定一个安全沙箱目录，防止大模型“越权”乱写文件破坏系统
    private final String FILE_DIR = FileConstant.FILE_SAVE_DIR + "/file";
    // TODO 1: 标记这是一个可以被大模型调用的工具，并准确描述它的功能
    // 核心原理：大模型看不懂 Java 代码，它只看这里的 description。框架会在底层把这段描述转成 JSON Schema 发给大模型。
    // 提示：加上 @Tool 注解，设置 description = "Read content from a file"
    @Tool(description = "Read content from a file")
    public String readFile(
            // TODO 2: 标记并描述方法的参数
            // 提示：加上 @ToolParam 注解，设置 description = "Name of a file to read"
            @ToolParam(description = "Name of a file to read") String fileName) {
        String filePath = FILE_DIR + "/" + fileName;
        try {
            // TODO 3: 执行本地文件读取
            // 核心原理：Hutool 是 Java 生态里极其好用的工具包，极大地简化了原生 I/O 流的复杂操作。
            // 提示：调用 FileUtil.readUtf8String(filePath) 并 return 返回值。
            return FileUtil.readUtf8String(filePath); // 替换此处代码
        } catch (Exception e) {
            // 面试考点：工具调用失败时，绝对不能直接抛出异常让程序崩溃，而是要把错误信息当作字符串返回给大模型，大模型看到错误后会尝试自我纠正。
            return "Error reading file: " + e.getMessage();
        }
    }
    // TODO 4: 标记写文件工具
    // 提示：加上 @Tool 注解，设置 description = "Write content to a file"
    @Tool(description = "Write content to a file")
    public String writeFile(
            // TODO 5: 标记文件名参数 (description: "Name of the file to write")
            @ToolParam(description = "Name of the file to write")
            String fileName,
            // TODO 6: 标记文件内容参数 (description: "Content to write to the file")
            @ToolParam(description = "Content to write to the file")
            String content) {
        String filePath = FILE_DIR + "/" + fileName;
        try {
            // TODO 7: 确保多级目录存在，防止报错
            // 提示：调用 FileUtil.mkdir(FILE_DIR)
            FileUtil.mkdir(FILE_DIR);
            // TODO 8: 将字符串内容写入文件
            // 提示：调用 FileUtil.writeUtf8String(content, filePath)
            FileUtil.writeUtf8String(content,filePath);
            return "File written successfully to: " + filePath;
        } catch (Exception e) {
            return "Error writing to file: " + e.getMessage();
        }
    }
}