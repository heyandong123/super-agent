package com.yupi.yuaiagent.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@code appendHistory} 的 fail-open 离线单测（2026-10-11 单用户单会话重构新增）。
 * <p>
 * 为什么这条不变量值得单独一个类：appendHistory 是<b>同步</b>写在请求线程上的
 * （刻意不走 MemoryWriteQueue——异步队列 + 同步追加会形成双通道竞态，见仓库类注释），
 * 所以它一旦抛异常，就会顺着 BaseAgent 的收尾路径或 LoveApp 的响应链把一次
 * 已经成功的对话变成 500。落盘失败最多是"少记一轮历史"，永远不能反过来影响主链路。
 * <p>
 * 全程离线：仓库打成 mock，ChatModel 打成 mock（ChatClient 只是构造期需要）。
 */
@Tag("unit")
class MemoryUpdateServiceHistoryTest {

    private FileConversationHistoryRepository historyRepository;

    private MemoryUpdateService memoryUpdateService;

    @BeforeEach
    void setUp() {
        historyRepository = mock(FileConversationHistoryRepository.class);
        memoryUpdateService = new MemoryUpdateService(
                mock(FileUserProfileRepository.class),
                mock(FileRecentSummaryRepository.class),
                historyRepository,
                mock(ConversationMemoryService.class),
                mock(MemoryWriteQueue.class),
                new MemoryHistoryProperties(),
                mock(ChatModel.class));
    }

    @Test
    @DisplayName("落盘失败 fail-open：仓库抛 IOException 也不许把异常带到主链路")
    void appendFailureIsSwallowed() throws Exception {
        doThrow(new IOException("磁盘写满")).when(historyRepository).append(any());

        assertDoesNotThrow(() -> memoryUpdateService.appendHistory("问题", "回答", "stub-agent"),
                "历史是近场恢复的增强项，写不进去最多少记一轮，不能让对话整体失败");
    }

    @Test
    @DisplayName("总开关关闭时不落盘：评测隔离（restore-turns=0 / enabled=false）下零副作用")
    void disabledHistorySkipsWrite() throws Exception {
        MemoryHistoryProperties disabled = new MemoryHistoryProperties();
        disabled.setEnabled(false);
        MemoryUpdateService service = new MemoryUpdateService(
                mock(FileUserProfileRepository.class),
                mock(FileRecentSummaryRepository.class),
                historyRepository,
                mock(ConversationMemoryService.class),
                mock(MemoryWriteQueue.class),
                disabled,
                mock(ChatModel.class));

        service.appendHistory("问题", "回答", "stub-agent");

        verify(historyRepository, never()).append(any());
    }

    @Test
    @DisplayName("空内容不落盘：半截对话（如失败轮只剩用户话语）没有恢复价值")
    void blankContentSkipsWrite() throws Exception {
        memoryUpdateService.appendHistory("问题", "   ", "stub-agent");
        memoryUpdateService.appendHistory("", "回答", "stub-agent");

        verify(historyRepository, never()).append(any());
    }
}
