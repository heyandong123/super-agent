package com.yupi.yuaiagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;

/**
 * 用户画像文件仓库（第二层记忆：单份画像）
 * <p>
 * 布局演进（每一版都是「上一版解决了什么」的记录）：
 * <ol>
 *   <li>memory/profile/{userId}/{conversationId}.json —— 按会话存画像，「跨会话记不住用户」的根因；</li>
 *   <li>memory/profile/{userId}/profile.json —— 按用户存（2026-10），画像跨会话共享；</li>
 *   <li>{@code memory/profile.json} —— 单用户单会话（2026-10-11）：项目没有登录体系，
 *       userId 恒为 "default"，连目录层也是死复杂度；「所有会话视为一条连续会话」后
 *       画像全局只有一份。</li>
 * </ol>
 * <p>
 * 装配由 {@link MemoryStoreConfig} 提供（目录可注入，便于测试隔离），不再是 @Repository。
 */
@Slf4j
public class FileUserProfileRepository {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final File profileFile;

    /**
     * @param baseDirectory 记忆根目录（如 tmp/memory），画像文件固定为其中的 profile.json
     */
    public FileUserProfileRepository(File baseDirectory) {
        if (!baseDirectory.exists()) {
            baseDirectory.mkdirs();
        }
        this.profileFile = new File(baseDirectory, "profile.json");
    }

    /**
     * 读取画像（全局唯一一份）。
     *
     * @return 画像；文件不存在或损坏时返回 null（调用方走「新建画像」路径）
     */
    public UserProfile find() {
        if (!profileFile.exists()) {
            return null;
        }
        try {
            return objectMapper.readValue(profileFile, UserProfile.class);
        } catch (IOException e) {
            // 坏文件隔离：读到截断/非法 JSON 时不能把异常抛给主链路（旧实现会让整轮请求 500）。
            // 改名保留现场 + 返回 null，下一轮写入会重建一份干净画像。
            quarantine(e);
            return null;
        }
    }

    /**
     * 保存画像（原子写：先写临时文件再替换，避免半截 JSON）。
     */
    public void save(UserProfile userProfile) {
        try {
            AtomicJsonWriter.write(objectMapper, profileFile, userProfile, true);
        } catch (IOException e) {
            throw new RuntimeException("保存用户画像失败: " + profileFile, e);
        }
    }

    private void quarantine(IOException cause) {
        File broken = new File(profileFile.getParentFile(),
                profileFile.getName() + ".corrupt-" + System.currentTimeMillis());
        boolean renamed = profileFile.renameTo(broken);
        log.warn("用户画像文件损坏，已隔离（{}）: {}",
                renamed ? "重命名为 " + broken.getName() : "重命名失败，下次写入将覆盖", cause.getMessage());
    }
}
