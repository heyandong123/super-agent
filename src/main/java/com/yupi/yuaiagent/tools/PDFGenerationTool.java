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
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
/**
 * PDF 生成工具（赋予大模型自动化排版并导出 PDF 文档的能力）
 */
public class PDFGenerationTool {

    private static final long PDF_GENERATION_TIMEOUT_SECONDS = 15;

    /** 候选中文字体路径列表(按优先级) */
    private static final String[] CHINESE_FONT_PATHS = {
        System.getProperty("user.home") + "/Library/Fonts/Source Han Serif SC Heavy (TrueType).ttf",
        System.getProperty("user.home") + "/Library/Fonts/NotoSansSC-VF.ttf",
        System.getProperty("user.home") + "/Library/Fonts/NotoSerifSC-VF.ttf",
        "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
        "C:\\Windows\\Fonts\\simsun.ttc",
    };

    /**
     * 多源加载中文字体:系统字体文件 → iText 内置字体。
     * iText 9 已移除内置 STSong 字体,因此优先使用系统字体文件。
     */
    private PdfFont createChineseFont() throws IOException {
        for (String fontPath : CHINESE_FONT_PATHS) {
            File fontFile = new File(fontPath);
            if (fontFile.exists()) {
                try {
                    return PdfFontFactory.createFont(fontPath,
                            PdfFontFactory.EmbeddingStrategy.PREFER_EMBEDDED);
                } catch (IOException ignored) {
                    // 该字体文件加载失败,尝试下一个
                }
            }
        }
        // 所有系统字体都失败,尝试 iText 内置字体(兼容旧版本)
        return PdfFontFactory.createFont("STSongStd-Light", "UniGB-UCS2-H");
    }

    @Tool(description = "Generate a PDF file with given content", returnDirect = false)
    public String generatePDF(
            // TODO 2: 描述参数 fileName 和 content
            @ToolParam(description = "Name of the file to save the generated PDF") String fileName,
            @ToolParam(description = "Content to be included in the PDF") String content) {
        String fileDir = FileConstant.FILE_SAVE_DIR + "/pdf";
        // 绝对路径按原样使用，相对路径拼接沙箱目录（见 ToolPathUtils）
        String filePath = ToolPathUtils.resolveFilePath(fileDir, fileName);
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future<String> future = executorService.submit(() -> {
            // TODO 3: 确保目标文件的多级目录存在
            Path parent = Paths.get(filePath).getParent();
            if (parent != null) {
                FileUtil.mkdir(parent.toString());
            }
            // TODO 4: 构建 iText7 的核心文档对象流 (使用 try-with-resources 自动优雅关闭流)
            // 提示: 依次实例化 PdfWriter(底层物理写入), PdfDocument(PDF规范结构管理), Document(面向开发者的排版画布)
            try (PdfWriter writer = new PdfWriter(filePath);
                 PdfDocument pdf = new PdfDocument(writer);
                 Document document = new Document(pdf)) {
                // 尝试多个中文字体源,兼容 macOS / Linux / Windows
                PdfFont font = createChineseFont();
                document.setFont(font);
                // TODO 6: 创建段落，填充内容，并将其挂载到画板上
                // 提示: new 一个 Paragraph 传入 content，然后用 document.add(paragraph) 添加进去。
                Paragraph paragraph = new Paragraph(content);
                document.add(paragraph);
            }
            return "PDF generated successfully to: " + filePath;
        });
        try {
            return future.get(PDF_GENERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            FileUtil.del(filePath);
            return "Error generating PDF: generation timed out after " + PDF_GENERATION_TIMEOUT_SECONDS + " seconds";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            FileUtil.del(filePath);
            return "Error generating PDF: generation interrupted";
        } catch (ExecutionException e) {
            FileUtil.del(filePath);
            Throwable cause = e.getCause();
            String message = cause != null ? cause.getMessage() : e.getMessage();
            return "Error generating PDF: " + message;
        } finally {
            executorService.shutdownNow();
        }
    }
}
