package com.yupi.yuaiagent.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 记忆写入队列的离线单测（2026-10 记忆重构 · 阶段 3）。
 * <p>
 * 线程池语义（有界队列、丢弃、看门狗、排空）全是「不看代码就说不清」的部分，
 * 而这些语义一旦错了，线上表现为「记忆静默丢失」——所以它们必须被离线锁死。
 * 全程不碰 LLM/文件，毫秒级跑完。
 */
@Tag("unit")
class MemoryWriteQueueTest {

    private final List<MemoryWriteQueue> createdQueues = new CopyOnWriteArrayList<>();

    @AfterEach
    void shutdownQueues() {
        createdQueues.forEach(MemoryWriteQueue::shutdown);
        createdQueues.clear();
    }

    /** 造一个队列：默认把看门狗间隔拉到 1 小时，避免它在与超时无关的用例里插一脚 */
    private MemoryWriteQueue newQueue(Consumer<MemoryAsyncProperties> customizer) {
        MemoryAsyncProperties properties = new MemoryAsyncProperties();
        properties.setWatchdogIntervalMillis(3_600_000);
        customizer.accept(properties);
        MemoryWriteQueue queue = new MemoryWriteQueue(properties);
        createdQueues.add(queue);
        return queue;
    }

    private static MemoryWriteQueue.MemoryWriteTask task(String name, Runnable body) {
        return new MemoryWriteQueue.MemoryWriteTask() {
            @Override
            public void execute() {
                body.run();
            }

            @Override
            public String describe() {
                return name;
            }
        };
    }

    @Test
    @DisplayName("null 任务直接忽略，不进队列也不计数")
    void nullTaskIsIgnored() {
        MemoryWriteQueue queue = newQueue(p -> {
        });

        assertFalse(queue.submit(null));
        assertEquals(0, queue.getSubmitted());
        assertEquals(0, queue.getPending());
    }

    @Test
    @DisplayName("enabled=false 回退同步：任务在 submit 返回前跑完，且不经过线程池计数")
    void disabledRunsSynchronously() {
        MemoryWriteQueue queue = newQueue(p -> p.setEnabled(false));
        AtomicBoolean ran = new AtomicBoolean(false);

        assertTrue(queue.submit(task("同步任务", () -> ran.set(true))));

        assertTrue(ran.get(), "enabled=false 时任务必须在 submit 返回前就执行完（回滚开关的语义）");
        assertEquals(0, queue.getSubmitted(), "同步回退不经过线程池");
        assertEquals(0, queue.getPending());
    }

    @Test
    @DisplayName("异步提交立刻返回，任务在队列线程里跑完并被计数")
    void submitIsAsyncAndDrainedByAwaitIdle() {
        MemoryWriteQueue queue = newQueue(p -> {
        });
        AtomicInteger runs = new AtomicInteger();

        for (int i = 0; i < 5; i++) {
            queue.submit(task("任务" + i, runs::incrementAndGet));
        }

        assertTrue(queue.awaitIdle(5_000), "队列应该能在 5 秒内排空");
        assertEquals(5, runs.get());
        assertEquals(5, queue.getSubmitted());
        assertEquals(5, queue.getCompleted());
        assertEquals(0, queue.getFailed());
        assertEquals(0, queue.getPending());
    }

    @Test
    @DisplayName("submit 不阻塞：慢任务还在跑的时候 submit 早已返回（这就是「写入不再占用请求线程」）")
    void submitReturnsWhileTaskStillRunning() throws Exception {
        MemoryWriteQueue queue = newQueue(p -> {
        });
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        queue.submit(task("慢任务", () -> {
            started.countDown();
            await(release);
        }));

        assertTrue(started.await(2, TimeUnit.SECONDS), "任务应该已经开始执行");
        assertEquals(1, queue.getPending(), "任务在飞时 pending 应为 1");

        release.countDown();
        assertTrue(queue.awaitIdle(5_000));
        assertEquals(0, queue.getPending());
    }

    @Test
    @DisplayName("awaitIdle 在任务没跑完时超时返回 false，而不是假装排空")
    void awaitIdleReportsTimeoutHonestly() throws Exception {
        MemoryWriteQueue queue = newQueue(p -> {
        });
        CountDownLatch release = new CountDownLatch(1);

        queue.submit(task("长任务", () -> await(release)));

        assertFalse(queue.awaitIdle(50), "任务还在跑，awaitIdle 必须返回 false");

        release.countDown();
        assertTrue(queue.awaitIdle(5_000));
    }

