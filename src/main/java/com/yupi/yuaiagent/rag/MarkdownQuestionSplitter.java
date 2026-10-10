package com.yupi.yuaiagent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 问答结构切分器（按 "#### 问题标题" 切分）
 * <p>
 * 设计理由：恋爱知识文档是"#### 问题标题 + 答案正文"的问答对结构。
 * 固定 token 切分（TokenTextSplitter）会切断问答对、且已知会丢失文档开头；
 * 按 #### 标题切分后，每块 = 一个完整问答，检索命中整块、答案完整。
 * <p>
 * 切分规则：
 * 1. 文档开头（# 总标题 + 首个 #### 之前的内容）并入第一个问题块，保证不丢内容
 * 2. 每块保留问题标题到 metadata.question，可用于标题级检索
 * 3. 继承输入 Document 的原有 metadata（如 filename、status）
 */
@Component
@Slf4j
public class MarkdownQuestionSplitter {

    /**
     * 匹配 "#### 问题标题" 行（行首，可有前导空白）
     */
    private static final Pattern QUESTION_HEADER = Pattern.compile("(?m)^\\s*####\\s+(.+)$");

    public List<Document> split(List<Document> documents) {
        List<Document> result = new ArrayList<>();
        for (Document document : documents) {
            result.addAll(splitOne(document));
        }
        return result;
    }

    private List<Document> splitOne(Document document) {
        String text = document.getText();
        Map<String, Object> baseMetadata = document.getMetadata();
        List<Document> chunks = new ArrayList<>();

        Matcher matcher = QUESTION_HEADER.matcher(text);
        List<int[]> questionRanges = new ArrayList<>();
        List<String> questionTitles = new ArrayList<>();
        while (matcher.find()) {
            questionRanges.add(new int[]{matcher.start(), matcher.end()});
            questionTitles.add(matcher.group(1).trim());
        }

        if (questionRanges.isEmpty()) {
            // 无 #### 标题：整篇作为一块（保留原标题）
            Map<String, Object> metadata = new HashMap<>(baseMetadata);
            metadata.put("question", extractDocTitle(text));
            chunks.add(new Document(text, metadata));
            return chunks;
        }

        // 每块：[当前标题行起, 下一个标题行止)；首块并入文档开头（标题+首个问题前的内容）
        for (int i = 0; i < questionRanges.size(); i++) {
            int start = questionRanges.get(i)[0];
            int end = (i + 1 < questionRanges.size()) ? questionRanges.get(i + 1)[0] : text.length();
            Map<String, Object> metadata = new HashMap<>(baseMetadata);
            metadata.put("question", questionTitles.get(i));
            chunks.add(new Document(text.substring(start, end).trim(), metadata));
        }

        // 文档开头（总标题 + 首个 #### 之前）：并入首块头部，保证开头内容不丢
        int firstHeaderStart = questionRanges.get(0)[0];
        if (firstHeaderStart > 0) {
            String preamble = text.substring(0, firstHeaderStart).trim();
            if (!preamble.isEmpty()) {
                Document firstChunk = chunks.get(0);
                chunks.set(0, new Document(
                        preamble + "\n\n" + firstChunk.getText(),
                        firstChunk.getMetadata()));
            }
        }
        return chunks;
    }

    private String extractDocTitle(String text) {
        String firstLine = text.split("\n", 2)[0].trim();
        return firstLine.replaceFirst("^#+\\s*", "");
    }
}
