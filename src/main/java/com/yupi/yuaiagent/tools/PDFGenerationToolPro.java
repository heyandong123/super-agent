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
/**
 * PDF 生成与云端上传工具
 */
public class PDFGenerationToolPro {
    // 【核心修改 1】：returnDirect = true。大模型执行完此工具后，直接将这里的 return 值发给前端，不再进行二次对话。
    // 同时也修改了 description，明确告诉大模型这个工具会返回一个可下载的 URL。
    @Tool(description = "Generate a PDF file and return its accessible download URL", returnDirect = true)
    public String generatePDF(
            @ToolParam(description = "Name of the file to save the generated PDF") String fileName,
            @ToolParam(description = "Content to be included in the PDF") String content) {
        String fileDir = FileConstant.FILE_SAVE_DIR + "/pdf";
        String filePath = fileDir + "/" + fileName;
        try {
            FileUtil.mkdir(fileDir);
            try (PdfWriter writer = new PdfWriter(filePath);
                 PdfDocument pdf = new PdfDocument(writer);
                 Document document = new Document(pdf)) {
                PdfFont font = PdfFontFactory.createFont("STSongStd-Light", "UniGB-UCS2-H");
                document.setFont(font);
                Paragraph paragraph = new Paragraph(content);
                document.add(paragraph);
            }
            // 【核心修改 2】：拿到本地生成的 PDF 文件对象
            File pdfFile = new File(filePath);
            // 【核心修改 3】：调用对象存储服务，将文件传上云端，并拿到公网访问地址
            String fileUrl = uploadToObjectStorage(pdfFile);
            // 【核心修改 4】（企业级最佳实践）：上传云端后，立即删除本地临时文件，防止把宿主机磁盘撑爆
            FileUtil.del(pdfFile);
            // 【核心修改 5】：直接返回 Markdown 格式的超链接，前端用户看到后可以直接点击下载
            return "📄 PDF 文件已为您生成完毕！点击即可下载：[" + fileName + "](" + fileUrl + ")";
        } catch (Exception e) {
            return "Error generating or uploading PDF: " + e.getMessage();
        }
    }
    /**
     * 模拟上传到对象存储的辅助方法
     * 在真实项目中，你需要在这里注入你们公司的 OSSClient（如阿里云 OSS、腾讯云 COS 等）
     */
    private String uploadToObjectStorage(File file) {
        // TODO: 替换为你实际的 OSS/COS 上传逻辑
        // 示例: String url = aliyunOssClient.upload(file.getName(), file);
        return "https://your-oss-bucket-domain.com/pdf/" + file.getName();
    }
}