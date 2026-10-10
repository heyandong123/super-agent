package com.yupi.yuaiagent.memory;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用户画像抽取评估。
 *
 * <p>会真实调用 LLM（抽取画像 + 生成摘要），所以默认不执行，
 * 手动评估时设置环境变量：RUN_MEMORY_EVAL=true
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_MEMORY_EVAL", matches = "true")
class MemoryUpdateServiceTest {

    /**
     * 直接调用 MemoryUpdateService 时没有用户体系，用最外层默认用户。
     */
    private static final String USER_ID = "default";

    @Resource
    MemoryUpdateService memoryUpdateService;

    @Resource
    FileUserProfileRepository userProfileRepository;

    @Test
    @DisplayName("评估：relationshipStatus 传 null 时，仍应从对话中抽取回答偏好")
    void updateUserProfileTest() {
        // 每次用新的 conversationId：复合键是 userId + conversationId，
        // 复用固定 key 会读到上一次的画像文件，让断言失去意义
        String conversationId = "mem-update-test-" + System.nanoTime();

        memoryUpdateService.updateUserProfile(USER_ID, conversationId, null,
                "我和小美恋爱了，但是我们离得很远，我不想这种状态，所以分手了，现在在参加相亲，希望能找一个本地的女友，我该怎么做，回答简单一点");

        UserProfile profile = userProfileRepository.findByUserKey(USER_ID, conversationId);
        assertNotNull(profile, "第二层用户画像文件应该被创建");
        // 只断言回答偏好：「简单一点」指向明确，且白名单只有 简洁/详细/直白 三种取值。
        // 感情状态不断言：这句话里同时出现 恋爱/分手/相亲，模型判成哪一个都说得通。
        assertEquals("简洁", profile.getPreferredAnswerStyle(), "「回答简单一点」应该被识别为简洁风格");
    }
}
