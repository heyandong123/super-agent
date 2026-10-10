package com.yupi.yuaiagent.agent.model;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

/**
 * 子智能体类型
 */
public enum SubAgentType {
    SEARCH,
    DOCUMENT,
    GENERAL;

    @JsonCreator
    public static SubAgentType fromValue(String value) {
        if (value == null) {
            return GENERAL;
        }
        try {
            return SubAgentType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return GENERAL;
        }
    }
}
