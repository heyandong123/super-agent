package com.yupi.yuaiagent.memory;

import cn.hutool.core.util.StrUtil;
import com.yupi.yuaiagent.constant.FileConstant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;

/**
 * 记忆文件仓库装配（2026-10-11 单用户单会话重构）
 * <p>
 * 三个 File*Repository 原先各自 {@code @Repository} + 无参构造器、硬编码
 * {@code tmp/memory/...} 目录；单会话收敛后目录只有一个根（{@code yuai.memory.dir}），
 * 改为本配置类统一装配——<b>根目录可注入</b>是测试隔离的前提：
 * 评测/单测用 {@code @DynamicPropertySource} 把目录指到 target/ 下，
 * 绝不污染用户真实的 tmp/memory（这个坑踩过一次：首轮评测把真实画像写成了评测语料）。
 */
@Configuration
public class MemoryStoreConfig {

    @Bean
    public FileUserProfileRepository fileUserProfileRepository(@Value("${yuai.memory.dir:}") String memoryDir) {
        return new FileUserProfileRepository(resolveMemoryDir(memoryDir));
    }

    @Bean
    public FileRecentSummaryRepository fileRecentSummaryRepository(@Value("${yuai.memory.dir:}") String memoryDir) {
        return new FileRecentSummaryRepository(resolveMemoryDir(memoryDir));
    }

    @Bean
    public FileConversationHistoryRepository fileConversationHistoryRepository(
            @Value("${yuai.memory.dir:}") String memoryDir,
            MemoryHistoryProperties historyProperties) {
        return new FileConversationHistoryRepository(resolveMemoryDir(memoryDir), historyProperties.getMaxTurns());
    }

    /** 未配置时回落与历史行为一致的默认目录：${user.dir}/tmp/memory */
    private static File resolveMemoryDir(String memoryDir) {
        return StrUtil.isBlank(memoryDir)
                ? new File(FileConstant.FILE_SAVE_DIR, "memory")
                : new File(memoryDir);
    }
}
