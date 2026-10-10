package com.yupi.yuaiagent.tools;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class PDFGenerationToolProTest {

    @Test
    void generatePDF() {
        PDFGenerationToolPro pdfGenerationToolPro = new PDFGenerationToolPro();
        String s = pdfGenerationToolPro.generatePDF("kk", "牛逼");
    }
}