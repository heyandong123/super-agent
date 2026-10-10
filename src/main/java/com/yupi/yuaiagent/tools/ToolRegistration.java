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

    // 空默认值：未配置 key 时仅使用免费引擎（百度 → Bing）
    @Value("${search-api.api-key:}")
    private String searchApiKey;

//    @Bean
//    public FileOperationTool fileOperationTool() {
//        return new FileOperationTool();
//    }
    @Autowired
    FileOperationTool fileOperationTool;

    @Bean
    public WebSearchTool webSearchTool() {
        return new WebSearchTool(searchApiKey);
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
