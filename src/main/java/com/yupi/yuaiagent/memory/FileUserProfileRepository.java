package com.yupi.yuaiagent.memory;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.yuaiagent.constant.FileConstant;
import org.springframework.stereotype.Repository;

import java.io.File;
import java.io.IOException;

/**
 * 用户画像文件仓库
 * 用来寻找、新增、保存用户画像
 */
@Repository
public class FileUserProfileRepository {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final File baseDirectory;

    public FileUserProfileRepository() {
        this.baseDirectory = new File(FileConstant.FILE_SAVE_DIR, "memory/profile");
        if (!this.baseDirectory.exists()) {
            this.baseDirectory.mkdirs();
        }
    }

    public UserProfile findByUserKey(String userId, String conversationId) {
        File file = profileFile(userId, conversationId);
        if (!file.exists()) {
            return null;
        }
        try {
            return objectMapper.readValue(file, UserProfile.class);
        } catch (IOException e) {
            throw new RuntimeException("读取用户画像失败: " + userId + "/" + conversationId, e);
        }
    }

    public void save(String userId, UserProfile userProfile) {
        File file = profileFile(userId, userProfile.getUserKey());
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, userProfile);
        } catch (IOException e) {
            throw new RuntimeException("保存用户画像失败: " + userId + "/" + userProfile.getUserKey(), e);
        }
    }

    /**
     * 复合键文件布局：memory/profile/{userId}/{conversationId}.json
     * userKey 传的是"会话维度"的 key（userProfile.getUserKey()）。
     *
     * @param userId 用户唯一标识（目录层）
     * @param userKey 会话维度标识（文件层）
     * @return 用户画像文件对象
     */
    private File profileFile(String userId, String userKey) {
        // 处理空值，默认使用 "default"
        String safeUserDir = StrUtil.blankToDefault(userId, "default")
                // 替换非法文件名字符，确保目录名安全
                .replaceAll("[^a-zA-Z0-9._-]", "_");
        String safeKey = StrUtil.blankToDefault(userKey, "anonymous")
                // 替换非法文件名字符，确保文件名安全
                .replaceAll("[^a-zA-Z0-9._-]", "_");
        File userDir = new File(baseDirectory, safeUserDir);
        if (!userDir.exists()) {
            userDir.mkdirs();
        }
        // 返回用户目录下的 JSON 文件
        return new File(userDir, safeKey + ".json");
    }
}
