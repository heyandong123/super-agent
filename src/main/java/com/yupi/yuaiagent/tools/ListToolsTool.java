package com.yupi.yuaiagent.tools;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Arrays;
import java.util.List;

/**
 * 工具目录元工具（分页浏览本会话全部可用工具，工具搜索失效时的兜底路径）
 */
public class ListToolsTool {

    /**
     * 目录中排除的元工具（它们常驻暴露，不需要出现在目录里）
     */
    private static final List<String> EXCLUDED_NAMES = List.of("searchTools", "listTools");

    /**
     * 本 Agent 的原始工具注册表（不含元工具）
     */
    private final ToolCallback[] registry;

    private final int pageSize;

    public ListToolsTool(ToolCallback[] registry, int pageSize) {
        this.registry = registry;
        this.pageSize = pageSize;
    }

    @Tool(name = "listTools", description = """
            List the full catalog of tools available in this session, paginated.
            Use this when searchTools cannot find a suitable tool for the task.
            """)
    public String listTools(
            @ToolParam(description = "Page number, starting from 1", required = false) Integer page) {
        List<ToolCallback> tools = Arrays.stream(registry)
                .filter(tool -> !EXCLUDED_NAMES.contains(tool.getToolDefinition().name()))
                .toList();
        int total = tools.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) total / pageSize));
        int currentPage = (page == null || page < 1) ? 1 : Math.min(page, totalPages);
        int fromIndex = (currentPage - 1) * pageSize;
        if (fromIndex >= total) {
            return "可用工具目录（共 " + total + " 个），页码超出范围，共 " + totalPages + " 页。";
        }
        int toIndex = Math.min(fromIndex + pageSize, total);
        StringBuilder sb = new StringBuilder();
        sb.append("可用工具目录（共 ").append(total).append(" 个，第 ")
                .append(currentPage).append("/").append(totalPages).append(" 页）：\n");
        for (int i = fromIndex; i < toIndex; i++) {
            sb.append(i + 1).append(". ").append(tools.get(i).getToolDefinition().name())
                    .append(" — ").append(tools.get(i).getToolDefinition().description()).append("\n");
        }
        return sb.toString();
    }
}
