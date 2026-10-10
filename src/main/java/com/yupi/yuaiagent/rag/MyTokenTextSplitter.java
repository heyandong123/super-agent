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
    private final LoveAppRagProperties loveAppRagProperties;

    MyTokenTextSplitter(LoveAppRagProperties loveAppRagProperties) {
        this.loveAppRagProperties = loveAppRagProperties;
    }

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
        LoveAppRagProperties.Splitter splitterProperties = loveAppRagProperties.getSplitter();
        TokenTextSplitter splitter = new TokenTextSplitter(
                splitterProperties.getChunkSize(),
                splitterProperties.getMinChunkSizeChars(),
                splitterProperties.getMinChunkLengthToEmbed(),
                splitterProperties.getMaxNumChunks(),
                splitterProperties.isKeepSeparator()
        );
        return splitter.apply(documents); // 替换此处代码
    }
}
