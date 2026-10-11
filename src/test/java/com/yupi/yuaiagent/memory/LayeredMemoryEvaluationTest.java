package com.yupi.yuaiagent.memory;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 四层记忆效果评估测试（2026-10-11 单用户单会话版）。
 *
 * <p>默认不会执行，避免普通测试阶段误调用大模型。手动评估时设置环境变量：
 * RUN_MEMORY_EVAL=true
 *
 * <p><b>隔离方式随重构改变</b>：画像/摘要全局唯一一份后，无法再用「随机 eval 用户目录」隔离
 * （旧版 EVAL_USER_ID 的做法已随 userId 一起删除）——改为把 {@code yuai.memory.dir} 指到
 * target/ 下的本次运行专属目录：既不污染真实 tmp/memory，也不需要 @AfterEach 逐文件清理
 * （target/ 本身是一次性目录）。
 */
@SpringBootTest(properties = {
        "spring.main.lazy-initialization=true",
        "yuai.rag.bootstrap-enabled=false"
})
@EnabledIfEnvironmentVariable(named = "RUN_MEMORY_EVAL", matches = "true")
class LayeredMemoryEvaluationTest {

    /** 本次运行专属的记忆目录：单键化后唯一的隔离手段就是换根目录 */
    private static final String EVAL_MEMORY_DIR =
            "target/eval-memory/layered-" + java.util.UUID.randomUUID();

    @DynamicPropertySource
    static void memoryDir(DynamicPropertyRegistry registry) {
        registry.add("yuai.memory.dir", () -> EVAL_MEMORY_DIR);
    }

    @Resource
    private MemoryUpdateService memoryUpdateService;

    @Resource
    private MemoryAssembler memoryAssembler;

    @Resource
    private FileUserProfileRepository userProfileRepository;

    @Resource
    private FileRecentSummaryRepository recentSummaryRepository;

    @Resource
    private ConversationMemoryService conversationMemoryService;

    @BeforeEach
    void skipUnlessMemoryEvaluationEnabled() {
        Assumptions.assumeTrue(
                "true".equalsIgnoreCase(System.getenv("RUN_MEMORY_EVAL")),
                "未设置 RUN_MEMORY_EVAL=true，跳过分层记忆评估测试"
        );
    }

    @Test
    @DisplayName("评估：第二层用户画像、第三层近期摘要、前三层 Prompt 注入是否生效")
    void shouldUpdateProfileSummaryAndAssemblePromptSection() {
        String userMessage = "我现在单身，最近在相亲，希望你回答简洁一点。";
        String assistantMessage = "明白，我会用简洁方式给你相亲建议。";

        memoryUpdateService.recordLoveAppInteraction(
                null,
                userMessage,
                assistantMessage,
                "chat"
        );
        // 记忆写入自 2026-10 起走异步队列（画像 + 摘要 + 归档共 3 次 LLM/嵌入往返不再占用请求线程），
        // 断言前必须显式等队列排空，否则读到的是「还没写进去」的旧状态。
        assertTrue(memoryUpdateService.awaitPendingWrites(30_000), "记忆写入队列应该在 30 秒内排空");

        UserProfile profile = userProfileRepository.find();
        RecentSummary summary = recentSummaryRepository.find();
        String promptSection = memoryAssembler
                .assemble("love_app", "chat")
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
        memoryUpdateService.recordLoveAppInteraction(
                null,
                "我现在单身，最近在相亲，回答简单点。",
                "好的，我会简单给你相亲建议。",
                "chat"
        );
        memoryUpdateService.recordLoveAppInteraction(
                null,
                "更新一下，我现在和小美恋爱了，希望你之后回答详细一点。",
                "恭喜你进入恋爱状态，我后面会讲得更详细。",
                "chat"
        );
        // 两次写入是 FIFO 提交（单工作线程串行执行），等排空后读到的就是「后写覆盖先写」的结果
        assertTrue(memoryUpdateService.awaitPendingWrites(30_000), "记忆写入队列应该在 30 秒内排空");

        UserProfile profile = userProfileRepository.find();

        assertAll(
                () -> assertNotNull(profile, "第二层用户画像应该存在"),
                () -> assertEquals("恋爱", profile.getRelationshipStatus(), "新感情状态应该覆盖旧状态"),
                () -> assertEquals("详细", profile.getPreferredAnswerStyle(), "新回答偏好应该覆盖旧偏好")
        );
    }

