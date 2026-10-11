package com.yupi.yuaiagent.context;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yupi.yuaiagent.memory.AtomicJsonWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 压缩前的对话落盘（2026-10 上下文重构 · 阶段 2）
 * <p>
 * 对标 Claude Code 的 transcript JSONL（{@code /resume} 能从磁盘恢复完整对话）。
 * 本项目只做<b>有界版</b>：每会话最多留 {@code transcript-keep-per-conversation} 份，不做恢复命令——
 * 理由是"不可逆操作前先留证据"：压缩一旦把原文丢了，出问题连复盘的材料都没有。
 * <p>
 * 2026-10-11：目录去掉 userId 层（单用户后它恒为 default，是死目录），
 * 只剩 {@code tmp/transcripts/{conversationId}/}。
 * <p>
 * 落盘格式：一行一条消息的 JSONL（工具输出取 {@code getResponses()}——它的 {@code getText()} 恒为空串，
 * 这个坑见 {@link ContextTokenEstimator#estimate(Message)} 的注释）。
 * 写入走 {@link AtomicJsonWriter} 之外的独立实现（JSONL 是追加语义的行文件，不是整份对象覆写）。
 * <p>
 * 全程 fail-open：落盘失败只 warn，绝不阻塞压缩本身（压缩失效会把上下文撑爆，比丢一份证据严重得多）。
 */
@Component
@Slf4j
public class TranscriptWriter {

    private final ContextProperties properties;

    private final ObjectMapper mapper = new ObjectMapper();

    public TranscriptWriter(ContextProperties properties) {
        this.properties = properties;
    }

    /**
     * 把当前完整对话写成一份 transcript。
     *
     * @param reason 触发原因（如 "microcompact" / "compact"），进文件名，便于事后分辨
     * @return 落盘路径；未启用或失败时返回 null
     */
    public Path write(String conversationId, int step, String reason, List<Message> messages) {
        if (!properties.isTranscriptEnabled() || messages == null || messages.isEmpty()) {
            return null;
        }
        try {
            Path directory = conversationDirectory(conversationId);
            Files.createDirectories(directory);
            String fileName = String.format("step-%03d-%d-%s.jsonl", step, Instant.now().toEpochMilli(),
                    StrUtil.blankToDefault(reason, "compact"));
            Path target = directory.resolve(fileName);
            List<String> lines = new ArrayList<>();
            for (Message message : messages) {
                lines.add(mapper.writeValueAsString(toRecord(message)));
            }
            AtomicJsonWriter.writeLines(target, lines);
            prune(directory);
            return target;
        } catch (Exception e) {
            log.warn("transcript 落盘失败（不影响压缩）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 目录裁剪：只保留最近 N 份（按文件名里的时间戳排序，文件名前缀即时间序）。
     */
    void prune(Path directory) {
        int keep = Math.max(properties.getTranscriptKeepPerConversation(), 1);
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> sorted = files
                    .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            for (int i = 0; i < sorted.size() - keep; i++) {
                Files.deleteIfExists(sorted.get(i));
            }
        } catch (Exception e) {
            log.warn("transcript 裁剪失败（不影响主链路）: {}", e.getMessage());
        }
    }

    /** 会话目录：{@code tmp/transcripts/{conversationId}}，做文件名清洗 */
    Path conversationDirectory(String conversationId) {
        return Paths.get(properties.getTranscriptDir(),
                sanitize(conversationId, "anonymous"));
    }

    /**
     * 渲染成可读的 JSON 记录：文本 + 工具调用 + 工具结果（工具结果必须取 responses，
     * 否则落下来的 transcript 里工具输出全是空的——和旧压缩器踩的是同一个坑）。
     */
    private Map<String, Object> toRecord(Message message) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("type", String.valueOf(message.getMessageType()));
        if (message instanceof ToolResponseMessage toolResponseMessage) {
            List<Map<String, String>> responses = new ArrayList<>();
            for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("id", response.id());
                item.put("name", response.name());
                item.put("data", response.responseData());
                responses.add(item);
            }
            record.put("toolResponses", responses);
            return record;
        }
        if (message instanceof AssistantMessage assistantMessage) {
            record.put("text", assistantMessage.getText());
            if (!assistantMessage.getToolCalls().isEmpty()) {
                List<Map<String, String>> toolCalls = new ArrayList<>();
                for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("id", toolCall.id());
                    item.put("name", toolCall.name());
                    item.put("arguments", toolCall.arguments());
                    toolCalls.add(item);
                }
                record.put("toolCalls", toolCalls);
            }
            return record;
        }
        record.put("text", message.getText());
        return record;
    }

    /** 与记忆模块同款清洗：目录/文件名只留安全字符，空值回落占位 */
    private static String sanitize(String value, String fallback) {
        String resolved = StrUtil.blankToDefault(value, fallback);
        return resolved.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

}
