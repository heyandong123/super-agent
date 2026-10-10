/*
package com.yupi.yuaiagent.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

import java.util.List;

*/
/**
 * 自定义基于 Token 的切词器
 *//*

@Component
class MyTokenTextSplitter {
    public List<Document> splitDocuments(List<Document> documents) {
        TokenTextSplitter splitter = new TokenTextSplitter();
        return splitter.apply(documents);
    }

    public List<Document> splitCustomized(List<Document> documents) {
        TokenTextSplitter splitter = new TokenTextSplitter(200, 100, 10, 5000, true);
        return splitter.apply(documents);
    }
}
*/
package com.yupi.yuaiagent.rag;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;
import java.util.List;
/**
 * 自定义基于 Token 的切词器（将长文档切分为适合大模型消化的小语块）
 */
@Component
class MyTokenTextSplitter {
    /**
     * 基础切分策略（傻瓜式）
     * 核心原理: 使用 Spring AI 的默认配置。通常默认会将文本切分成大约 800 个 Token 的块。
     * 这种方式适合对检索精度要求不高、纯粹为了防止大模型报错的常规场景。
     */
    public List<Document> splitDocuments(List<Document> documents) {
        // TODO 1: 实例化默认的 TokenTextSplitter
        TokenTextSplitter splitter = new TokenTextSplitter(); // 替换此处代码
        // TODO 2: 执行切分动作
        // 提示: 调用 splitter.apply() 方法，把原始的长文档集合传进去，返回切碎后的新集合。
        return splitter.apply(documents); // 替换此处代码
    }
    /**
     * 自定义高级切分策略（精细化调优）
     * 核心原理: 针对特定的业务场景（比如恋爱大师的短问答），强制控制切分的粒度。
     */
    public List<Document> splitCustomized(List<Document> documents) {
        // TODO 3: 实例化自定义参数的 TokenTextSplitter
        // 【面试级硬核考点：这 5 个魔法数字分别代表什么？】
        // 1. defaultChunkSize (200): 每个文本块最多 200 个 Token。粒度切得很细，保证检索出来的内容极其聚焦。
        // 2. minChunkSizeChars (100): 兜底策略，切出来的块哪怕 Token 很少，但字符数不能少于 100，防止切出废话。
        // 3. minChunkLengthToEmbed (10): 如果切出来的一句话少于 10 个字符（比如“好的”、“再见”），就直接丢弃，不进向量数据库浪费空间。
        // 4. maxNumChunks (5000): 防御性编程，最多只允许切出 5000 块，防止有人上传恶意超大文件把内存干爆。
        // 5. keepSeparator (true): 切分时保留标点符号，保证文本再拼接时的可读性。
        // 提示: new 一个 TokenTextSplitter，依次传入 200, 100, 10, 5000, true。
        TokenTextSplitter splitter = new TokenTextSplitter(200,100,10,5000,true); // 替换此处代码
        // TODO 4: 执行并返回
        return splitter.apply(documents); // 替换此处代码
    }
}
