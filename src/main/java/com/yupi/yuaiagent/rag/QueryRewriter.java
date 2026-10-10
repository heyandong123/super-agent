/*
package com.yupi.yuaiagent.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.stereotype.Component;

*/
/**
 * 查询重写器
 *//*

@Component
public class QueryRewriter {

    private final QueryTransformer queryTransformer;

    public QueryRewriter(ChatModel dashscopeChatModel) {
        ChatClient.Builder builder = ChatClient.builder(dashscopeChatModel);
        // 创建查询重写转换器
        queryTransformer = RewriteQueryTransformer.builder()
                .chatClientBuilder(builder)
                .build();
    }

    */
/**
     * 执行查询重写
     *
     * @param prompt
     * @return
     *//*

    public String doQueryRewrite(String prompt) {
        Query query = new Query(prompt);
        // 执行查询重写
        Query transformedQuery = queryTransformer.transform(query);
        // 输出重写后的查询
        return transformedQuery.text();
    }
}
*/

// package 定义了当前类所在的包路径
package com.yupi.yuaiagent.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.stereotype.Component;

/**
 * 查询重写器
 * * @Component 是 Spring 的基础注解。
 * 和之前的 @Configuration 类似，它也是把这个类交给 Spring 容器管理（变成一个 Bean）。
 * 区别在于：@Configuration 通常用于“工厂类”（里面有很多 @Bean 生产其他对象），
 * 而 @Component 表示这个类本身就是一个普通的、干活的组件（在这个例子中，它专门负责重写查询）。
 */
@Component
public class QueryRewriter {
    /**
     * QueryTransformer 是 Spring AI RAG 模块提供的核心接口，表示“查询转换器”。
     * 它属于“检索前（preretrieval）”阶段的操作。
     * 使用 final 修饰，表示这个变量一旦被赋值就不能再改变，这是一种很好的防御性编程习惯，保证了类的不可变性和线程安全。
     */
    private final QueryTransformer queryTransformer;
    /**
     * 【重点提示】：构造器注入 (Constructor Injection)
     * 你会发现这里没有用 @Resource！
     * 在 Spring 中，如果一个类只有一个构造函数，Spring 会自动把需要的参数（这里是 dashscopeChatModel）找出来传进去。
     * 这是目前 Spring 官方最推荐的依赖注入方式，比 @Resource/@Autowired 更安全，因为它能强制保证对象在创建时，核心依赖绝对不会为空 (null)。
     * * 参数 ChatModel：它是大模型能力的“底层引擎”（比如阿里云的通义千问大模型）。
     * 它和 ChatClient 的区别是：ChatModel 是纯粹的 API 封装，而 ChatClient 是在它之上封装的带有记忆、拦截器等高级功能的“客户端”。
     */
    public QueryRewriter(ChatModel dashscopeChatModel) {
        // =========================================================================
        // TODO 1: 构建 ChatClient.Builder
        // 核心原理：查询重写本身也需要大模型的思考能力，所以我们要用底层的 dashscopeChatModel
        // 创建一个大模型客户端构建器（Builder）。
        // 提示：调用 ChatClient.builder() 方法，把传进来的 dashscopeChatModel 塞进去，用变量接收。
        // =========================================================================
        ChatClient.Builder builder = ChatClient.builder(dashscopeChatModel);
        // =========================================================================
        // TODO 2: 创建查询重写转换器 (RewriteQueryTransformer)
        // 核心原理：RewriteQueryTransformer 是 Spring AI 内置的实现类。它内部其实封装着一段
        // 精心设计的系统提示词（System Prompt），大概意思是：“你是一个查询优化专家，请帮我把用户的输入重写...”。
        // 它需要一个 ChatClient 来执行这段对话，所以你要把刚才创建的 Builder 传给它。
        // 提示：使用 RewriteQueryTransformer.builder() 开启链式调用，
        // 接着调用 .chatClientBuilder() 把 TODO 1 的对象传进去，最后调用 .build() 生成实例，
        // 赋值给类的成员变量 queryTransformer。
        // =========================================================================
        queryTransformer = RewriteQueryTransformer.
                builder().
                chatClientBuilder(builder).
                build();
    }
    /**
     * 执行查询重写 (供外部业务调用的核心方法)
     *
     * @param prompt 用户的原始提问（可能很口语化）
     * @return 重写后，适合用于向量检索的规范字符串
     */
    public String doQueryRewrite(String prompt) {
        // =========================================================================
        // TODO 3: 将字符串包装为标准 Query 对象
        // 核心原理：在 Spring AI 的 RAG 体系中，统一使用 Query 对象来传递查询意图，
        // 因为除了文本，Query 对象内部还可以携带一些过滤条件（Metadata 过滤等）。
        // 提示：new 一个 Query 对象，把传入的 prompt 作为参数传递给构造函数。
        // =========================================================================
        Query query = new Query(prompt);
        // =========================================================================
        // TODO 4: 执行转换动作
        // 提示：调用成员变量 queryTransformer 的 transform() 方法，把你刚才 new 出来的 Query 对象传进去。
        // 这个动作会真正触发一次对大模型的网络请求。
        // 用一个名为 transformedQuery 的 Query 对象接收返回值。
        // =========================================================================
        Query transformedQuery = queryTransformer.transform(query);
        // =========================================================================
        // TODO 5: 提取并返回重写后的文本
        // 提示：调用 transformedQuery 的 text() 方法（或者 getText()，视具体版本 API 而定），
        // 提取出纯文本并 return 返回。
        // =========================================================================
        return transformedQuery.text(); // 请替换为你提取出的字符串变量
    }
}