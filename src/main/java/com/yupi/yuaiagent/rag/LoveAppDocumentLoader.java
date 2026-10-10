/*
package com.yupi.yuaiagent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
@Component
@Slf4j
public class LoveAppDocumentLoader {
    private ResourcePatternResolver resourcePatternResolver;
    public LoveAppDocumentLoader(ResourcePatternResolver resourcePatternResolver){
        this.resourcePatternResolver = resourcePatternResolver;
    }
    public List<Document> loadMarkdowns(){
        List<Document> allDocuments = new ArrayList<>();
        try {
            Resource[] resources = resourcePatternResolver.getResources("classpath:document/*.md");
            for(Resource resource : resources){
                String filename = resource.getFilename();
                String status = filename.substring(filename.length() - 6, filename.length() - 4);
                MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                        .withHorizontalRuleCreateDocument(true)
                        .withIncludeCodeBlock(false)
                        .withIncludeBlockquote(false)
                        .withAdditionalMetadata("filename", filename)
                        .withAdditionalMetadata("status", status)
                        .build();
                MarkdownDocumentReader markdownDocumentReader = new MarkdownDocumentReader(resource, config);
                allDocuments.addAll(markdownDocumentReader.get());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return allDocuments;
    }
}
*/
package com.yupi.yuaiagent.rag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/**
 * 恋爱大师文档加载器（负责从本地读取 Markdown 文件并解析为标准文档对象）
 */
@Component
@Slf4j
public class LoveAppDocumentLoader {
    // 【核心组件】Spring 提供的资源模式解析器，能根据通配符路径一次性扫描出多个文件
    private final ResourcePatternResolver resourcePatternResolver;
    // 构造器注入
    public LoveAppDocumentLoader(ResourcePatternResolver resourcePatternResolver){
        this.resourcePatternResolver = resourcePatternResolver;
    }
    public List<Document> loadMarkdowns(){
        List<Document> allDocuments = new ArrayList<>();
        try {
            // TODO 1: 扫描并加载所有的 Markdown 文件
            // 提示: 使用 resourcePatternResolver.getResources() 方法，传入路径 "classpath:document/*.md"
            Resource[] resources = resourcePatternResolver.getResources("classpath:document/*.md"); // 请替换为你编写的代码
            // TODO 2: 遍历处理每一个本地文件
            for(Resource resource : resources){
                // TODO 3: 提取文件名和业务状态标识
                // 核心原理: 教程里的文件命名有特定的规律（比如 "恋爱常见问题和回答 - 单身.md"）。
                // 这里利用 String 的 substring 方法，通过精准计算字符串长度，强行把 "单身"、"热恋" 等状态词抠出来，为后续的 Metadata 过滤做准备。
                // 提示: 调用 resource.getFilename() 获取完整文件名。接着用 substring(length - 6, length - 4) 提取状态。
                String filename = resource.getFilename(); // 替换此处代码
                String status = filename.substring(filename.length()-6,filename.length()-4); // 替换此处代码
                // 【纯文本加载】直接读取文件原文（utf-8），自行按 "#### 问题标题" 切分。
                // 不复用 MarkdownDocumentReader：它会把链接 URL 剥离、标题放 metadata、吞文档总标题，
                // 导致知识库内容不完整。纯文本 + 自切分保证原文逐字保留（链接当纯文本）。
                String rawText = resource.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
                String docTitle = filename.replace(".md", "").replace(" - ", " - ");
                List<Document> questionDocs = splitByQuestionHeaders(rawText, docTitle);
                for (Document doc : questionDocs) {
                    Map<String, Object> metadata = new HashMap<>(doc.getMetadata());
                    metadata.put("filename", filename);
                    metadata.put("status", status);
                    metadata.put("docTitle", docTitle);
                    allDocuments.add(new Document(doc.getText(), metadata));
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return allDocuments;
    }

    /**
     * 按 "#### 问题标题" 把整篇 markdown 切成问答对 Document。
     * 规则：文档开头（总标题 + 首个问题前的导语）并入首块；每块 = "问题标题 + 答案正文"，
     * 逐字保留原文（含链接、标点），保证知识库内容与源文档完全一致。
     */
    private List<Document> splitByQuestionHeaders(String rawText, String docTitle) {
        List<Document> result = new ArrayList<>();
        Pattern questionPattern = Pattern.compile("(?m)^\\s*####\\s+(.+)$");
        Matcher matcher = questionPattern.matcher(rawText);
        List<int[]> ranges = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        while (matcher.find()) {
            ranges.add(new int[]{matcher.start(), matcher.end()});
            titles.add(matcher.group(1).trim());
        }
        if (ranges.isEmpty()) {
            // 无 "####" 问题：整篇作为一块
            result.add(new Document(rawText.trim(), Map.of("question", docTitle)));
            return result;
        }
        for (int i = 0; i < ranges.size(); i++) {
            int start = ranges.get(i)[0];
            int end = (i + 1 < ranges.size()) ? ranges.get(i + 1)[0] : rawText.length();
            String chunk = rawText.substring(start, end).trim();
            // 首块并入文档总标题与开头导语
            if (i == 0 && ranges.get(0)[0] > 0) {
                String preamble = rawText.substring(0, ranges.get(0)[0]).trim();
                if (!preamble.isEmpty()) {
                    chunk = preamble + "\n\n" + chunk;
                }
            }
            result.add(new Document(chunk, Map.of("question", titles.get(i))));
        }
        return result;
    }
}
