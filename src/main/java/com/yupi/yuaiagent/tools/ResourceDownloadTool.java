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
    // TODO 1: 添加大模型工具注解
    // 提示: 使用 @Tool(description = "Download a resource from a given URL")
    @Tool(description = "Download a resource from a given URL")
    public String downloadResource(
            // TODO 2: 描述下载链接参数
            // 提示: 使用 @ToolParam(description = "URL of the resource to download")
            @ToolParam(description = "URL of the resource to download")
            String url,
            // TODO 3: 描述保存的文件名参数
            // 提示: 使用 @ToolParam(description = "Name of the file to save the downloaded resource")
            @ToolParam(description = "Name of the file to save the downloaded resource")
            String fileName) {
        // 定义专属的下载沙箱目录，防止大模型把文件乱丢在系统里
        String fileDir = FileConstant.FILE_SAVE_DIR + "/download";
        String filePath = fileDir + "/" + fileName;
        try {
            // TODO 4: 确保下载目录存在
            // 核心原理: 如果文件夹不存在，直接写入文件会报 FileNotFoundException。
            // 提示: 调用 FileUtil.mkdir(fileDir);
            FileUtil.mkdir(fileDir);
            // TODO 5: 执行核心下载动作
            // 核心原理: 在原生 Java 中，发起 HTTP 请求并处理字节流写入文件需要几十行代码。
            // 这里我们借助 Hutool 的强力封装，只需要一行代码就能搞定复杂的流转存。
            // 提示: 调用 HttpUtil.downloadFile()，传入 url 和 new File(filePath)
            HttpUtil.downloadFile(url,new File(filePath));
            return "Resource downloaded successfully to: " + filePath;
        } catch (Exception e) {
            // 面试考点复习: 捕获异常并作为字符串返回给大模型，让大模型知道下载失败了（比如 404 或者网络不通）
            return "Error downloading resource: " + e.getMessage();
        }
    }
}