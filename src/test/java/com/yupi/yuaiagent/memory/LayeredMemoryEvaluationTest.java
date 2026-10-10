package com.yupi.yuaiagent.memory;

import cn.hutool.core.util.StrUtil;
import com.yupi.yuaiagent.constant.FileConstant;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 四层记忆效果评估测试。
 *
 * <p>默认不会执行，避免普通测试阶段误调用大模型。手动评估时设置环境变量：
 * RUN_MEMORY_EVAL=true
 */
@SpringBootTest(properties = {
        "spring.main.lazy-initialization=true",
        "yuai.rag.bootstrap-enabled=false"
})
@EnabledIfEnvironmentVariable(named = "RUN_MEMORY_EVAL", matches = "true")
class LayeredMemoryEvaluationTest {

    /**
     * LoveApp 链路没有用户体系，production 的 5 参 recordLoveAppInteraction 重载把 userId 落成 "default"。
     * 断言必须用同一个复合键（userId + conversationId）回查，否则永远查不到画像与摘要。
     */
    private static final String EVAL_USER_ID = "default";

    @Resource
    private MemoryUpdateService memoryUpdateService;

    @Resource
    private MemoryAssembler memoryAssembler;

    @Resource
    private FileUserProfileRepository userProfileRepository;

    @Resource
    private FileRecentSummaryRepository recentSummaryRepository;

    private final List<String> generatedConversationIds = new ArrayList<>();

    @BeforeEach
    void skipUnlessMemoryEvaluationEnabled() {
        Assumptions.assumeTrue(
                "true".equalsIgnoreCase(System.getenv("RUN_MEMORY_EVAL")),
                "未设置 RUN_MEMORY_EVAL=true，跳过分层记忆评估测试"
        );
    }

    @AfterEach
    void cleanupGeneratedMemoryFiles() throws IOException {
        for (String conversationId : generatedConversationIds) {
            Files.deleteIfExists(memoryFile("profile", conversationId));
            Files.deleteIfExists(memoryFile("summary", conversationId));
        }
    }

    @Test
    @DisplayName("评估：第二层用户画像、第三层近期摘要、前三层 Prompt 注入是否生效")
    void shouldUpdateProfileSummaryAndAssemblePromptSection() {
        System.out.println("RUN_MEMORY_EVAL = " + System.getenv("RUN_MEMORY_EVAL"));
        System.out.println("开始干活");
        String conversationId = newConversationId();
        String userMessage = "我现在单身，最近在相亲，希望你回答简洁一点。";
        String assistantMessage = "明白，我会用简洁方式给你相亲建议。";

        memoryUpdateService.recordLoveAppInteraction(
                conversationId,
                null,
                userMessage,
                assistantMessage,
                "chat"
        );

        UserProfile profile = userProfileRepository.findByUserKey(EVAL_USER_ID, conversationId);
        RecentSummary summary = recentSummaryRepository.findByConversationKey(EVAL_USER_ID, conversationId);
        String promptSection = memoryAssembler
                .assemble(EVAL_USER_ID, conversationId, "love_app", "chat")
                .toPromptSection();

        assertAll(
                () -> assertNotNull(profile, "第二层用户画像应该被创建"),
                () -> assertEquals("单身", profile.getRelationshipStatus(), "应该识别出用户感情状态"),
                () -> assertEquals("简洁", profile.getPreferredAnswerStyle(), "应该识别出用户回答偏好"),
                () -> assertTrue(profile.getNotableFacts().contains("近期关注相亲场景"), "应该识别出用户关注相亲场景"),
                () -> assertNotNull(summary, "第三层近期摘要应该被创建"),
                () -> assertTrue(StrUtil.isNotBlank(summary.getSummary()), "第三层近期摘要不应该为空"),
                () -> assertContains(promptSection, "[Session Metadata]", "第一层会话元信息应该进入运行时 Prompt"),
                () -> assertContains(promptSection, "[User Profile]", "第二层用户画像应该进入运行时 Prompt"),
                () -> assertContains(promptSection, "[Recent Summary]", "第三层近期摘要应该进入运行时 Prompt"),
                () -> assertContains(promptSection, "relationshipStatus=单身", "Prompt 应该包含感情状态"),
                () -> assertContains(promptSection, "preferredAnswerStyle=简洁", "Prompt 应该包含回答偏好"),
                () -> assertContains(promptSection, "近期关注相亲场景", "Prompt 应该包含关注场景")
        );
    }

    @Test
    @DisplayName("评估：用户状态变化后，第二层画像应该用新状态覆盖旧状态")
    void shouldRefreshProfileWhenUserStatusChanges() {
        String conversationId = newConversationId();

        memoryUpdateService.recordLoveAppInteraction(
                conversationId,
                null,
                "我现在单身，最近在相亲，回答简单点。",
                "好的，我会简单给你相亲建议。",
                "chat"
        );
        memoryUpdateService.recordLoveAppInteraction(
                conversationId,
                null,
                "更新一下，我现在和小美恋爱了，希望你之后回答详细一点。",
                "恭喜你进入恋爱状态，我后面会讲得更详细。",
                "chat"
        );

        UserProfile profile = userProfileRepository.findByUserKey(EVAL_USER_ID, conversationId);

        assertAll(
                () -> assertNotNull(profile, "第二层用户画像应该存在"),
                () -> assertEquals("恋爱", profile.getRelationshipStatus(), "新感情状态应该覆盖旧状态"),
                () -> assertEquals("详细", profile.getPreferredAnswerStyle(), "新回答偏好应该覆盖旧偏好")
        );
    }

    @Test
    @DisplayName("回归评估：外部传入 relationshipStatus 时，仍应抽取回答偏好和关注场景")
    void shouldStillExtractOtherProfileFieldsWhenRelationshipStatusProvided() {
        String conversationId = newConversationId();

        memoryUpdateService.recordLoveAppInteraction(
                conversationId,
                "单身",
                "最近在相亲，希望你回答简洁一点。",
                "好的，我会用简洁方式给你相亲建议。",
                "rag"
        );

        UserProfile profile = userProfileRepository.findByUserKey(EVAL_USER_ID, conversationId);

        assertAll(
                () -> assertNotNull(profile, "第二层用户画像应该存在"),
                () -> assertEquals("单身", profile.getRelationshipStatus(), "外部传入的感情状态应该被保留"),
                () -> assertEquals("简洁", profile.getPreferredAnswerStyle(), "不应该因为传入 relationshipStatus 就跳过回答偏好抽取"),
                () -> assertTrue(profile.getNotableFacts().contains("近期关注相亲场景"), "不应该因为传入 relationshipStatus 就跳过关注场景抽取")
        );
    }

    private String newConversationId() {
        String conversationId = "memory-eval-" + UUID.randomUUID();
        generatedConversationIds.add(conversationId);
        return conversationId;
    }

    private Path memoryFile(String memoryType, String conversationId) {
        String safeKey = StrUtil.blankToDefault(conversationId, "anonymous")
                .replaceAll("[^a-zA-Z0-9._-]", "_");
        // 复合键目录布局：memory/{profile|summary}/{userId}/{conversationId}.json
        // 以前漏掉了 userId 这一层，路径永远指向不存在的文件，@AfterEach 其实一直没删掉过东西
        return Path.of(FileConstant.FILE_SAVE_DIR, "memory", memoryType, EVAL_USER_ID, safeKey + ".json");
    }

    private void assertContains(String actual, String expected, String message) {
        assertTrue(actual.contains(expected), message + "\n期望包含: " + expected + "\n实际内容:\n" + actual);
    }
}
