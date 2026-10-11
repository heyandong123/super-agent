package com.yupi.yuaiagent.memory;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用户画像抽取评估。
 *
 * <p>会真实调用 LLM（抽取画像 + 生成摘要），所以默认不执行，
 * 手动评估时设置环境变量：RUN_MEMORY_EVAL=true
 * <p>
 * 2026-10-11 单用户单会话重构：画像/摘要全局唯一一份，无法再用「随机 userId」做隔离——
 * 改为把 {@code yuai.memory.dir} 指到 target/ 下的本次运行专属目录（@DynamicPropertySource），
 * 保证①评估绝不污染真实 tmp/memory（这个坑踩过一次：首轮评测把真实画像写成了评测语料）；
 * ②每次运行从空画像开始，断言有意义。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_MEMORY_EVAL", matches = "true")
class MemoryUpdateServiceTest {

    /** 本次运行专属的记忆目录：单键化后唯一的隔离手段就是换根目录 */
    private static final String EVAL_MEMORY_DIR =
            "target/eval-memory/memory-update-" + java.util.UUID.randomUUID();

    @DynamicPropertySource
    static void memoryDir(DynamicPropertyRegistry registry) {
        registry.add("yuai.memory.dir", () -> EVAL_MEMORY_DIR);
    }

    @Resource
    MemoryUpdateService memoryUpdateService;

    @Resource
    FileUserProfileRepository userProfileRepository;

    @Test
    @DisplayName("评估：relationshipStatus 传 null 时，仍应从对话中抽取回答偏好")
    void updateUserProfileTest() {
        // 这里刻意直接调 updateUserProfile（画像抽取的最小同步单元：内部直接 save，不投队列）：
        // 本用例要测的是「抽取质量」，异步链路由 MemoryWriteQueueTest 与
        // LayeredMemoryEvaluationTest（record* + awaitPendingWrites）覆盖。
        memoryUpdateService.updateUserProfile(null,
                "我和小美恋爱了，但是我们离得很远，我不想这种状态，所以分手了，现在在参加相亲，希望能找一个本地的女友，我该怎么做，回答简单一点");
        // updateUserProfile 是同步写，这一步当前恒真；留着是防它日后被挪进异步队列时断言悄悄失去意义
        assertTrue(memoryUpdateService.awaitPendingWrites(30_000), "记忆写入队列应该在 30 秒内排空");

        UserProfile profile = userProfileRepository.find();
        assertNotNull(profile, "第二层用户画像文件应该被创建");
        // 只断言回答偏好：「简单一点」指向明确，且白名单只有 简洁/详细/直白 三种取值。
        // 感情状态不断言：这句话里同时出现 恋爱/分手/相亲，模型判成哪一个都说得通。
        assertEquals("简洁", profile.getPreferredAnswerStyle(), "「回答简单一点」应该被识别为简洁风格");
    }
}
