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
import java.util.List;
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
                // TODO 4: 构建 Markdown 解析配置 (MarkdownDocumentReaderConfig)
                // 核心原理: 这是 Spring AI 非常强大的一点，它内置了 Markdown 解析器。
                // 提示: 开启 Builder 模式，配置如下：
                // 1. .withHorizontalRuleCreateDocument(true) -> 遇到 Markdown 里的分隔线 (---) 就自动切分成新文档（这就是一种天然的“文本切分”）。
                // 2. .withIncludeCodeBlock(false) -> 忽略代码块。
                // 3. .withIncludeBlockquote(false) -> 忽略引用块。
                // 4. 使用 .withAdditionalMetadata() 将刚才提取的 filename 和 status 塞入元数据。
                // 5. .build() 结束。
                MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                        .withHorizontalRuleCreateDocument(true)
                        .withIncludeBlockquote(false)
                        .withIncludeCodeBlock(false)
                        .withAdditionalMetadata("filename",filename)
                        .withAdditionalMetadata("status",status)
                        .build(); // 替换此处代码
                // TODO 5: 实例化读取器并执行解析
                // 提示: new 一个 MarkdownDocumentReader 对象，将当前的 resource 和刚才建好的 config 传进去。
                // 然后调用它的 get() 方法，它会返回解析好的一组 Document 对象，最后使用 allDocuments.addAll() 将它们收集起来。
                List<Document> document = new MarkdownDocumentReader(resource, config).get();
                allDocuments.addAll(document);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return allDocuments;
    }
}
