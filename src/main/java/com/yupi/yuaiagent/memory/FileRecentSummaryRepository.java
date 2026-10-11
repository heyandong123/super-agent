package com.yupi.yuaiagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;

/**
 * 近期摘要文件仓库（第三层记忆：单份滚动摘要）
 * <p>
 * 布局演进：memory/summary/{userId}/{conversationId}.json（复合键、按会话隔离）
 * → {@code memory/summary.json}（2026-10-11 单用户单会话）：所有会话视为一条连续会话后，
 * 摘要承载的是「全程历史的滚动压缩」，全局只有一份——它正是「全量加载上一次会话历史」
 * 里那个"全程"的落点（近场原文由 history.jsonl 负责，见 {@link ConversationTurn}）。
 * <p>
 * 装配由 {@link MemoryStoreConfig} 提供（目录可注入，便于测试隔离），不再是 @Repository。
 */
@Slf4j
public class FileRecentSummaryRepository {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final File summaryFile;

    /**
     * @param baseDirectory 记忆根目录（如 tmp/memory），摘要文件固定为其中的 summary.json
     */
    public FileRecentSummaryRepository(File baseDirectory) {
        if (!baseDirectory.exists()) {
            baseDirectory.mkdirs();
        }
        this.summaryFile = new File(baseDirectory, "summary.json");
    }

    public RecentSummary find() {
        if (!summaryFile.exists()) {
            return null;
        }
        try {
            return objectMapper.readValue(summaryFile, RecentSummary.class);
        } catch (IOException e) {
            // 与画像仓库一致：坏文件隔离 + 返回 null，不让一个坏 JSON 打挂整轮请求
            File broken = new File(summaryFile.getParentFile(),
                    summaryFile.getName() + ".corrupt-" + System.currentTimeMillis());
            boolean renamed = summaryFile.renameTo(broken);
            log.warn("近期摘要文件损坏，已隔离（{}）: {}",
                    renamed ? "重命名为 " + broken.getName() : "重命名失败，下次写入将覆盖", e.getMessage());
            return null;
        }
    }

    public void save(RecentSummary recentSummary) {
        try {
            AtomicJsonWriter.write(objectMapper, summaryFile, recentSummary, true);
        } catch (IOException e) {
            throw new RuntimeException("保存近期摘要失败: " + summaryFile, e);
        }
    }
}
