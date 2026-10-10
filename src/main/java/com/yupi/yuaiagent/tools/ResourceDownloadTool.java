/*
package com.yupi.yuaiagent.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.http.HttpUtil;
import com.yupi.yuaiagent.constant.FileConstant;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.File;

*/
/**
 * 资源下载工具
 *//*

public class ResourceDownloadTool {

    @Tool(description = "Download a resource from a given URL")
    public String downloadResource(@ToolParam(description = "URL of the resource to download") String url, @ToolParam(description = "Name of the file to save the downloaded resource") String fileName) {
        String fileDir = FileConstant.FILE_SAVE_DIR + "/download";
        String filePath = fileDir + "/" + fileName;
        try {
            // 创建目录
            FileUtil.mkdir(fileDir);
            // 使用 Hutool 的 downloadFile 方法下载资源
            HttpUtil.downloadFile(url, new File(filePath));
            return "Resource downloaded successfully to: " + filePath;
        } catch (Exception e) {
            return "Error downloading resource: " + e.getMessage();
        }
    }
}
*/

package com.yupi.yuaiagent.tools;
import cn.hutool.core.io.FileUtil;
import cn.hutool.http.HttpUtil;
import com.yupi.yuaiagent.constant.FileConstant;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import java.io.File;
/**
 * 资源下载工具（赋予大模型将网络文件下载到本地的权限）
 */
public class ResourceDownloadTool {

    /** 下载超时（毫秒）：防止 URL 无响应时把一次工具调用拖满外层 30s 上限 */
    private static final int DOWNLOAD_TIMEOUT_MS = 30_000;

    /** 单文件大小上限（字节）：下载后超限则删除并报错，防止磁盘被写满 */
    private static final long MAX_FILE_SIZE_BYTES = 20L * 1024 * 1024;

    // TODO 1: 添加大模型工具注解
    // 提示: 使用 @Tool(description = "Download a resource from a given URL")
    // 重构轮（2026-10）：描述写明协议 / 大小 / 超时限制与保存位置，减少无效调用
    @Tool(description = """
            Download a file from a URL into the sandbox directory tmp/download/ and return the saved path.
            Only http/https URLs; max 20MB; 30s timeout. Always give a file name with a proper extension
            (e.g. photo.jpg) so the file type is preserved.
            """)
    public String downloadResource(
            // TODO 2: 描述下载链接参数
            // 提示: 使用 @ToolParam(description = "URL of the resource to download")
            @ToolParam(description = "Direct URL of the resource (must start with http:// or https://)")
            String url,
            // TODO 3: 描述保存的文件名参数
            // 提示: 使用 @ToolParam(description = "Name of the file to save the downloaded resource")
            @ToolParam(description = "File name to save as, including extension (e.g. photo.jpg)")
            String fileName) {
        // 定义专属的下载沙箱目录，防止大模型把文件乱丢在系统里
        String fileDir = FileConstant.FILE_SAVE_DIR + "/download";
        // 重构轮：文件名同样走 ToolPathUtils（原为裸字符串拼接，绝对路径会被重复拼接、相对路径不设防）
        String filePath = ToolPathUtils.resolveFilePath(fileDir, fileName);
        // 护栏：只允许 http/https，杜绝 file:// 等协议读取本地文件
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            return "Error downloading resource: only http:// and https:// URLs are supported";
        }
        try {
            // TODO 4: 确保下载目录存在
            // 核心原理: 如果文件夹不存在，直接写入文件会报 FileNotFoundException。
            // 提示: 调用 FileUtil.mkdir(fileDir);
            FileUtil.mkdir(fileDir);
            // TODO 5: 执行核心下载动作
            // 核心原理: 在原生 Java 中，发起 HTTP 请求并处理字节流写入文件需要几十行代码。
            // 这里我们借助 Hutool 的强力封装，只需要一行代码就能搞定复杂的流转存。
            // 提示: 调用 HttpUtil.downloadFile()，传入 url 和 new File(filePath)
            // 重构轮：补超时参数（毫秒）+ 下载后大小检查，防挂死、防磁盘打满
            long size = HttpUtil.downloadFile(url, new File(filePath), DOWNLOAD_TIMEOUT_MS);
            if (size > MAX_FILE_SIZE_BYTES) {
                FileUtil.del(filePath);
                return "Error downloading resource: file exceeds the 20MB limit (size=" + size + " bytes), removed";
            }
            return "Resource downloaded successfully to: " + filePath + " (" + size + " bytes)";
        } catch (Exception e) {
            // 面试考点复习: 捕获异常并作为字符串返回给大模型，让大模型知道下载失败了（比如 404 或者网络不通）
            return "Error downloading resource: " + e.getMessage();
        }
    }
}