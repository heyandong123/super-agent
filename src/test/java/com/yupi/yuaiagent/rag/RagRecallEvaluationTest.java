package com.yupi.yuaiagent.rag;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

/**
 * RAG 检索召回率评测。
 * 使用真实 QueryRewriter（LLM 驱动）对比模糊提问 vs 查询重写的 Top-1 命中率。
 */
@SpringBootTest
@ActiveProfiles("local")
class RagRecallEvaluationTest {

    private static VectorStore vectorStore;
    private static QueryRewriter queryRewriter;

    @BeforeAll
    static void setUp(@Autowired EmbeddingModel embeddingModel,
                      @Autowired ResourcePatternResolver resourceResolver,
                      @Autowired QueryRewriter qr) {
        LoveAppDocumentLoader loader = new LoveAppDocumentLoader(resourceResolver);
        MyTokenTextSplitter splitter = new MyTokenTextSplitter(new LoveAppRagProperties());
        List<Document> loveDocs = loader.loadMarkdowns();
        List<Document> chunks = splitter.splitCustomized(loveDocs);

        // 生成 100 篇干扰文档(其他领域),模拟真实知识库
        List<Document> distractors = generateDistractorDocuments();
        chunks.addAll(distractors);

        vectorStore = SimpleVectorStore.builder(embeddingModel).build();
        vectorStore.add(chunks);
        queryRewriter = qr;
        System.out.println("已加载 " + chunks.size() + " 个文档片段(含 " + distractors.size() + " 篇干扰)");
    }

