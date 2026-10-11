package com.yupi.yuaiagent.memory;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 对话历史文件仓库（history.jsonl，单用户单会话连续模型的近场恢复源）
 * <p>
 * <b>格式</b>：JSONL，一行一条 {@link ConversationTurn}——追加语义的行文件，
 * 与画像/摘要的「整份覆写」不同，这里每次只 append 一行，仅在超过容量上限时原子裁剪。
 * <p>
 * <b>为什么不走 MemoryWriteQueue</b>：① 恢复读取发生在下一个 run 的起始，
 * 如果写入还在异步队列里排队，会出现「读了旧内容、写入才落地」的双通道竞态；
 * ② {@link AtomicJsonWriter} 的单写者假设（单线程池串行写）在这里不成立——
 * 同步写发生在多个请求线程上。所以本类用<b>单临界区锁</b>把 append + 裁剪保护起来，
 * 调用方（{@code MemoryUpdateService.appendHistory}）负责 fail-open。
 * <p>
 * <b>为什么每次 append 都要读一遍文件</b>：上限只有 max-turns（默认 200）行，
 * 读完即知是否越限；省掉读的代价是要维护一个额外的计数状态（还得处理崩溃后计数对不上），
 * 这点 I/O 换掉一类状态不一致，划算。
 */
@Slf4j
public class FileConversationHistoryRepository {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final File historyFile;

    private final int maxTurns;

    /** 单临界区锁：append 与裁剪必须原子成对，否则并发 append 会把裁剪丢掉的行写回来 */
    private final Object lock = new Object();

    public FileConversationHistoryRepository(File baseDirectory, int maxTurns) {
        if (!baseDirectory.exists()) {
            baseDirectory.mkdirs();
        }
        this.historyFile = new File(baseDirectory, "history.jsonl");
        this.maxTurns = Math.max(maxTurns, 1);
    }

    /**
     * 读取最近 n 轮（文件顺序即时间序，取尾部 n 行）。
     * 坏行跳过不抛异常：一行坏数据不该让整段历史不可用。
     */
    public List<ConversationTurn> loadRecentTurns(int n) {
        if (n <= 0) {
            return List.of();
        }
        synchronized (lock) {
            List<String> lines = readLines();
            int from = Math.max(0, lines.size() - n);
            List<ConversationTurn> turns = new ArrayList<>();
            int malformed = 0;
            for (String line : lines.subList(from, lines.size())) {
                try {
                    ConversationTurn turn = objectMapper.readValue(line, ConversationTurn.class);
                    if (turn != null && (StrUtil.isNotBlank(turn.user()) || StrUtil.isNotBlank(turn.assistant()))) {
                        turns.add(turn);
                    }
                } catch (Exception e) {
                    malformed++;
                }
            }
            if (malformed > 0) {
                log.warn("history.jsonl 有 {} 行无法解析，已跳过（不影响其余历史）", malformed);
            }
            return turns;
        }
    }

    /**
     * 追加一轮对话；越限时原子裁剪为最近 maxTurns 轮。
     * 失败抛出 IOException（由调用方决定 fail-open 策略）。
     */
    public void append(ConversationTurn turn) throws IOException {
        if (turn == null) {
            return;
        }
        String line = objectMapper.writeValueAsString(turn);
        synchronized (lock) {
            Files.writeString(historyFile.toPath(), line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
            trimIfNeeded();
        }
    }

    /** 超过容量上限时原子重写（保留最近 maxTurns 轮）；未越限时零拷贝早退 */
    private void trimIfNeeded() throws IOException {
        List<String> lines = readLines();
        if (lines.size() <= maxTurns) {
            return;
        }
        List<String> kept = new ArrayList<>(lines.subList(lines.size() - maxTurns, lines.size()));
        AtomicJsonWriter.writeLines(historyFile.toPath(), kept);
    }

    private List<String> readLines() {
        Path path = historyFile.toPath();
        if (!Files.exists(path)) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(Files.readAllLines(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("history.jsonl 读取失败（按空历史继续）: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
}
