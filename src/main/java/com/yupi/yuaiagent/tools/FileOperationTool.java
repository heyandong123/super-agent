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
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 文件操作工具类（为大模型提供读写本地文件的“物理外挂”）
 */
@Component
public class FileOperationTool {
    // 划定一个安全沙箱目录，防止大模型“越权”乱写文件破坏系统。
    // 相对路径写入沙箱目录；绝对路径（如 /tmp/xxx）按原样使用，见 ToolPathUtils。
    private final String FILE_DIR = FileConstant.FILE_SAVE_DIR + "/file";

    /** 单次读取默认返回的最大行数（超出则截断并提示用 offset 翻页） */
    private static final int DEFAULT_READ_LINES = 500;

    /** 单次读取的硬上限：模型传再大的 limit 也会截到 2000 行 */
    private static final int MAX_READ_LINES = 2000;

    /** 单次读取的字符硬上限（约 32KB）：防止单行超长文件打爆上下文 */
    private static final int MAX_READ_CHARS = 32_000;

    // TODO 1: 标记这是一个可以被大模型调用的工具，并准确描述它的功能
    // 核心原理：大模型看不懂 Java 代码，它只看这里的 description。框架会在底层把这段描述转成 JSON Schema 发给大模型。
    // 提示：加上 @Tool 注解，设置 description = "Read content from a file"
    // 重构轮（2026-10）：描述升级为生产版（写明沙箱路径 / 分页 / 截断语义），原教学提示保留作为对照。
    @Tool(description = """
            Read a text file and return its content.
            Relative paths resolve under the sandbox directory tmp/file/; absolute paths are read as-is.
            Returns at most 500 lines per call. For longer files, call again with offset (0-based start
            line) and/or limit (max lines, up to 2000) to page through the content.
            Use writeFile to create or overwrite a file.
            """)
    public String readFile(
            // TODO 2: 标记并描述方法的参数
            // 提示：加上 @ToolParam 注解，设置 description = "Name of a file to read"
            @ToolParam(description = "File name or path to read (relative = under the sandbox directory tmp/file/)")
            String fileName,
            // 重构轮新增：分页参数（可选，required = false 时模型不传则为 null），大文件不再一次性灌进上下文
            @ToolParam(description = "Optional: 0-based line number to start reading from (default 0)",
                    required = false)
            Integer offset,
            @ToolParam(description = "Optional: maximum number of lines to return (default 500, max 2000)",
                    required = false)
            Integer limit) {
        String filePath = ToolPathUtils.resolveFilePath(FILE_DIR, fileName);
        int startLine = (offset == null || offset < 0) ? 0 : offset;
        int maxLines = (limit == null || limit <= 0) ? DEFAULT_READ_LINES : Math.min(limit, MAX_READ_LINES);
        try {
            // TODO 3: 执行本地文件读取
            // 核心原理：Hutool 是 Java 生态里极其好用的工具包，极大地简化了原生 I/O 流的复杂操作。
            // 提示：调用 FileUtil.readUtf8String(filePath) 并 return 返回值。
            // 重构轮：改用 readLines 支持 offset/limit 分页 + 截断提示（原来一次性 readUtf8String 全量返回，大文件打爆上下文）
            List<String> allLines = FileUtil.readLines(filePath, StandardCharsets.UTF_8);
            if (allLines.isEmpty()) {
                return "(file is empty)";
            }
            if (startLine >= allLines.size()) {
                return "Error reading file: offset " + startLine + " is beyond the end of the file ("
                        + allLines.size() + " lines); use offset=0 to read from the start";
            }
            int endLine = Math.min(allLines.size(), startLine + maxLines);
            String content = String.join("\n", allLines.subList(startLine, endLine));
            boolean charTruncated = content.length() > MAX_READ_CHARS;
            StringBuilder result = new StringBuilder();
            if (charTruncated) {
                result.append(content, 0, MAX_READ_CHARS)
                        .append("\n\n[truncated: output exceeds ").append(MAX_READ_CHARS)
                        .append(" characters; narrow the range with offset/limit]");
            } else {
                result.append(content);
            }
            if (endLine < allLines.size() && !charTruncated) {
                result.append("\n\n[truncated: showing lines ").append(startLine + 1).append("-").append(endLine)
                        .append(" of ").append(allLines.size())
                        .append("; call readFile again with offset=").append(endLine).append(" to continue]");
            }
            return result.toString();
        } catch (Exception e) {
            // 面试考点：工具调用失败时，绝对不能直接抛出异常让程序崩溃，而是要把错误信息当作字符串返回给大模型，大模型看到错误后会尝试自我纠正。
            return "Error reading file: " + e.getMessage();
        }
    }
    // TODO 4: 标记写文件工具
    // 提示：加上 @Tool 注解，设置 description = "Write content to a file"
    // 重构轮（2026-10）：描述写明"整文件覆盖"与父目录自动创建，减少模型误用（追加场景需先 readFile 再整体写回）。
    @Tool(description = """
            Create or overwrite a file with the given content (the whole file is replaced).
            Relative paths resolve under the sandbox directory tmp/file/; absolute paths are written as-is.
            Parent directories are created automatically. Returns the written file path.
            """)
    public String writeFile(
            // TODO 5: 标记文件名参数 (description: "Name of the file to write")
            @ToolParam(description = "File name or path to write (relative = under the sandbox directory tmp/file/)")
            String fileName,
            // TODO 6: 标记文件内容参数 (description: "Content to write to the file")
            @ToolParam(description = "Full content to write to the file (replaces any existing content)")
            String content) {
        String filePath = ToolPathUtils.resolveFilePath(FILE_DIR, fileName);
        try {
            // TODO 7: 确保目标文件的多级目录存在，防止报错
            Path parent = Paths.get(filePath).getParent();
            if (parent != null) {
                FileUtil.mkdir(parent.toString());
            }
            // TODO 8: 将字符串内容写入文件
            // 提示：调用 FileUtil.writeUtf8String(content, filePath)
            FileUtil.writeUtf8String(content,filePath);
            return "File written successfully to: " + filePath;
        } catch (Exception e) {
            return "Error writing to file: " + e.getMessage();
        }
    }
}