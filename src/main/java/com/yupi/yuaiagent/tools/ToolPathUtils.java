package com.yupi.yuaiagent.tools;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 工具路径解析工具类
 * <p>
 * 规则：相对路径拼接沙箱目录；绝对路径按原样使用（并做 normalize 归一化）。
 * 之所以允许绝对路径：模型按用户指令写 /tmp/xxx 是合理诉求，且项目已提供终端工具
 * （具备任意路径能力），沙箱的约束边界以"模型意图 + 用户可见路径"为准。
 */
public final class ToolPathUtils {

    private ToolPathUtils() {
    }

    /**
     * 解析工具文件路径
     *
     * @param baseDir  沙箱基础目录（相对路径时拼接）
     * @param fileName 模型传入的文件名/路径
     * @return 解析后的完整路径
     */
    public static String resolveFilePath(String baseDir, String fileName) {
        Path path = Paths.get(fileName);
        if (path.isAbsolute()) {
            return path.normalize().toString();
        }
        return baseDir + "/" + fileName;
    }
}
