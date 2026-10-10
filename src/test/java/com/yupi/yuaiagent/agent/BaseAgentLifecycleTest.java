package com.yupi.yuaiagent.agent;

import com.yupi.yuaiagent.agent.model.AgentState;
import com.yupi.yuaiagent.memory.MemoryUpdateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * BaseAgent 生命周期的回归测试（纯单测：不启 Spring、不调 LLM、不碰工具）。
 *
 * <p>覆盖两个曾经真实存在的缺陷：
 * <ul>
 *   <li>cleanup() 排在记忆写入之后，而记忆写入会调 LLM 且可能抛异常 —— 一抛，状态机就永远停在 RUNNING/ERROR；</li>
 *   <li>执行循环的条件是 {@code state != FINISHED}，于是超时回调把状态置为 ERROR/IDLE 后循环依然成立，
 *       agent 会在连接已经断掉的情况下继续跑含 LLM 调用与工具副作用的步骤。</li>
 * </ul>
 */
@Tag("unit")
class BaseAgentLifecycleTest {

    @Test
    @DisplayName("记忆写入抛异常时：状态机仍回到 IDLE 且消息已清空，任务结果不受影响")
    void memoryFailureShouldNotBreakStateMachine() {
        MemoryUpdateService failingMemory = mock(MemoryUpdateService.class);
        doThrow(new RuntimeException("模拟磁盘写满"))
                .when(failingMemory)
                .recordAgentInteraction(anyString(), anyString(), anyString(), anyString(), anyString());

        StubAgent agent = new StubAgent(2);
        agent.setMemoryUpdateService(failingMemory);

        String output = agent.run("你好");

        // 修复前：recordMemoryAfterRun 先抛，cleanup() 被跳过，状态停留在 RUNNING，messageList 也不清
        assertEquals(AgentState.IDLE, agent.getState(), "记忆写入失败不应阻止 cleanup 把状态复位");
        assertTrue(agent.getMessageList().isEmpty(), "cleanup 必须执行，消息上下文应被清空");
        assertFalse(output.startsWith("出错了："), "记忆写入失败属于 fail-open，不能污染任务结果");
        assertTrue(output.contains("第 1 步结果"));
    }

    @Test
    @DisplayName("同步 run：step 内把状态挪出 RUNNING（模拟超时回调），循环立即结束而不是跑满 maxSteps")
    void leavingRunningStateStopsSyncLoop() {
        StubAgent agent = new StubAgent(5);
        agent.setStateAfterFirstStep(AgentState.IDLE);

        agent.run("你好");

        // 修复前条件为 state != FINISHED，IDLE 依然满足，5 步会全部跑完
        assertEquals(1, agent.getStepCount(), "状态离开 RUNNING 后不应再执行后续步骤");
    }

    @Test
    @Timeout(30)
    @DisplayName("流式 runStream：worker 执行中状态被回调改成 ERROR，收尾后不再执行第二步")
    void streamWorkerStopsWhenStateLeavesRunning() throws Exception {
        StubAgent agent = new StubAgent(5);
        agent.blockOnFirstStep();
        attachFinishLatch(agent);

        SseEmitter emitter = agent.runStream("你好");
        assertNotNull(emitter);

        // 等 worker 真正进入第一步（此时它阻塞在闩锁上），再模拟 SSE 超时回调：
        // 状态置 ERROR（回调同时会 cancel worker，这里只验证循环条件这一层）
        assertTrue(agent.awaitFirstStep(5), "worker 应在 5 秒内进入第一步");
        agent.setState(AgentState.ERROR);
        agent.releaseFirstStep();

        assertTrue(agent.awaitWorkerFinish(10), "worker 应在 10 秒内收尾");
        assertEquals(1, agent.getStepCount(), "连接已断开时不得继续执行后续步骤");
        assertEquals(AgentState.IDLE, agent.getState());
    }

    @Test
    @Timeout(30)
    @DisplayName("流式 runStream 正常路径：收尾后状态回到 IDLE、消息清空")
    void streamNormalPathReturnsToIdle() throws Exception {
        StubAgent agent = new StubAgent(1);
        attachFinishLatch(agent);

        agent.runStream("你好");

        assertTrue(agent.awaitWorkerFinish(10), "worker 应在 10 秒内收尾");
        assertEquals(1, agent.getStepCount(), "worker 必须真的跑过，否则这条断言是空的");
        assertEquals(AgentState.IDLE, agent.getState());
        assertTrue(agent.getMessageList().isEmpty());
    }

    /**
     * 用记忆写入作为「worker 已收尾」的信号：finally 里的顺序是 cleanup() → recordMemorySafely()，
     * 所以闩锁一放行，状态机就已经落定，断言不存在竞态。
     * （不能直接轮询 state == IDLE：初始状态本来就是 IDLE，会假阳性通过。）
     */
    private static void attachFinishLatch(StubAgent agent) {
        MemoryUpdateService memory = mock(MemoryUpdateService.class);
        doAnswer(invocation -> {
            agent.markWorkerFinished();
            return null;
        }).when(memory).recordAgentInteraction(anyString(), anyString(), anyString(), anyString(), anyString());
        agent.setMemoryUpdateService(memory);
    }

    /**
     * 最小可用的 BaseAgent 实现：step() 不碰 LLM、不碰工具，只按开关模拟「外部把状态改走」与「步骤阻塞」。
     */
    private static class StubAgent extends BaseAgent {

        private final AtomicInteger steps = new AtomicInteger();
        private final CountDownLatch firstStepStarted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch workerFinished = new CountDownLatch(1);
        private volatile boolean blockOnFirstStep;
        private volatile AgentState stateAfterFirstStep;

        private StubAgent(int maxSteps) {
            setName("stub-agent");
            setMaxSteps(maxSteps);
        }

        void setStateAfterFirstStep(AgentState state) {
            this.stateAfterFirstStep = state;
        }

        void blockOnFirstStep() {
            this.blockOnFirstStep = true;
        }

        boolean awaitFirstStep(int seconds) throws InterruptedException {
            return firstStepStarted.await(seconds, TimeUnit.SECONDS);
        }

        void releaseFirstStep() {
            release.countDown();
        }

        void markWorkerFinished() {
            workerFinished.countDown();
        }

        boolean awaitWorkerFinish(int seconds) throws InterruptedException {
            return workerFinished.await(seconds, TimeUnit.SECONDS);
        }

        int getStepCount() {
            return steps.get();
        }

        @Override
        public String step() {
            int stepNumber = steps.incrementAndGet();
            if (stepNumber == 1 && blockOnFirstStep) {
                firstStepStarted.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (stepNumber == 1 && stateAfterFirstStep != null) {
                setState(stateAfterFirstStep);
            }
            return "第 " + stepNumber + " 步结果";
        }
    }
}
