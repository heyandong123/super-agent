package com.yupi.yuaiagent.tools;

import cn.hutool.core.util.StrUtil;
import com.yupi.yuaiagent.toolrouter.ToolRoutingProperties;
import com.yupi.yuaiagent.toolrouter.ToolVectorRouter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 工具搜索元工具（模型自述能力需求，按需拉取工具）
 * 降级链：语义检索 → 阈值放宽 → 关键词回退 → 结构化失败提示。
 * 命中的工具写入 Agent 的 L2 会话缓存（discoveredTools），后续轮次直接暴露。
 */
@Slf4j
public class ToolSearchTool {

    /**
     * 语义检索默认阈值（与 yuai.tool-router.similarity-threshold 默认值保持一致）
     */
    private static final double DEFAULT_THRESHOLD = 0.2;

    /**
     * 阈值放宽下限
     */
    private static final double MIN_THRESHOLD = 0.05;

    /**
     * 搜索结果中排除的工具名（终止工具与元工具，L1 已常驻无需重复命中）
     */
    private static final Set<String> EXCLUDED_NAMES = Set.of("doTerminate", "searchTools", "listTools");

    private final ToolVectorRouter toolVectorRouter;

    /**
     * 本 Agent 的原始工具注册表（不含元工具），用于解析描述与 L2 白名单过滤
     */
    private final ToolCallback[] registry;

    /**
     * L2 会话缓存集合（ToolCallAgent 持有，本工具直写）
     */
    private final Set<String> discoveredTools;

    private final int searchTopK;

    private final double relaxedThresholdFactor;

    private final AtomicInteger callCount = new AtomicInteger();

    private final AtomicInteger hitCount = new AtomicInteger();

    public ToolSearchTool(ToolVectorRouter toolVectorRouter,
                          ToolCallback[] registry,
                          Set<String> discoveredTools,
                          ToolRoutingProperties properties) {
        this.toolVectorRouter = toolVectorRouter;
        this.registry = registry;
        this.discoveredTools = discoveredTools;
        this.searchTopK = properties.getSearchTopK();
        this.relaxedThresholdFactor = properties.getRelaxedThresholdFactor();
    }

    @Tool(name = "searchTools", description = """
            Search the available tool catalog for tools relevant to a task.
            Describe the capability you need in natural language; matched tools are
            added to the current session and become directly callable in later steps.
            """)
    public String searchTools(
            @ToolParam(description = "Natural-language description of the capability you need") String query) {
        callCount.incrementAndGet();
        if (StrUtil.isBlank(query)) {
            return "用法：searchTools(query) —— 用自然语言描述你需要的能力，例如\"创建 Jira 工单\"或\"搜索图片\"。";
        }
        // 降级链：语义检索 → 阈值放宽 → 关键词回退
        List<ToolVectorRouter.ToolHit> hits = semanticSearchOrNull(query);
        String level = "VECTOR";
        if (hits == null) {
            hits = semanticSearchRelaxedOrNull(query);
            level = "VECTOR_RELAXED";
        }
        if (hits == null) {
            hits = keywordSearchHits(query);
            level = "KEYWORD";
        }
        if (hits == null) {
            log.info("searchTools 未命中: query={}", query);
            return buildMissMessage(query);
        }
        // 白名单过滤：排除元工具/终止工具，且只保留本 Agent registry 里真实存在的工具
        // （向量索引是全量 allTools，而各子 Agent 的 registry 是子集）
        List<ToolVectorRouter.ToolHit> validHits = hits.stream()
                .filter(hit -> !EXCLUDED_NAMES.contains(hit.name()))
                .filter(hit -> Arrays.stream(registry)
                        .anyMatch(tool -> tool.getToolDefinition().name().equals(hit.name())))
                .toList();
        if (validHits.isEmpty()) {
            log.info("searchTools 命中但全部被白名单过滤: query={}, 原始命中={}", query, hits);
            return buildMissMessage(query);
        }
        // 直写 L2 会话缓存：后续轮次 think() 直接暴露这些工具
        validHits.forEach(hit -> discoveredTools.add(hit.name()));
        hitCount.addAndGet(validHits.size());
        log.info("searchTools 调用: query={}, 降级层级={}, 命中数={}, 命中={}",
                query, level, validHits.size(),
                validHits.stream().map(ToolVectorRouter.ToolHit::name).toList());
        return buildHitMessage(validHits, level);
    }

