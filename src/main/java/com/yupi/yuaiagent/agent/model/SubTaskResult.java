package com.yupi.yuaiagent.agent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 子智能体执行结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubTaskResult {

    private SubAgentType agentType;

    private String goal;

    private boolean success;

    private String summary;

    private String rawOutput;
}
