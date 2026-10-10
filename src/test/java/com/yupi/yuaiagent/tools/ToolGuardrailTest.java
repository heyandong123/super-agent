package com.yupi.yuaiagent.tools;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具护栏离线回归（2026-10 工具重构）：
 * - readFile 的 offset/limit 分页与截断提示（防大文件/超长行打爆上下文）
 * - downloadResource 的协议校验（杜绝 file:// 等本地协议）
 * 全部离线，不依赖网络、Spring 容器与真实密钥。
 */
@Tag("unit")
class ToolGuardrailTest {

    private final FileOperationTool fileTool = new FileOperationTool();

    private final ResourceDownloadTool downloadTool = new ResourceDownloadTool();

    @TempDir
    Path tempDir;

    private Path writeLines(String name, int lineCount) throws IOException {
        String content = IntStream.rangeClosed(1, lineCount)
                .mapToObj(i -> "line-" + i)
                .collect(Collectors.joining("\n"));
        Path file = tempDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void readFile_truncatesToDefault500Lines_withPagingHint() throws IOException {
        Path file = writeLines("big.txt", 600);
        String result = fileTool.readFile(file.toString(), null, null);
        assertTrue(result.startsWith("line-1\n"), "应从第 1 行开始");
        assertTrue(result.contains("line-500"), "默认应包含到第 500 行");
        assertFalse(result.contains("line-501\n"), "默认不应包含第 501 行正文");
        assertTrue(result.contains("[truncated: showing lines 1-500 of 600; call readFile again with offset=500 to continue]"),
                "应给出翻页提示，实际输出尾部：" + result.substring(Math.max(0, result.length() - 120)));
    }

    @Test
    void readFile_pagesWithOffsetAndLimit() throws IOException {
        Path file = writeLines("big.txt", 600);
        String result = fileTool.readFile(file.toString(), 500, null);
        assertTrue(result.startsWith("line-501"), "offset=500 应从第 501 行开始");
        assertTrue(result.endsWith("line-600"), "读到文件末尾应无截断提示");
        assertFalse(result.contains("[truncated"), "最后一页不应再有截断提示");
    }

    @Test
    void readFile_clampsLimitToHardMax2000() throws IOException {
        Path file = writeLines("huge.txt", 3000);
        String result = fileTool.readFile(file.toString(), 0, 99_999);
        assertTrue(result.contains("[truncated: showing lines 1-2000 of 3000; call readFile again with offset=2000 to continue]"),
                "limit 超过硬上限 2000 时应被截到 2000 行");
    }

    @Test
    void readFile_capsSingleHugeLineByChars() throws IOException {
        Path file = tempDir.resolve("oneline.txt");
        Files.writeString(file, "x".repeat(40_000), StandardCharsets.UTF_8);
        String result = fileTool.readFile(file.toString(), null, null);
        assertTrue(result.contains("[truncated: output exceeds 32000 characters; narrow the range with offset/limit]"),
                "超长单行应按字符数截断");
        assertTrue(result.length() < 32_500, "截断后总长度应接近 32000 字符，实际 " + result.length());
    }

    @Test
    void readFile_offsetBeyondEof_returnsActionableError() throws IOException {
        Path file = writeLines("small.txt", 3);
        String result = fileTool.readFile(file.toString(), 10, null);
        assertTrue(result.startsWith("Error reading file: offset 10 is beyond the end of the file (3 lines)"),
                "越界 offset 应返回带指引的错误（Error 前缀保证 act() 软错误识别生效）");
    }

    @Test
    void readFile_emptyFile_returnsPlaceholder() throws IOException {
        Path file = tempDir.resolve("empty.txt");
        Files.writeString(file, "", StandardCharsets.UTF_8);
        assertEquals("(file is empty)", fileTool.readFile(file.toString(), null, null));
    }

    @Test
    void downloadResource_rejectsNonHttpProtocols() {
        String result = downloadTool.downloadResource("file:///etc/passwd", "passwd.txt");
        assertTrue(result.startsWith("Error downloading resource: only http:// and https:// URLs are supported"),
                "非 http/https 协议必须被护栏拒绝，实际：" + result);
    }
}
