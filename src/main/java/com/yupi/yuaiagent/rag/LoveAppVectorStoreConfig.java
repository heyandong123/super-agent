/*
package com.yupi.yuaiagent.rag;

import jakarta.annotation.Resource;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

*/
/**
 * 恋爱大师向量数据库配置（初始化基于内存的向量数据库 Bean）
 *//*

@Configuration
public class LoveAppVectorStoreConfig {

    @Resource
    private LoveAppDocumentLoader loveAppDocumentLoader;

    @Resource
    private MyTokenTextSplitter myTokenTextSplitter;

    @Resource
    private MyKeywordEnricher myKeywordEnricher;

    @Bean
    VectorStore loveAppVectorStore(EmbeddingModel dashscopeEmbeddingModel) {
        SimpleVectorStore simpleVectorStore = SimpleVectorStore.builder(dashscopeEmbeddingModel).build();
        // 加载文档
        List<Document> documentList = loveAppDocumentLoader.loadMarkdowns();
        // 自主切分文档
//        List<Document> splitDocuments = myTokenTextSplitter.splitCustomized(documentList);
        // 自动补充关键词元信息
        List<Document> enrichedDocuments = myKeywordEnricher.enrichDocuments(documentList);
        simpleVectorStore.add(enrichedDocuments);
        return simpleVectorStore;
    }
}
*/

// package 定义了当前类所在的“文件夹”路径，用于组织代码，防止类名冲突。

package com.yupi.yuaiagent.rag;



// import 用于引入其他包里的类，这样你在下面就可以直接写类名，而不需要每次都写全限定名（比如 org.springframework.ai.document.Document）。

// 带有 jakarta 或 org.springframework 的通常是框架提供的基础能力。

import jakarta.annotation.Resource;

import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.document.Document;

import org.springframework.ai.embedding.EmbeddingModel;

import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.SimpleVectorStoreContent;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;
/**
 * 恋爱大师向量数据库配置（初始化基于内存的向量数据库 Bean）
 * * @Configuration 是 Spring 框架的核心注解之一。
 * 它的作用是告诉 Spring：“嘿，这是一个配置类，里面包含了一些需要你帮我创建和管理的对象的图纸。”
 * 在项目启动时，Spring 会自动扫描到这个类，并执行里面带有 @Bean 注解的方法。
 */
@Configuration
public class LoveAppVectorStoreConfig{
    /**
     * @Resource 也是 Spring 的核心注解（依赖注入/DI）。
     * 它的意思是：“我需要一个 LoveAppDocumentLoader 类型的对象，但我不想自己 new 它。Spring 请你帮我找一个现成的，自动塞到这个变量里。”
     * LoveAppDocumentLoader 的作用通常是去读取本地或远端的 Markdown/TXT 等业务文档。
     */
    @Resource
    private LoveAppDocumentLoader loveAppDocumentLoader;
    /**
     * 同上，自动注入自定义的文本切分器。
     * 为什么需要切分？因为大模型（LLM）有上下文长度限制（Token 限制），不能一次性把几十万字的书全塞进去。
     * 所以需要把它切成一段段的“语块”（Chunk）。
     */
    @Resource
    private MyTokenTextSplitter myTokenTextSplitter;
    /**
     * 同上，自动注入自定义的关键词增强器。
     * 作用：在切分好的文本片段上，利用算法或规则补充一些关键词（比如提取这段话的摘要或核心词），
     * 这样在后续向量检索时，命中率会更高。
     */
    @Resource
    private MyKeywordEnricher myKeywordEnricher;
    /**
     * @Bean 配合类上的 @Configuration 使用。
     * 它的意思是：“请执行这个方法，并把这个方法返回的对象（VectorStore）放到 Spring 的容器里当做一个全局单例（Bean）保存起来。”
     * 这样，以后你在项目的任何地方，只要加上 @Resource private VectorStore vectorStore; 就可以直接使用它了。
     * * 方法参数 `EmbeddingModel dashscopeEmbeddingModel`：
     * 这里 Spring 极其智能，它发现这个方法需要一个 EmbeddingModel，它就会自动去容器里找一个给你传进来。
     * EmbeddingModel 的作用是调用大模型的 Embedding 接口，把人类的自然语言（文本）转换成计算机能理解的稠密浮点数数组（向量/Vector）。
     */
    @Bean
    VectorStore loveAppVectorStore(EmbeddingModel dashscopeEmbeddingModel) {
        // =========================================================================
        // TODO 1: 构建基于内存的向量数据库 (SimpleVectorStore)
        // 提示: SimpleVectorStore 提供了一个 builder() 方法，你需要把传进来的 dashscopeEmbeddingModel 丢给 builder，然后调用 build() 方法生成实例。
        // 代码写在这里：
        SimpleVectorStore simpleVectorStore = SimpleVectorStore.builder(dashscopeEmbeddingModel).build();
        // =========================================================================
        // TODO 2: 加载原始文档
        // 提示: 使用上面注入的 loveAppDocumentLoader 属性，调用它的 loadMarkdowns() 方法。
        // 它会返回一个 List<Document> 类型的集合，请用一个变量接收它。
        // (Document 是 Spring AI 中用于统一表示文档的类，里面包含文本内容 text 和元数据 metadata)
        // 代码写在这里：
        List<Document> documents = loveAppDocumentLoader.loadMarkdowns();
        // =========================================================================
        // TODO 3: (可选/被注释的步骤) 自主切分文档
        // 提示: 原代码中这一步被注释掉了，可能是目前加载的 Markdown 文件已经足够小，或者在 Loader 阶段已经切分过了。
        // 了解即可：通常会调用 myTokenTextSplitter.splitCustomized(刚刚加载的文档集合)
        List<Document> splitDocuments = myTokenTextSplitter.splitCustomized(documents);
        // =========================================================================
        // TODO 4: 自动补充关键词元信息 (Enrich)
        // 提示: 使用上面注入的 myKeywordEnricher，调用它的 enrichDocuments() 方法，把 TODO 2 中得到的文档集合传进去。
        // 它会返回一个新的、被增强过的 List<Document> 集合，请用一个变量接收。
        // 代码写在这里：
        List<Document> enrichedDocuments = myKeywordEnricher.enrichDocuments(documents);
        // =========================================================================
        // TODO 5: 将处理好的文档存入向量数据库
        // 提示: 拿到 TODO 1 中构建的 SimpleVectorStore 对象，调用它的 add() 方法，把 TODO 4 中增强后的文档集合放进去。
        // (在调用 add() 的底层，SimpleVectorStore 会自动调用 EmbeddingModel，把这些文档变成向量，然后存在内存里)
        // 代码写在这里：
        simpleVectorStore.add(enrichedDocuments);
        // =========================================================================
        // TODO 6: 返回最终构建好的向量数据库实例
        // 提示: return 你的 SimpleVectorStore 对象，把它交给 Spring 容器管理。
        // 代码写在这里：
        return simpleVectorStore; // 请将 null 替换为你的变量名
    }

}