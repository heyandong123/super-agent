package com.yupi.yuaiagent.memory;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.yuaiagent.constant.FileConstant;
import org.springframework.stereotype.Repository;

import java.io.File;
import java.io.IOException;

/**
 * 近期摘要文件仓库
 */
@Repository
public class FileRecentSummaryRepository {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final File baseDirectory;

    public FileRecentSummaryRepository() {
        this.baseDirectory = new File(FileConstant.FILE_SAVE_DIR, "memory/summary");
        if (!this.baseDirectory.exists()) {
            this.baseDirectory.mkdirs();
        }
    }

    public RecentSummary findByConversationKey(String userId, String conversationId) {
        File file = summaryFile(userId, conversationId);
        if (!file.exists()) {
            return null;
        }
        try {
            return objectMapper.readValue(file, RecentSummary.class);
        } catch (IOException e) {
            throw new RuntimeException("读取近期摘要失败: " + userId + "/" + conversationId, e);
        }
    }

    public void save(String userId, RecentSummary recentSummary) {
        File file = summaryFile(userId, recentSummary.getConversationId());
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, recentSummary);
        } catch (IOException e) {
            throw new RuntimeException("保存近期摘要失败: " + userId + "/" + recentSummary.getConversationId(), e);
        }
    }

    /**
     * 复合键文件布局：memory/summary/{userId}/{conversationId}.json
     * 用户维度隔离在目录层，会话维度隔离在文件层。
     */
    private File summaryFile(String userId, String conversationId) {
        String safeUserDir = StrUtil.blankToDefault(userId, "default")
                .replaceAll("[^a-zA-Z0-9._-]", "_");
        String safeKey = StrUtil.blankToDefault(conversationId, "anonymous")
                .replaceAll("[^a-zA-Z0-9._-]", "_");
        File userDir = new File(baseDirectory, safeUserDir);
        if (!userDir.exists()) {
            userDir.mkdirs();
        }
        return new File(userDir, safeKey + ".json");
    }
}
