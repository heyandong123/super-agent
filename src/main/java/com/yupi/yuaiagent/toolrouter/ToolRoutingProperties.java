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
}
