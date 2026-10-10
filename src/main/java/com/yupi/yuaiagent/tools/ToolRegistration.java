package com.yupi.yuaiagent.tools;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 集中的工具注册类
 */
@Configuration
public class ToolRegistration {

    // 密钥集中管理：真值只写 application-local.yml 的 yuai.api-keys.tavily（未跟踪 + 已忽略）
    // 空默认值：未配置 key 时跳过 Tavily 主链路，仅使用免费引擎兜底（百度 → Bing）
    @Value("${yuai.api-keys.tavily:}")
    private String tavilyApiKey;

//    @Bean
//    public FileOperationTool fileOperationTool() {
//        return new FileOperationTool();
//    }
    @Autowired
    FileOperationTool fileOperationTool;

    @Bean
    public WebSearchTool webSearchTool() {
        return new WebSearchTool(tavilyApiKey);
    }

    @Bean
    public WebScrapingTool webScrapingTool() {
        return new WebScrapingTool();
    }

    @Bean
    public ResourceDownloadTool resourceDownloadTool() {
        return new ResourceDownloadTool();
    }

    @Bean
    public TerminalOperationTool terminalOperationTool() {
        return new TerminalOperationTool();
    }

    @Bean
    public PDFGenerationTool pdfGenerationTool() {
        return new PDFGenerationTool();
    }

    @Bean
    public TerminateTool terminateTool() {
        return new TerminateTool();
    }

    @Bean(name = "searchTools")
    public ToolCallback[] searchTools() {
        return ToolCallbacks.from(
                webSearchTool(),
                webScrapingTool(),
                resourceDownloadTool(),
                terminateTool()
        );
    }

    @Bean(name = "documentTools")
    public ToolCallback[] documentTools() {
        return ToolCallbacks.from(
                //fileOperationTool(),
                fileOperationTool,
                pdfGenerationTool(),
                terminateTool()
        );
    }

    @Bean(name = "executionTools")
    public ToolCallback[] executionTools() {
        return ToolCallbacks.from(
                terminalOperationTool(),
                terminateTool()
        );
    }

    @Bean
    public ToolCallback[] allTools() {
        return ToolCallbacks.from(
                //fileOperationTool(),
                fileOperationTool,
                webSearchTool(),
                webScrapingTool(),
                resourceDownloadTool(),
                terminalOperationTool(),
                pdfGenerationTool(),
                terminateTool()
        );
    }
}