    @Test
    @DisplayName("任务抛异常只计数不冒泡，且工作线程能继续跑下一个任务")
    void taskFailureIsCountedAndWorkerSurvives() {
        MemoryWriteQueue queue = newQueue(p -> {
        });
        AtomicInteger runs = new AtomicInteger();

        queue.submit(task("会炸的任务", () -> {
            throw new IllegalStateException("boom");
        }));
        queue.submit(task("正常任务", runs::incrementAndGet));

        assertTrue(queue.awaitIdle(5_000));
        assertEquals(1, queue.getFailed(), "异常任务计入 failed");
        assertEquals(1, queue.getCompleted(), "后一个任务照样跑完 → 工作线程没被异常带走");
        assertEquals(1, runs.get());
    }

    @Test
    @DisplayName("队列满立刻丢弃并计数：绝不阻塞提交线程（刻意不用 CallerRunsPolicy）")
    void fullQueueDropsImmediately() throws Exception {
        MemoryWriteQueue queue = newQueue(p -> {
            p.setCorePoolSize(1);
            p.setMaxPoolSize(1);
            p.setQueueCapacity(1);
        });
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        // 占住唯一的工作线程
        queue.submit(task("占住线程的任务", () -> {
            running.countDown();
            await(release);
        }));
        assertTrue(running.await(2, TimeUnit.SECONDS));
        // 填满容量为 1 的队列
        queue.submit(task("排队任务", () -> {
        }));

        long start = System.currentTimeMillis();
        boolean accepted = queue.submit(task("应被丢弃的任务", () -> {
        }));
        long cost = System.currentTimeMillis() - start;

        assertFalse(accepted, "队列满时 submit 应该返回 false");
        assertEquals(1, queue.getRejected());
        assertTrue(cost < 500, "拒绝必须立刻返回，实际耗时 " + cost + " ms —— 阻塞就说明用了 CallerRunsPolicy");

        release.countDown();
        assertTrue(queue.awaitIdle(5_000));
    }

    @Test
    @DisplayName("看门狗把超时任务计入 timedOut，但绝不中断它（中断会留下半写文件）")
    void watchdogFlagsStuckTaskWithoutInterrupting() throws Exception {
        MemoryWriteQueue queue = newQueue(p -> {
            p.setTaskTimeoutMillis(100);
            p.setWatchdogIntervalMillis(50);
        });
        CountDownLatch finished = new CountDownLatch(1);

        queue.submit(task("卡住的任务", () -> {
            sleep(400);
            finished.countDown();
        }));

        long deadline = System.currentTimeMillis() + 3_000;
        while (queue.getTimedOut() == 0 && System.currentTimeMillis() < deadline) {
            sleep(20);
        }
        assertEquals(1, queue.getTimedOut(), "超过 taskTimeoutMillis 的任务应该被看门狗记一次");

        assertTrue(finished.await(2, TimeUnit.SECONDS),
                "看门狗只观测不中断：任务必须自己跑完，否则可能留下半截 JSON");
        assertTrue(queue.awaitIdle(5_000));
        assertEquals(1, queue.getCompleted());
        assertEquals(1, queue.getTimedOut(), "同一个任务只记一次超时");
    }

    @Test
    @DisplayName("shutdown 先排队列再退出：已提交的写入不该丢在关停路上")
    void shutdownDrainsPendingWrites() {
        MemoryWriteQueue queue = newQueue(p -> {
            p.setCorePoolSize(1);
            p.setMaxPoolSize(1);
        });
        AtomicInteger runs = new AtomicInteger();

        for (int i = 0; i < 3; i++) {
            queue.submit(task("任务" + i, () -> {
                sleep(30);
                runs.incrementAndGet();
            }));
        }

        queue.shutdown();

        assertEquals(3, runs.get(), "shutdown 应该等队列排空（@PreDestroy 的语义）");
        assertEquals(3, queue.getCompleted());
    }

    @Test
    @DisplayName("stats 一行能看清 submitted/completed/rejected，排障不用猜")
    void statsLineIsReadable() {
        MemoryWriteQueue queue = newQueue(p -> {
        });

        queue.submit(task("任务", () -> {
        }));
        assertTrue(queue.awaitIdle(5_000));

        String stats = queue.stats();
        assertTrue(stats.contains("submitted=1"), "stats 应包含 submitted：实际 " + stats);
        assertTrue(stats.contains("completed=1"), "stats 应包含 completed：实际 " + stats);
        assertTrue(stats.contains("rejected=0"), "stats 应包含 rejected：实际 " + stats);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
