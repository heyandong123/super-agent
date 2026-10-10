package com.yupi.yuaiagent.config;

import cn.hutool.core.util.StrUtil;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.retry.support.RetryTemplate;

/**
 * DeepSeek 使用 OpenAI 兼容接口，因此这里直接复用 Spring AI OpenAI 实现。
 * 只切换聊天模型，现有 DashScope 的 embedding / RAG 链路保持不变。
 */
@Configuration
@Slf4j
public class DeepSeekChatModelConfig {

    @Bean(name = "deepSeekChatModel")
    /**
     * @Primary: 当存在多个 ChatModel 类型的 Bean 时，优先注入此 Bean。
     */
    @Primary
    public ChatModel deepSeekChatModel(
            @Value("${spring.ai.openai.api-key:${DEEPSEEK_API_KEY:}}") String apiKey,
            @Value("${spring.ai.openai.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${spring.ai.openai.chat.options.model:deepseek-v4-pro}") String model) {
        if (StrUtil.isBlank(apiKey)) {
            log.warn("未检测到 DeepSeek API Key，聊天模型已切换到 DeepSeek 配置，但运行时调用会鉴权失败。请设置 DEEPSEEK_API_KEY。");
        }
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(StrUtil.blankToDefault(apiKey, ""))
                .build();
        OpenAiChatOptions chatOptions = new OpenAiChatOptions();
        chatOptions.setModel(model);
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(chatOptions)
                .toolCallingManager(ToolCallingManager.builder().build())
                .retryTemplate(RetryTemplate.defaultInstance())
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
    }
}
