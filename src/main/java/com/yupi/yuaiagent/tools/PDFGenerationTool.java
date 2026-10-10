/*
package com.yupi.yuaiagent.tools;

import cn.hutool.core.io.FileUtil;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import com.yupi.yuaiagent.constant.FileConstant;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;

*/
/**
 * PDF 生成工具
 *//*

public class PDFGenerationTool {

    @Tool(description = "Generate a PDF file with given content", returnDirect = false)
    public String generatePDF(
            @ToolParam(description = "Name of the file to save the generated PDF") String fileName,
            @ToolParam(description = "Content to be included in the PDF") String content) {
        String fileDir = FileConstant.FILE_SAVE_DIR + "/pdf";
        String filePath = fileDir + "/" + fileName;
        try {
            // 创建目录
            FileUtil.mkdir(fileDir);
            // 创建 PdfWriter 和 PdfDocument 对象
            try (PdfWriter writer = new PdfWriter(filePath);
                 PdfDocument pdf = new PdfDocument(writer);
                 Document document = new Document(pdf)) {
                // 自定义字体（需要人工下载字体文件到特定目录）
//                String fontPath = Paths.get("src/main/resources/static/fonts/simsun.ttf")
//                        .toAbsolutePath().toString();
//                PdfFont font = PdfFontFactory.createFont(fontPath,
//                        PdfFontFactory.EmbeddingStrategy.PREFER_EMBEDDED);
                // 使用内置中文字体
                PdfFont font = PdfFontFactory.createFont("STSongStd-Light", "UniGB-UCS2-H");
                document.setFont(font);
                // 创建段落
                Paragraph paragraph = new Paragraph(content);
                // 添加段落并关闭文档
                document.add(paragraph);
            }
            return "PDF generated successfully to: " + filePath;
        } catch (IOException e) {
            return "Error generating PDF: " + e.getMessage();
        }
    }
}
*/
package com.yupi.yuaiagent.tools;
import cn.hutool.core.io.FileUtil;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import com.yupi.yuaiagent.constant.FileConstant;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import java.io.IOException;
/**
 * PDF 生成工具（赋予大模型自动化排版并导出 PDF 文档的能力）
 */
public class PDFGenerationTool {
    // TODO 1: 标记工具并设置 returnDirect 属性
    // 【面试级考点】returnDirect = false 意味着大模型调用完工具后，会把工具的返回值（比如 "PDF generated successfully"）
    // 拿回大脑里消化一下，再由大模型组织自然语言回复给用户。如果设为 true，返回值会不经大模型润色直接甩给用户。
    @Tool(description = "Generate a PDF file with given content", returnDirect = false)
    public String generatePDF(
            // TODO 2: 描述参数 fileName 和 content
            @ToolParam(description = "Name of the file to save the generated PDF") String fileName,
            @ToolParam(description = "Content to be included in the PDF") String content) {
        String fileDir = FileConstant.FILE_SAVE_DIR + "/pdf";
        String filePath = fileDir + "/" + fileName;
        try {
            // TODO 3: 确保多级目录存在
            FileUtil.mkdir(fileDir);
            // TODO 4: 构建 iText7 的核心文档对象流 (使用 try-with-resources 自动优雅关闭流)
            // 提示: 依次实例化 PdfWriter(底层物理写入), PdfDocument(PDF规范结构管理), Document(面向开发者的排版画布)
            try (PdfWriter writer = new PdfWriter(filePath);
                 PdfDocument pdf = new PdfDocument(writer);
                 Document document = new Document(pdf)) {
                // TODO 5: 解决 PDF 中文乱码问题 (企业级排坑)
                // 核心原理: PDF 标准最初基于西方字符集，原生不自带中文字体，直接写入中文会变空白。
                // 提示: 使用 PdfFontFactory.createFont("STSongStd-Light", "UniGB-UCS2-H") 调用 iText 亚洲语言包内置的宋体。
                PdfFont font = PdfFontFactory.createFont("STSongStd-Light", "UniGB-UCS2-H"); // 替换此处代码
                document.setFont(font);
                // TODO 6: 创建段落，填充内容，并将其挂载到画板上
                // 提示: new 一个 Paragraph 传入 content，然后用 document.add(paragraph) 添加进去。
                Paragraph paragraph = new Paragraph(content);
                document.add(paragraph);
            }
            return "PDF generated successfully to: " + filePath;
        } catch (IOException e) {
            return "Error generating PDF: " + e.getMessage();
        }
    }
}