/*
package com.yupi.yuaiagent.rag;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.rag.DashScopeDocumentRetriever;
import com.alibaba.cloud.ai.dashscope.rag.DashScopeDocumentRetrieverOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

*/
/**
 * 自定义基于阿里云知识库服务的 RAG 增强顾问
 *//*

@Configuration
@Slf4j
public class LoveAppRagCloudAdvisorConfig {

    @Value("${spring.ai.dashscope.api-key}")
    private String dashScopeApiKey;

    @Bean
    public Advisor loveAppRagCloudAdvisor() {
        DashScopeApi dashScopeApi = DashScopeApi.builder()
                .apiKey(dashScopeApiKey)
                .build();
        final String KNOWLEDGE_INDEX = "恋爱大师";
        DocumentRetriever dashScopeDocumentRetriever = new DashScopeDocumentRetriever(dashScopeApi,
                DashScopeDocumentRetrieverOptions.builder()
                        .withIndexName(KNOWLEDGE_INDEX)
                        .build());
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(dashScopeDocumentRetriever)
                .build();
    }
}
*/

// package 定义了当前类所在的包路径
package com.yupi.yuaiagent.rag;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.rag.DashScopeDocumentRetriever;
import com.alibaba.cloud.ai.dashscope.rag.DashScopeDocumentRetrieverOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 自定义基于阿里云知识库服务的 RAG 增强顾问 (Cloud RAG)
 * * @Configuration: 声明这是一个配置类，Spring 启动时会加载它。
 * * @Slf4j: Lombok 提供的神仙注解，加上它之后，你就可以在这个类里直接使用 log.info() 打印日志，省去了手写 Logger 实例的麻烦。
 */
@Configuration
@Slf4j
public class LoveAppRagCloudAdvisorConfig {

    /**
     * 【重点提示】：@Value 注解 (属性注入)
     * 在上几个类中，我们用 @Resource 或构造函数去注入“对象”(Bean)。
     * 但 @Value 是用来注入“配置数据”的。
     * 它的意思是：“请去 application.yml 或 application.properties 文件中，
     * 找到 spring.ai.dashscope.api-key 这个配置项，把它对应的字符串值赋值给下面的变量。”
     * 这样可以避免把敏感的 API Key 硬编码写死在代码里。
     */
    @Value("${spring.ai.dashscope.api-key}")
    private String dashScopeApiKey;

    /**
     * 创建一个 RAG Advisor（顾问/拦截器），并把它交给 Spring 管理。
     * 之前我们在 RagChatService 里看到了一个 QuestionAnswerAdvisor，那个是 Spring AI 默认的基础版。
     * 这里我们要用 RetrievalAugmentationAdvisor，它是更灵活、更高级的 RAG 拦截器。
     */
    @Bean
    public Advisor loveAppRagCloudAdvisor() {

        // =========================================================================
        // TODO 1: 构建云服务 API 客户端 (DashScopeApi)
        // 核心原理：要调用阿里云的接口，首先要有一个合法的客户端凭证。
        // 提示：使用 DashScopeApi.builder() 开启链式调用，
        // 使用 .apiKey(dashScopeApiKey) 将上面注入的秘钥塞进去，
        // 最后 .build() 生成实例，用一个变量（如 dashScopeApi）接收。
        // =========================================================================
        DashScopeApi dashScopeApi = DashScopeApi.builder().apiKey(dashScopeApiKey).build();

        // =========================================================================
        // TODO 2: 定义云端知识库的索引名称
        // 核心原理：你在阿里云百炼平台上可能创建了多个知识库（比如“恋爱大师”、“Java面试题”）。
        // 我们需要告诉代码去查哪一个。
        // 提示：定义一个 final String 类型的常量（比如 KNOWLEDGE_INDEX），赋值为 "恋爱大师"（与云端设置的名字一致）。
        // =========================================================================
        final String KNOWLEDGE_INDEX = "恋爱大师";

        // =========================================================================
        // TODO 3: 构建文档检索器 (DocumentRetriever)
        // 核心原理：DocumentRetriever 是 Spring AI 抽象出来的接口，表示“能查文档的组件”。
        // 这里我们要实例化它的具体实现类：DashScopeDocumentRetriever。
        // 提示：new 一个 DashScopeDocumentRetriever 对象。
        // 它的构造函数需要两个参数：
        // 参数一：你刚才在 TODO 1 中创建的 dashScopeApi 对象。
        // 参数二：检索选项配置。你可以通过 DashScopeDocumentRetrieverOptions.builder()
        //         配合 .withIndexName(你 TODO 2 定义的常量名) 然后 .build() 来生成这个配置对象。
        // =========================================================================
        DashScopeDocumentRetriever dashScopeDocumentRetriever = new DashScopeDocumentRetriever(dashScopeApi,
                DashScopeDocumentRetrieverOptions.builder().
                withIndexName(KNOWLEDGE_INDEX).
                build());

        // =========================================================================
        // TODO 4: 构建并返回最终的 RAG 拦截器
        // 核心原理：RetrievalAugmentationAdvisor 是一个标准的 RAG 处理流程封装。
        // 它拿到你的提问后，会调用你配置的 DocumentRetriever 去找资料，然后组装给大模型。
        // 提示：使用 RetrievalAugmentationAdvisor.builder() 开启构建，
        // 调用 .documentRetriever() 方法，把你 TODO 3 中创建的检索器传进去，
        // 最后 .build() 生成 Advisor 对象，并 return 返回。
        // =========================================================================
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(dashScopeDocumentRetriever)
                .build(); // 请替换为你构建的 Advisor 对象
    }
}
