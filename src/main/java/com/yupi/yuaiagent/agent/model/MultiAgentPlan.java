package com.yupi.yuaiagent.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 多智能体执行计划
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MultiAgentPlan {

    private String summary;

    private List<SubTaskRequest> steps = new ArrayList<>();
}
