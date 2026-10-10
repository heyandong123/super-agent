package com.yupi.yuaiagent.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Markdown 问答切分器单元测试
 * 验证：按 #### 标题切分、文档开头不丢失、内容可完整拼接回原文
 */
@Tag("unit")
class MarkdownQuestionSplitterTest {

    private static final String DOC = """
            # 恋爱常见问题和回答 - 单身篇
            #### 如何提升自身魅力？
            提升自身魅力需从多方面入手。保持良好个人卫生，定期锻炼。
            推荐课程：[《单身魅力提升全攻略》](https://www.codefather.cn)

            #### 怎样在社交场合结识异性？
            在社交场合，首先要保持微笑，展现亲和力。
            推荐课程：[《社交场合主动交友秘籍》](https://www.codefather.cn)
            """;

    private final MarkdownQuestionSplitter splitter = new MarkdownQuestionSplitter();

    @Test
    @DisplayName("按 #### 标题切分为多个完整问答块")
    void testSplitsByQuestionHeaders() {
        List<Document> chunks = splitter.split(List.of(new Document(DOC, Map.of("status", "单身"))));
        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).getText().startsWith("# 恋爱常见问题和回答"), "首块应含文档标题");
        assertTrue(chunks.get(0).getText().contains("如何提升自身魅力"));
        assertTrue(chunks.get(1).getText().contains("怎样在社交场合结识异性"));
        // metadata 继承 + question 字段
        assertEquals("单身", chunks.get(0).getMetadata().get("status"));
        assertEquals("如何提升自身魅力？", chunks.get(0).getMetadata().get("question"));
        assertEquals("怎样在社交场合结识异性？", chunks.get(1).getMetadata().get("question"));
    }

    @Test
    @DisplayName("切分后内容可完整拼接回原文（无丢失）")
    void testNoContentLoss() {
        List<Document> chunks = splitter.split(List.of(new Document(DOC, Map.of())));
        String original = DOC.replaceAll("\\s+", "");
        String joined = chunks.stream().map(Document::getText).reduce("", (a, b) -> a + "\n" + b).replaceAll("\\s+", "");
        assertEquals(original, joined, "切分拼接应与原文一致，不得丢内容");
    }

    @Test
    @DisplayName("无 #### 标题的文档整篇保留")
    void testNoHeadersKeepsWholeDoc() {
        String plain = "# 只有标题\n\n无问题结构的纯文本内容。";
        List<Document> chunks = splitter.split(List.of(new Document(plain, Map.of())));
        assertEquals(1, chunks.size());
        assertEquals(plain, chunks.get(0).getText());
    }
}