    private static List<Document> generateDistractorDocuments() {
        String[] topics = {
            "Python 数据分析入门：使用 pandas 和 matplotlib 进行数据可视化",
            "React Hooks 最佳实践：useState 和 useEffect 的常见误区",
            "MySQL 索引优化：B+树结构与慢查询调优实战",
            "Docker 容器化部署：从 Dockerfile 到 Kubernetes 集群",
            "机器学习基础：线性回归与逻辑回归的数学推导",
            "Redis 缓存穿透与雪崩：布隆过滤器和分布式锁解决方案",
            "微服务架构设计：服务拆分、API 网关与服务间通信",
            "Linux 运维常用命令：文件操作、进程管理与网络诊断",
            "TypeScript 类型系统深入：泛型、条件类型与类型推导",
            "Git 分支管理策略：Git Flow、GitHub Flow 与 Trunk-Based",
            "前端性能优化：懒加载、代码分割与缓存策略",
            "Java 并发编程：线程池、锁升级与 JUC 工具类",
            "设计模式实战：单例、工厂、观察者模式的应用场景",
            "Spring Boot 自动配置原理：@Conditional 与 SPI 机制",
            "Kafka 消息队列：分区策略、消费者组与 exactly-once 语义",
            "网络安全基础：HTTPS 握手、XSS 防护与 CSRF 防御",
            "分布式系统：CAP 定理、一致性哈希与 Raft 共识算法",
            "Android 开发：Jetpack Compose 声明式 UI 入门",
            "Go 语言并发：goroutine、channel 与 select 模式",
            "数据库事务隔离级别：读已提交、可重复读与幻读解决",
            "Nginx 反向代理配置：负载均衡策略与 HTTPS 终止",
            "Elasticsearch 搜索引擎：倒排索引、分词器与聚合查询",
            "计算机组成原理：CPU 流水线、缓存一致性与内存屏障",
            "编译原理入门：词法分析、语法分析与 AST 抽象语法树",
            "操作系统内存管理：虚拟内存、页表、TLB 与缺页中断",
            "计算机网络 TCP 协议：三次握手、四次挥手与拥塞控制",
            "区块链技术原理：共识机制、智能合约与去中心化应用",
            "图像处理基础：卷积、滤波与 OpenCV 常用操作",
            "自然语言处理：词向量、Transformer 架构与注意力机制",
            "云计算基础：IaaS、PaaS、SaaS 的区别与选型指南",
        };
        List<Document> docs = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String title = topics[i % topics.length];
            String content = title + "。这是一个关于" + title.substring(0, 8)
                + "的技术文档，涵盖了相关领域的基础概念、核心原理和实践案例。"
                + "适合初学者入门和有一定经验的开发者参考。";
            // 每个文档略作变化,避免完全重复
            content += " (version " + (i / topics.length + 1) + ")";
            docs.add(new Document(content));
        }
        return docs;
    }

    // 极度模糊提问:当前提问本身不含任何文档关键词,必须依赖上下文
    record TestCase(String historyContext, String rawQuery, String[] expectedKeywords) {}

    private static final List<TestCase> TEST_CASES = List.of(
        new TestCase("用户：我最近老是和对象吵架",
            "那该怎么办呢", new String[]{"争吵", "冷静", "倾听"}),
        new TestCase("用户：我觉得自己很普通，没什么吸引力",
            "怎么办", new String[]{"魅力", "形象", "兴趣"}),
        new TestCase("用户：我社交圈子很小，想认识新的人",
            "有什么办法", new String[]{"社交", "结识", "交流"}),
        new TestCase("用户：我妈给我介绍了一个相亲对象",
            "然后呢", new String[]{"相亲", "判断", "发展"}),
        new TestCase("用户：我太黏对象了，什么都要一起",
            "怎么解决", new String[]{"依赖", "独立", "自己"}),
        new TestCase("用户：对象经常熬夜打游戏不陪我",
            "怎么说", new String[]{"缺点", "沟通", "改善"}),
        new TestCase("用户：想给对方一个惊喜",
            "怎么做", new String[]{"惊喜", "浪漫", "喜欢"}),
        new TestCase("用户：我们聊到了以后结婚买房的事",
            "怎么聊下去", new String[]{"未来", "规划", "沟通"}),
        new TestCase("用户：我刚分手，每天都很难受",
            "怎么办", new String[]{"焦虑", "充实", "恋爱"}),
        new TestCase("用户：线上聊了很多人但都没成",
            "有什么建议", new String[]{"线上", "交友", "脱单"})
    );

    @Test
    @DisplayName("RAG Top-1 召回：原始模糊提问 vs QueryRewriter(LLM)重写")
    void evaluateWithRealRewriter() {
        System.out.println("\n========================================");
        System.out.println("RAG Top-1 召回率评测（3 个预期关键词至少命中 2 个）");
        System.out.println("========================================\n");

        int rawHits = 0, rewrittenHits = 0;

        for (TestCase tc : TEST_CASES) {
            // 原始模糊提问
            List<Document> rawResults = vectorStore.similaritySearch(
                SearchRequest.builder().query(tc.rawQuery).topK(1).similarityThreshold(0.1).build()
            );
            boolean rawHit = isHit(rawResults, tc.expectedKeywords);
            if (rawHit) rawHits++;

            // QueryRewriter（LLM 驱动）重写
            String rewritten = queryRewriter.doQueryRewrite(tc.rawQuery, tc.historyContext);
            List<Document> rewrittenResults = vectorStore.similaritySearch(
                SearchRequest.builder().query(rewritten).topK(1).similarityThreshold(0.1).build()
            );
            boolean rwHit = isHit(rewrittenResults, tc.expectedKeywords);
            if (rwHit) rewrittenHits++;

            System.out.printf("%-16s → raw:%-6s  rewrite:%-6s  [%s]%n",
                truncate(tc.rawQuery, 14),
                rawHit ? "✅ 命中" : "❌ 未命中",
                rwHit ? "✅ 命中" : "❌ 未命中",
                truncate(rewritten, 40));
        }

        int total = TEST_CASES.size();
        System.out.println("\n--- 最终结果 ---");
        System.out.printf("模糊提问 Top-1 命中率:    %d/%d (%d%%)%n", rawHits, total, rawHits * 100 / total);
        System.out.printf("Query Rewrite 后命中率:   %d/%d (%d%%)%n", rewrittenHits, total, rewrittenHits * 100 / total);
    }

    private boolean isHit(List<Document> results, String[] keywords) {
        if (results.isEmpty()) return false;
        String text = results.get(0).getText();
        int match = 0;
        for (String kw : keywords) {
            if (text.contains(kw)) match++;
        }
        return match >= 2;
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
