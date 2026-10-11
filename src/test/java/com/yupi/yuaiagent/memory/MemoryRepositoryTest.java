package com.yupi.yuaiagent.memory;

import com.yupi.yuaiagent.context.ContextTokenEstimator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记忆文件仓库离线回归（2026-10-11 单用户单会话重构后）：
 * <ul>
 *   <li>单键化：画像 / 摘要各全局一份，固定文件名 profile.json / summary.json，不再有目录层</li>
 *   <li>原子写：写入后不留 .tmp 残留，覆盖写内容正确</li>
 *   <li>坏文件隔离：读到非法 JSON 返回 null 并改名留存，而不是抛异常打挂整轮请求</li>
 *   <li>旧字段兼容：老画像文件里的 userKey/userId 字段被忽略而不是反序列化失败</li>
 *   <li>归档文本截断：任意长度输入都落在嵌入模型单条上限内（P8 事故的硬保证）</li>
 * </ul>
 * 全部离线：使用 @TempDir，不碰真实 sandbox、不需要 Spring 容器。
 */
@Tag("unit")
class MemoryRepositoryTest {

    @TempDir
    Path tempDir;

    private FileUserProfileRepository newProfileRepository() {
        return new FileUserProfileRepository(tempDir.toFile());
    }

    @Test
    @DisplayName("画像单份存：布局就是 memory/profile.json，不带任何目录层/键")
    void profileIsStoredAsSingleFile() throws Exception {
        FileUserProfileRepository repository = newProfileRepository();

        repository.save(UserProfile.builder()
                .nickname("小帅")
                .notableFacts(List.of("近期关注相亲场景"))
                .build());
        // 第二轮读：无论哪个「会话」来读（单会话语义下不再有会话概念）都拿到同一份画像
        UserProfile loaded = repository.find();

        assertNotNull(loaded);
        assertEquals("小帅", loaded.getNickname());
        assertEquals(List.of("近期关注相亲场景"), loaded.getNotableFacts());
        // 钉住布局：profile.json 直接位于记忆根目录下
        // （v1 是 {userId}/{conversationId}.json、v2 是 {userId}/profile.json，两者都会随会话数增长）
        assertTrue(Files.exists(tempDir.resolve("profile.json")),
                "画像文件应位于 " + tempDir.resolve("profile.json"));
        try (Stream<Path> files = Files.list(tempDir)) {
            assertEquals(List.of("profile.json"),
                    files.map(p -> p.getFileName().toString()).sorted().toList(),
                    "记忆根目录下应只有目标文件，无目录层、无 .tmp/.corrupt 残留");
        }
    }

    @Test
    @DisplayName("画像原子写：不留 .tmp 残留，覆盖写后内容是新值")
    void atomicWriteLeavesNoTempFileAndOverwrites() throws Exception {
        FileUserProfileRepository repository = newProfileRepository();
        repository.save(UserProfile.builder().nickname("旧昵称").build());
        repository.save(UserProfile.builder().nickname("新昵称").build());

        assertEquals("新昵称", repository.find().getNickname());
        try (Stream<Path> files = Files.list(tempDir)) {
            List<String> names = files.map(p -> p.getFileName().toString()).toList();
            assertEquals(List.of("profile.json"), names, "目录里应只有目标文件，无 .tmp/.corrupt 残留");
        }
    }

    @Test
    @DisplayName("画像坏文件隔离：非法 JSON 返回 null 并改名留存，不抛异常")
    void corruptProfileIsQuarantined() throws Exception {
        FileUserProfileRepository repository = newProfileRepository();
        // 模拟半截 JSON（进程在写一半时被杀留下的现场）
        Files.writeString(tempDir.resolve("profile.json"),
                "{\"nickname\":\"小帅\",\"nick", StandardCharsets.UTF_8);

        UserProfile loaded = repository.find();

        assertNull(loaded, "坏文件应降级为 null，由调用方走新建画像路径");
        try (Stream<Path> files = Files.list(tempDir)) {
            List<String> names = files.map(p -> p.getFileName().toString()).toList();
            assertTrue(names.stream().anyMatch(n -> n.startsWith("profile.json.corrupt-")),
                    "坏文件应被改名留存以便排查，实际: " + names);
        }
        // 隔离后再次写入应恢复正常
        repository.save(UserProfile.builder().nickname("重建").build());
        assertEquals("重建", repository.find().getNickname());
    }

