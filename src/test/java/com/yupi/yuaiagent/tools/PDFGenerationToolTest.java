package com.yupi.yuaiagent.tools;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class PDFGenerationToolTest {

    @Test
    void generatePDF() {
        PDFGenerationTool tool = new PDFGenerationTool();
        String fileName = "测试文档.pdf";
        String content = "测试文档 https://www.example.com";
        String result = tool.generatePDF(fileName, content);
        assertNotNull(result);
    }
}