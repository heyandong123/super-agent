package com.yupi.yuaiagent.toolrouter;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具动态调用机制配置属性
 */
@Component
@ConfigurationProperties(prefix = "yuai.tool-routing")
@Data
public class ToolRoutingProperties {

    /**
     * L1 额外固定暴露的工具名（每轮随元工具一起暴露，默认为空）
     */
    private List<String> fixedTools = new ArrayList<>();

    /**
     * searchTools 语义检索返回的工具数
     */
    private int searchTopK = 3;

    /**
     * 阈值放宽系数：首轮语义检索失败后，阈值乘以该系数放宽再试一次
     */
    private double relaxedThresholdFactor = 0.5;

    /**
     * listTools 目录分页大小
     */
    private int listPageSize = 10;

    /**
     * 工具暴露模式（2026-10 工具重构引入）：
     * - full（默认，生产模式）：每轮暴露全部工具（全量常驻），不做关键词/向量路由；
     *   动态调用机制整体旁路（ToolSearchTool/ListToolsTool 不注册、启动期不构建工具向量索引），
     *   相关源码保留为教学素材。对标：Claude Code / OpenHands 的固定工具面，
     *   以及 Manus 的 "Mask, don't remove"（任务中途不增删工具，保持请求前缀稳定）。
     * - dynamic（教学/回退）：旧的四层动态调用机制（元工具 + 关键词/向量路由 + L2 会话缓存）。
     */
    private String exposureMode = "full";
}
