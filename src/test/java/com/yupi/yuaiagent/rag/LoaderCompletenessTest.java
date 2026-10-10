package com.yupi.yuaiagent.rag;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯文本 loader 完整性验证：库拼接内容应与源文档逐字一致（含链接、标题）
 */
@Tag("unit")
class LoaderCompletenessTest {

    private final LoveAppDocumentLoader loader =
            new LoveAppDocumentLoader(new PathMatchingResourcePatternResolver());

    @Test
    void testFullCoverage() throws Exception {
        List<Document> docs = loader.loadMarkdowns();
        assertEquals(15, docs.size(), "3 篇 × 5 问 = 15 块");
        for (String status : new String[]{"单身", "已婚", "恋爱"}) {
            String joined = docs.stream()
                    .filter(d -> status.equals(d.getMetadata().get("status")))
                    .map(Document::getText)
                    .reduce("", (a, b) -> a + "\n" + b)
                    .replaceAll("\\s+", "");
            String filename = status.equals("单身") ? "恋爱常见问题和回答 - 单身篇.md"
                    : status.equals("已婚") ? "恋爱常见问题和回答 - 已婚篇.md" : "恋爱常见问题和回答 - 恋爱篇.md";
            String src = new String(Files.readAllBytes(Path.of("src/main/resources/document/" + filename)),
                    StandardCharsets.UTF_8).replaceAll("\\s+", "");
            assertEquals(src, joined, status + " 篇拼接应与源文档逐字一致（含链接）");
            assertTrue(joined.contains("codefather"), status + " 篇应保留链接 URL");
        }
        // question metadata 完整
        assertTrue(docs.stream().allMatch(d -> d.getMetadata().get("question") != null));
        System.out.println("✅ loader 完整覆盖：15 块、链接保留、question metadata 齐全");
    }
}