    public int getCallCount() {
        return callCount.get();
    }

    public int getHitCount() {
        return hitCount.get();
    }

    private List<ToolVectorRouter.ToolHit> semanticSearchOrNull(String query) {
        if (toolVectorRouter == null) {
            return null;
        }
        try {
            List<ToolVectorRouter.ToolHit> hits =
                    toolVectorRouter.semanticSearch(query, DEFAULT_THRESHOLD, searchTopK);
            return hits.isEmpty() ? null : hits;
        } catch (Exception e) {
            log.warn("searchTools 语义检索异常，进入降级: {}", e.getMessage());
            return null;
        }
    }

    private List<ToolVectorRouter.ToolHit> semanticSearchRelaxedOrNull(String query) {
        if (toolVectorRouter == null) {
            return null;
        }
        try {
            double relaxedThreshold = Math.max(DEFAULT_THRESHOLD * relaxedThresholdFactor, MIN_THRESHOLD);
            List<ToolVectorRouter.ToolHit> hits =
                    toolVectorRouter.semanticSearch(query, relaxedThreshold, searchTopK * 2);
            return hits.isEmpty() ? null : hits;
        } catch (Exception e) {
            log.warn("searchTools 阈值放宽检索异常，进入关键词回退: {}", e.getMessage());
            return null;
        }
    }

    private List<ToolVectorRouter.ToolHit> keywordSearchHits(String query) {
        if (toolVectorRouter == null) {
            return null;
        }
        try {
            Set<String> names = toolVectorRouter.keywordSearch(query);
            if (names.isEmpty()) {
                return null;
            }
            return names.stream()
                    .map(name -> new ToolVectorRouter.ToolHit(name, 0.0))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("searchTools 关键词回退异常: {}", e.getMessage());
            return null;
        }
    }

    private String buildHitMessage(List<ToolVectorRouter.ToolHit> hits, String level) {
        StringBuilder sb = new StringBuilder();
        sb.append("找到 ").append(hits.size()).append(" 个相关工具：\n");
        int index = 1;
        for (ToolVectorRouter.ToolHit hit : hits) {
            sb.append(index++).append(". ").append(hit.name()).append(" — ").append(descriptionOf(hit.name()));
            if (!"KEYWORD".equals(level) && hit.score() > 0) {
                sb.append(String.format("（相似度 %.2f）", hit.score()));
            }
            sb.append("\n");
        }
        sb.append("这些工具已加入本会话可用清单，后续步骤可直接调用。");
        if ("VECTOR_RELAXED".equals(level)) {
            sb.append("（注：语义相似度较低，结果可能不完全匹配）");
        }
        return sb.toString();
    }

    private String buildMissMessage(String query) {
        return "未找到与「" + query + "」相关的工具（已尝试语义检索和关键词匹配）。\n"
                + "建议：1) 换更通用或更具体的关键词重试 searchTools；"
                + "2) 调用 listTools 查看本会话全部可用工具；"
                + "3) 若所需能力确实缺失（如 MCP 工具），可能是对应 MCP 服务未连接，请检查 mcp-servers.json 配置。";
    }

    private String descriptionOf(String toolName) {
        return Arrays.stream(registry)
                .filter(tool -> tool.getToolDefinition().name().equals(toolName))
                .map(tool -> tool.getToolDefinition().description())
                .findFirst()
                .orElse("");
    }
}