    @Test
    @DisplayName("旧字段兼容：老画像文件里的 userKey/userId 被忽略而不是解析失败")
    void legacyKeyFieldsAreIgnored() throws Exception {
        FileUserProfileRepository repository = newProfileRepository();
        Files.writeString(tempDir.resolve("profile.json"),
                "{\"userKey\":\"old-conversation-id\",\"userId\":\"default\",\"nickname\":\"老画像\",\"unknownField\":1}",
                StandardCharsets.UTF_8);

        UserProfile loaded = repository.find();

        assertNotNull(loaded, "老文件不应解析失败（@JsonIgnoreProperties）");
        assertEquals("老画像", loaded.getNickname());
    }

    @Test
    @DisplayName("摘要单份存：summary.json 覆写滚动更新，不随会话数增长")
    void summaryIsStoredAsSingleFile() throws Exception {
        FileRecentSummaryRepository repository = new FileRecentSummaryRepository(tempDir.toFile());

        assertNull(repository.find(), "未写过时返回 null，由调用方走「无历史摘要」路径");
        repository.save(RecentSummary.builder().summary("第一版摘要").build());
        repository.save(RecentSummary.builder().summary("第二版摘要（滚动合并）").build());

        assertEquals("第二版摘要（滚动合并）", repository.find().getSummary());
        try (Stream<Path> files = Files.list(tempDir)) {
            assertEquals(List.of("summary.json"),
                    files.map(p -> p.getFileName().toString()).sorted().toList(),
                    "摘要应固定在 memory/summary.json，不产生目录层");
        }
    }

    @Test
    @DisplayName("归档截断：任意超长对话的归档文本都落在嵌入客户端闸门内（防 400 → 146s 重试风暴）")
    void oversizedArchiveIsTruncatedWithinEmbeddingLimit() {
        String hugeUser = "这是用户的一段超长输入，用来触发嵌入模型单条上限。".repeat(2000);
        String hugeAssistant = "这是助手的一段超长回复。".repeat(2000);

        String archive = ConversationMemoryService.buildArchiveText(hugeUser, hugeAssistant);

        // 用精确口径断言（CL100K，与 TokenCountBatchingStrategy 同源）：
        // 客户端闸门 = 2048 × (1 - 0.1 预留) = 1843，截到 1800 精确 token 才一定过得了。
        // 曾经用启发式「上界口径」截到 1800，实测约 2000 token，照样被拒 → fail-open 静默丢记忆。
        assertTrue(ContextTokenEstimator.countTokensExact(archive) <= 1800,
                "归档文本精确 token 数必须 ≤1800，实际 " + ContextTokenEstimator.countTokensExact(archive));
        assertTrue(archive.contains("…（归档已截断）"), "确实发生了截断");
        assertTrue(archive.contains(hugeUser.substring(0, 100)),
                "用户段是检索锚点，截断后必须保住开头而不是直接丢空");
    }

    @Test
    @DisplayName("归档截断：正常长度的对话原样归档（不误伤）")
    void normalArchiveIsKeptIntact() {
        String archive = ConversationMemoryService.buildArchiveText("你好", "你好，有什么可以帮你？");

        assertEquals("""
                [User]
                你好

                [Assistant]
                你好，有什么可以帮你？
                """, archive);
    }

    @Test
    @DisplayName("AtomicJsonWriter：目录不存在时自动创建，且能写入任意可序列化对象")
    void atomicWriterCreatesDirectories() throws Exception {
        File target = new File(tempDir.toFile(), "nested/dir/data.json");
        AtomicJsonWriter.write(new com.fasterxml.jackson.databind.ObjectMapper(), target,
                java.util.Map.of("k", "v"), true);

        assertTrue(target.exists());
        try (Stream<Path> files = Files.list(target.getParentFile().toPath())) {
            List<String> names = files.map(p -> p.getFileName().toString()).toList();
            assertEquals(List.of("data.json"), names, "目录里应只有目标文件，临时文件必须被清理");
        }
        assertTrue(Files.readString(target.toPath()).contains("\"k\""));
    }
}
