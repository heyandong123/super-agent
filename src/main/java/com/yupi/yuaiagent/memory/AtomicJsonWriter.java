package com.yupi.yuaiagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * JSON 文件原子写入工具（记忆层所有落盘统一走这里）
 * <p>
 * 为什么需要它：直接 {@code writeValue(file, ...)} 是「边序列化边写目标文件」，
 * 进程在写一半时被杀 / 磁盘满，会留下一个截断的 JSON。下次读取那条路径时
 * 反序列化失败——更糟的是旧实现把这个 IOException 直接包成 RuntimeException 抛给主链路，
 * 一个坏文件就能让整轮对话 500。
 * <p>
 * 做法（经典 write-temp-then-rename）：先写同目录下的临时文件，写完整后再原子替换目标。
 * 同目录很关键——跨文件系统的 move 不是原子的；同目录下 POSIX rename 才是。
 * <p>
 * 已知限制：不做跨进程文件锁（单写者假设：每个文件由单一线程池串行写；
 * 部署模型是单实例，跨进程并发不在范围内）。
 */
@Slf4j
public final class AtomicJsonWriter {

    private AtomicJsonWriter() {
    }

    /**
     * 原子写入 JSON。
     *
     * @param objectMapper 序列化器
     * @param target       目标文件（父目录不存在会自动创建）
     * @param value        待写入对象
     * @param pretty       是否格式化输出（画像/摘要这类人要看懂的文件用 true）
     * @throws IOException 写临时文件或替换失败（调用方决定是否 fail-open）
     */
    public static void write(ObjectMapper objectMapper, File target, Object value, boolean pretty) throws IOException {
        File absolute = target.getAbsoluteFile();
        File dir = absolute.getParentFile();
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }
        // 临时文件也放在同目录，保证 rename 不跨文件系统
        File tmp = File.createTempFile(absolute.getName() + ".", ".tmp", dir);
        try {
            if (pretty) {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp, value);
            } else {
                objectMapper.writeValue(tmp, value);
            }
            moveIntoPlace(tmp, absolute);
        } finally {
            // 替换成功后 tmp 已不存在；失败路径上清掉残留（best-effort）
            try {
                Files.deleteIfExists(tmp.toPath());
            } catch (IOException e) {
                log.debug("清理临时文件失败（忽略）: {}", tmp);
            }
        }
    }

    /**
     * 原子写入多行文本（JSONL：一行一条记录）。
     * <p>
     * 与 {@link #write} 共用同一套 write-temp-then-rename 语义：transcript 是"压缩前留的证据"，
     * 半截文件比没有文件更误导（看起来有内容，实际缺一半）。
     *
     * @param target 目标文件（父目录不存在会自动创建）
     * @param lines  逐行内容（不含换行符，由本方法统一补 \n）
     * @throws IOException 写临时文件或替换失败
     */
    public static void writeLines(Path target, java.util.List<String> lines) throws IOException {
        File absolute = target.toFile().getAbsoluteFile();
        File dir = absolute.getParentFile();
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }
        File tmp = File.createTempFile(absolute.getName() + ".", ".tmp", dir);
        try {
            StringBuilder builder = new StringBuilder();
            for (String line : lines) {
                builder.append(line).append('\n');
            }
            Files.writeString(tmp.toPath(), builder.toString(), java.nio.charset.StandardCharsets.UTF_8);
            moveIntoPlace(tmp, absolute);
        } finally {
            try {
                Files.deleteIfExists(tmp.toPath());
            } catch (IOException e) {
                log.debug("清理临时文件失败（忽略）: {}", tmp);
            }
        }
    }

    private static void moveIntoPlace(File tmp, File target) throws IOException {
        Path tmpPath = tmp.toPath();
        Path targetPath = target.toPath();
        try {
            Files.move(tmpPath, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // 个别文件系统（部分网络盘 / 容器挂载）不支持原子移动：
            // 降级为普通替换——仍保留「先写完整临时文件」的语义，只是极小概率下不再是原子的
            log.debug("文件系统不支持原子移动，降级为普通替换: {}", targetPath);
            Files.move(tmpPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
