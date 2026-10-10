package com.yupi.yuaiagent.rag;

import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;

/**
 * 创建上下文查询增强器的工厂
 *
 * 【空知识库降级策略】allowEmptyContext=true：
 * 检索不到任何知识文档时，用户问题原样透传，模型走"无 RAG 的普通回答"，
 * 而不是让模型按固定话术输出拒绝文案。
 * 教训：allowEmptyContext(false) + 固定拒绝提示词，会在知识库为空时让模型照抄
 * 话术，用户看到的就是"答非所问"的假拒绝（如"我只能回答恋爱相关的问题"）。
 */
public class LoveAppContextualQueryAugmenterFactory {

    public static ContextualQueryAugmenter createInstance() {
        return ContextualQueryAugmenter.builder()
                // 检索为空时把原始用户问题直接交给模型回答（降级），
                // 知识库不可用/为空也不影响对话可用性
                .allowEmptyContext(true)
                .build();
    }
}