    @Test
    @DisplayName("回归评估：外部传入 relationshipStatus 时，仍应抽取回答偏好和关注场景")
    void shouldStillExtractOtherProfileFieldsWhenRelationshipStatusProvided() {
        memoryUpdateService.recordLoveAppInteraction(
                "单身",
                "最近在相亲，希望你回答简洁一点。",
                "好的，我会用简洁方式给你相亲建议。",
                "rag"
        );
        assertTrue(memoryUpdateService.awaitPendingWrites(30_000), "记忆写入队列应该在 30 秒内排空");

        UserProfile profile = userProfileRepository.find();

        assertAll(
                () -> assertNotNull(profile, "第二层用户画像应该存在"),
                () -> assertEquals("单身", profile.getRelationshipStatus(), "外部传入的感情状态应该被保留"),
                () -> assertEquals("简洁", profile.getPreferredAnswerStyle(), "不应该因为传入 relationshipStatus 就跳过回答偏好抽取"),
                () -> assertTrue(profile.getNotableFacts().contains("近期关注相亲场景"), "不应该因为传入 relationshipStatus 就跳过关注场景抽取")
        );
    }

    @Test
    @DisplayName("评估（单用户单会话）：新会话（新 chatId / 清空前端状态后）首轮即可命中上一轮写入的画像")
    void shouldKeepProfileAcrossSessions() {
        // 上一轮：用户自报昵称与感情状态
        memoryUpdateService.recordLoveAppInteraction(
                null,
                "我叫小帅，我现在单身，希望你回答简洁一点。",
                "好的小帅，我会用简洁的方式回答。",
                "chat"
        );
        // 跨会话可读性的前提是「上一轮的写入已经落盘」——异步化后必须先等队列排空
        assertTrue(memoryUpdateService.awaitPendingWrites(30_000), "记忆写入队列应该在 30 秒内排空");

        // 新一轮：全新的会话（单会话语义下不再有键，读取同样无键），不写任何新信息，只读
        UserProfile profileInNewSession = userProfileRepository.find();
        String promptSection = memoryAssembler
                .assemble("love_app", "chat")
                .toPromptSection();

        assertAll(
                () -> assertNotNull(profileInNewSession, "新会话应该读到全局唯一的画像文件"),
                () -> assertEquals("小帅", profileInNewSession.getNickname(), "跨会话应该记得昵称"),
                () -> assertContains(promptSection, "nickname=小帅", "新会话的 Prompt 注入应该带上上一轮写入的画像")
        );
    }

    @Test
    @DisplayName("评估（P8 事故修复）：超长归档走「写入前截断」，不再触发 400 重试风暴（修复前实测 143~146 秒）")
    void oversizedArchiveNoLongerBurnsRetries() {
        String hugeUser = "这是一段用于触发嵌入上限的超长输入。".repeat(2000);
        String hugeAssistant = "这是一段用于触发嵌入上限的超长回复。".repeat(2000);

        long start = System.currentTimeMillis();
        conversationMemoryService.saveExchange(hugeUser, hugeAssistant, "love_app");
        long cost = System.currentTimeMillis() - start;

        System.out.printf("超长归档写入耗时: %d ms（修复前 143000~146000 ms）%n", cost);
        assertTrue(cost < 10_000,
                "归档前截断应让超长输入快速成功，实际耗时 " + cost + " ms —— 若接近 150s 说明截断失效、又在打 DashScope 400 重试");
    }

    private void assertContains(String actual, String expected, String message) {
        assertTrue(actual.contains(expected), message + "\n期望包含: " + expected + "\n实际内容:\n" + actual);
    }
}
