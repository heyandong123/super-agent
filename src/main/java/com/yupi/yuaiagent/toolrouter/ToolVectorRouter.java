package com.yupi.yuaiagent.toolrouter;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 语义向量工具路由器
 * 将工具描述转为向量存入 PGVector，运行时通过语义相似度匹配用户意图和工具
 */
@Component
@Slf4j
public class ToolVectorRouter {

    private final VectorStore toolVectorStore;

    private final ToolCallback[] allTools;

    private final ToolVectorRouterProperties properties;

    /** 工具暴露模式配置：full（默认）时整个路由机制旁路，启动期不再构建索引 */
    private final ToolRoutingProperties toolRoutingProperties;

    private final JdbcTemplate jdbcTemplate;

    /**
     * 工具索引文本的内存镜像（工具名 → 名称+描述+中文场景词），供关键词检索兜底使用
     */
    private final Map<String, String> toolIndexText = new HashMap<>();

    public ToolVectorRouter(
            @Qualifier("toolRouterVectorStore") VectorStore toolVectorStore,
            @Qualifier("allTools") ToolCallback[] allTools,
            ToolVectorRouterProperties properties,
            ToolRoutingProperties toolRoutingProperties,
            JdbcTemplate jdbcTemplate) {
        this.toolVectorStore = toolVectorStore;
        this.allTools = allTools;
        this.properties = properties;
        this.toolRoutingProperties = toolRoutingProperties;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 应用启动时，将所有工具的描述转为向量并存入 PGVector。
     * 通过 schema-version 机制判断是否需要重建索引：
     * - 表为空 → 新建索引
     * - 表中有数据但版本号与配置不一致 → 清空并重建
     * - 版本号一致 → 跳过
     */
    @PostConstruct
    public void init() {
        // 全量加载模式（默认）下动态路由机制整体旁路：不构建工具向量索引，
        // 省掉启动期的 embedding 调用与对 tool_vector_store / tool_router_meta 表的依赖。
        // 代码保留为教学素材，exposure-mode=dynamic 时可切回。
        if (!"dynamic".equalsIgnoreCase(toolRoutingProperties.getExposureMode())) {
            log.info("工具暴露模式为全量加载（full），跳过工具向量索引构建（动态调用机制已弃用，见 CLAUDE.md）");
            return;
        }
        log.info("开始索引工具描述到向量库（schemaVersion={}）...", properties.getSchemaVersion());
        // 填充内存索引文本（关键词检索兜底用）。
        // 必须放在 schema 版本检查之前：版本一致时下方会提前 return，放后面会导致内存索引为空。
        Arrays.stream(allTools).forEach(tool ->
                toolIndexText.put(tool.getToolDefinition().name(), buildIndexText(tool.getToolDefinition())));
        try {
            Integer storedVersion = getStoredSchemaVersion();
            if (storedVersion != null && storedVersion == properties.getSchemaVersion()) {
                log.info("工具向量索引已是最新版本（v{}），跳过重建", storedVersion);
                return;
            }
            if (storedVersion != null) {
                log.info("检测到 schema 版本变更（v{} → v{}），清空旧索引并重建",
                        storedVersion, properties.getSchemaVersion());
                truncateVectorTable();
            }
        } catch (Exception e) {
            log.info("工具向量表尚不存在或无法查询，将创建并索引：{}", e.getMessage());
        }
        List<Document> toolDocs = buildToolDocuments();
        if (toolDocs.isEmpty()) {
            log.warn("没有可索引的工具，跳过");
            return;
        }
        try {
            toolVectorStore.add(toolDocs);
            saveSchemaVersion(properties.getSchemaVersion());
            log.info("已成功索引 {} 个工具到向量库（v{}）", toolDocs.size(), properties.getSchemaVersion());
        } catch (Exception e) {
            log.error("工具向量索引失败：{}", e.getMessage(), e);
        }
    }

    /**
     * 读取向量库中存储的 schema 版本号
     */
    private Integer getStoredSchemaVersion() {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT version FROM tool_router_meta WHERE meta_key = 'schema_version'",
                    Integer.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 保存当前 schema 版本号到向量库
     */
    private void saveSchemaVersion(int version) {
        jdbcTemplate.update("""
                CREATE TABLE IF NOT EXISTS tool_router_meta (
                    meta_key VARCHAR(64) PRIMARY KEY,
                    version INTEGER NOT NULL
                )
                """);
        jdbcTemplate.update("""
                INSERT INTO tool_router_meta (meta_key, version)
                VALUES ('schema_version', ?)
                ON CONFLICT (meta_key) DO UPDATE SET version = EXCLUDED.version
                """, version);
    }

    /**
     * 清空工具向量表
     */
    private void truncateVectorTable() {
        jdbcTemplate.update("DELETE FROM " + properties.getVectorTableName());
    }

    /**
     * 工具命中的结果记录（工具名 + 相似度）
     */
    public record ToolHit(String name, double score) {
    }

    /**
     * 根据用户上下文进行语义路由，返回匹配的工具名集合
     *
     * @param context 用户输入/对话上下文
     * @return 匹配的工具名集合（始终包含 doTerminate）
     * @throws RuntimeException PGVector 不可用时抛出，由上层回退到关键词路由
     */
    public Set<String> routeTools(String context) {
        List<ToolHit> hits = semanticSearch(context, properties.getSimilarityThreshold(), properties.getTopK());
        Set<String> toolNames = hits.stream()
                .map(ToolHit::name)
                .collect(Collectors.toSet());
        // 始终保留 doTerminate，让 Agent 可以自主终止
        toolNames.add("doTerminate");
        log.info("语义向量路由结果（topK={}, threshold={}）：{}",
                properties.getTopK(), properties.getSimilarityThreshold(), toolNames);
        return toolNames;
    }

    /**
     * 语义检索工具，返回命中工具名及相似度（供 searchTools 元工具编排降级链使用）
     *
     * @param query     检索 query
     * @param threshold 相似度阈值
     * @param topK      返回数量
     * @return 命中列表（可为空列表）
     */
    public List<ToolHit> semanticSearch(String query, double threshold, int topK) {
        List<Document> results = toolVectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(topK)
                        .similarityThreshold(threshold)
                        .build()
        );
        return results.stream()
                .map(doc -> new ToolHit(
                        (String) doc.getMetadata().get("toolName"),
                        doc.getScore() != null ? doc.getScore() : 0.0))
                .toList();
    }

    /**
     * 关键词检索工具（语义检索失败时的兜底），对内存索引文本做 contains 匹配。
     * 优先整体命中；整体未命中时按空白/逗号切词，任一词命中即计入。
     *
     * @param query 检索 query
     * @return 命中工具名集合（不含 doTerminate）
     */
    public Set<String> keywordSearch(String query) {
        if (StrUtil.isBlank(query)) {
            return Set.of();
        }
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        Set<String> matched = new HashSet<>();
        // 第 1 步：整体命中
        for (Map.Entry<String, String> entry : toolIndexText.entrySet()) {
            if ("doTerminate".equals(entry.getKey())) {
                continue;
            }
            if (entry.getValue().toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
                matched.add(entry.getKey());
            }
        }
        if (!matched.isEmpty()) {
            return matched;
        }
        // 第 2 步：切词命中
        String[] terms = normalizedQuery.split("[\\s,，、;；]+");
        for (Map.Entry<String, String> entry : toolIndexText.entrySet()) {
            if ("doTerminate".equals(entry.getKey())) {
                continue;
            }
            String indexText = entry.getValue().toLowerCase(Locale.ROOT);
            for (String term : terms) {
                if (StrUtil.isNotBlank(term) && indexText.contains(term)) {
                    matched.add(entry.getKey());
                    break;
                }
            }
        }
        return matched;
    }

    /**
     * 为每个工具构建待索引的 Document
     * 文本内容 = 工具名 + 工具描述 + 参数信息（从 JSON Schema 中提取）
     */
    private List<Document> buildToolDocuments() {
        return List.of(allTools).stream()
                .map(tool -> {
                    ToolDefinition def = tool.getToolDefinition();
                    String indexText = buildIndexText(def);
                    return new Document(indexText, Map.of("toolName", def.name()));
                })
                .collect(Collectors.toList());
    }

    /**
     * 构建用于向量化的工具文本
     * 组合工具名和描述，并追加中文场景关键词，让语义搜索更精准
     */
    private String buildIndexText(ToolDefinition def) {
        StringBuilder sb = new StringBuilder();
        sb.append("工具名称：").append(def.name()).append("\n");
        sb.append("工具描述：").append(def.description()).append("\n");
        // 追加中文使用场景关键词，提升跨语言语义匹配效果
        String chineseScenarios = getChineseScenarios(def.name());
        if (!chineseScenarios.isEmpty()) {
            sb.append("使用场景：").append(chineseScenarios);
        }
        return sb.toString();
    }

    /**
     * 为每个工具提供中文使用场景描述，增强语义匹配
     */
    private String getChineseScenarios(String toolName) {
        return switch (toolName) {
            case "searchWeb" ->
                    "搜索 查找 查询 检索 搜索网页 搜索引擎 百度搜索 联网搜索 搜一下 查一下 " +
                    "天气查询 新闻搜索 信息检索 网上搜索 实时信息 最新消息 搜资料 查资料 " +
                    "附近 周边 地点 位置 餐厅 电影院 咖啡店 好吃的 好玩的 约会地点";
            case "scrapeWebPage" ->
                    "抓取网页 爬虫 网页内容提取 抓取链接 网页抓取 爬网页 提取网页内容 " +
                    "获取网页 读取网页 网址抓取 链接内容 网页内容 爬取文章";
            case "downloadResource" ->
                    "下载 资源下载 下载文件 下载图片 下载视频 下载资源 文件下载 " +
                    "图片下载 资源获取 保存图片 下载链接";
            case "readFile" ->
                    "读取文件 读文件 查看文件 打开文件 文件读取 读内容 读取文档 " +
                    "查看内容 读txt 读markdown 文件查看";
            case "writeFile" ->
                    "写入文件 写文件 保存文件 保存 导出 存储 写入 写内容 " +
                    "保存到文件 输出文件 写文档 保存结果 另存为 存文件";
            case "generatePDF" ->
                    "生成PDF 导出PDF 创建PDF PDF生成 PDF报告 导出报告 生成报告 " +
                    "制作PDF 生成文档 PDF文档 报告生成 导出文档 制作报告 做成PDF";
            case "executeTerminalCommand" ->
                    "执行命令 终端命令 shell命令 bash命令 命令行 运行命令 " +
                    "执行脚本 运行脚本 python脚本 ls命令 终端操作 命令执行";
            default -> "";
        };
    }
}
