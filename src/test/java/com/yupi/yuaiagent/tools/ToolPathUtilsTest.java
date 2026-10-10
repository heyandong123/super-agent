package com.yupi.yuaiagent.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具路径解析单元测试（验证绝对路径 bug 修复）
 */
@Tag("unit")
class ToolPathUtilsTest {

    private static final String BASE_DIR = "/project/tmp/file";

    @Test
    @DisplayName("相对路径：拼接沙箱目录")
    void testRelativePathJoinsBaseDir() {
        assertEquals(BASE_DIR + "/note.txt", ToolPathUtils.resolveFilePath(BASE_DIR, "note.txt"));
    }

    @Test
    @DisplayName("绝对路径：按原样使用，不再被沙箱目录污染")
    void testAbsolutePathUsedAsIs() {
        assertEquals("/tmp/yu-tool-test-output.txt",
                ToolPathUtils.resolveFilePath(BASE_DIR, "/tmp/yu-tool-test-output.txt"));
    }

    @Test
    @DisplayName("绝对路径：normalize 归一化")
    void testAbsolutePathNormalized() {
        assertEquals("/tmp/a.txt", ToolPathUtils.resolveFilePath(BASE_DIR, "/tmp/../tmp/a.txt"));
    }

    @Test
    @DisplayName("绝对路径判断：Unix 绝对路径")
    void testUnixAbsolutePathDetected() {
        String resolved = ToolPathUtils.resolveFilePath(BASE_DIR, "/var/log/app.log");
        assertTrue(resolved.startsWith("/var/log"), "Unix 绝对路径应保留原样");
    }
}
