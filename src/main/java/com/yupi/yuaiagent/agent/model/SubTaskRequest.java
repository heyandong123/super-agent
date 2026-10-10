package com.yupi.yuaiagent.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 协调器分发给子智能体的任务
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SubTaskRequest {

    private SubAgentType agentType;

    private String goal;

    private String reason;
}
