package com.yupi.yuaiagent.memory;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 记忆写入队列（2026-10 记忆重构 · 阶段 3）：把「写画像 / 写摘要 / 向量归档」移出请求线程。
 * <p>
 * <b>四条设计约束（每一条都是踩过的坑的反面）</b>：
 * <ol>
 *   <li><b>有界队列 + 丢弃计数</b>：满则丢（{@code rejected} 计数 + 抽样 WARN），
 *       绝不用 {@code CallerRunsPolicy} 把 3~8 秒的写入压回请求线程；</li>
 *   <li><b>任务里只有值对象</b>：提交方必须把要写的数据「拷贝成字符串/布尔」再提交，
 *       绝不把 Agent 引用带进来——Agent 是每请求 new 的，而队列里的任务可能活得比它久；</li>
 *   <li><b>异常不冒泡</b>：任务自己吞掉一切异常并计入 {@code failed}，
 *       否则一次写盘失败会杀掉工作线程，之后所有记忆写入静默消失；</li>
 *   <li><b>超时用看门狗，不中断线程</b>：{@code interrupt} 可能留下半写文件，
 *       取舍是「宁可让一条写入超时跑完，也不留下半截 JSON」——所以只观测、
 *       只计入 {@code timedOut}、只告警。</li>
 * </ol>
 * 工作线程是 daemon：关停时最多等 {@code drain-timeout-ms} 排空，等不到就告警放行，
 * 绝不阻塞 JVM 退出（进程被杀时最后一条写入可能丢，这是明确接受的代价）。
 */
@Component
@Slf4j
public class MemoryWriteQueue {

    /** 队列里跑的任务：只描述「要写什么」，不含任何 Agent/请求态引用；{@code describe()} 供日志定位 */
    public interface MemoryWriteTask {

        void execute();

        String describe();
    }

    /** 丢弃抽样告警：第 1 次必告警，之后每 N 次告警一次（防日志被刷屏） */
    private static final int REJECT_LOG_EVERY = 50;

    private final MemoryAsyncProperties properties;

    private final ThreadPoolExecutor executor;

    private final ScheduledExecutorService watchdog;

    private final AtomicLong taskSequence = new AtomicLong();

    /** 在跑的任务：taskId → 开始时间（纳秒），看门狗据此发现「卡住的写入」 */
    private final Map<Long, Long> inFlight = new ConcurrentHashMap<>();

    private final AtomicInteger submitted = new AtomicInteger();

    private final AtomicInteger completed = new AtomicInteger();

    private final AtomicInteger failed = new AtomicInteger();

    private final AtomicInteger rejected = new AtomicInteger();

    private final AtomicInteger timedOut = new AtomicInteger();

    /** 已提交但尚未跑完的任务数（含还在队列里排队的） */
    private final AtomicInteger pending = new AtomicInteger();

    public MemoryWriteQueue(MemoryAsyncProperties properties) {
        this.properties = properties;
        this.executor = new ThreadPoolExecutor(
                Math.max(properties.getCorePoolSize(), 0),
                Math.max(properties.getMaxPoolSize(), 1),
                Math.max(properties.getKeepAliveSeconds(), 1L), TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(properties.getQueueCapacity(), 1)),
                daemonThreadFactory("memory-writer-"),
                new ThreadPoolExecutor.AbortPolicy());
        this.watchdog = new ScheduledThreadPoolExecutor(1,
                daemonThreadFactory("memory-watchdog-"));
        long interval = Math.max(properties.getWatchdogIntervalMillis(), 1L);
        this.watchdog.scheduleWithFixedDelay(this::scanStuckTasks, interval, interval, TimeUnit.MILLISECONDS);
    }

    /**
     * 提交一次记忆写入。
     *
     * @return true=已受理（异步排队或同步执行完）；false=队列已满被丢弃（调用方无需处理，已计数告警）
     */
    public boolean submit(MemoryWriteTask task) {
        if (task == null) {
            return false;
        }
        if (!properties.isEnabled()) {
            // 同步回退（调试/回滚）：行为与改造前一致，异常也沿用改造前的语义（不吞）
            task.execute();
            return true;
        }
        pending.incrementAndGet();
        try {
            executor.execute(() -> runTracked(task));
            submitted.incrementAndGet();
            return true;
        } catch (RejectedExecutionException e) {
            pending.decrementAndGet();
            int total = rejected.incrementAndGet();
            if (total == 1 || total % REJECT_LOG_EVERY == 0) {
                log.warn("记忆写入队列已满，本次写入被丢弃（累计丢弃 {} 次）: {}。"
                        + "丢弃是有意为之：宁可少记一条，也不把长耗时的写入压回请求线程。",
                        total, task.describe());
            }
            return false;
        }
    }

    private void runTracked(MemoryWriteTask task) {
        long taskId = taskSequence.incrementAndGet();
        inFlight.put(taskId, System.nanoTime());
        try {
            task.execute();
            completed.incrementAndGet();
        } catch (Throwable t) {
            // 捕获一切（含 Error）：任务异常若冒泡，工作线程会被替换掉，之后所有记忆写入静默消失
            failed.incrementAndGet();
            log.warn("记忆写入失败（已丢弃，不影响主链路）: {} - {}", task.describe(), t.getMessage());
        } finally {
            inFlight.remove(taskId);
            pending.decrementAndGet();
        }
    }

    /**
     * 看门狗：定期巡检「跑太久」的任务。
     * <p>
     * 刻意<b>不</b>做 {@code Future.cancel(true)} / {@code interrupt}：写入是
     * 「临时文件 + 原子改名」，被打断在中间就是一份半截文件；而记忆写入慢的根因通常是
     * 远端 LLM/嵌入 API 抖动，它自己会超时返回。所以这里只把「卡住了」变成可观测的数字
     * （{@code timedOut}），不替它做决定。每个任务只记一次（记完即从在跑表移除）。
     */
    private void scanStuckTasks() {
        try {
            long thresholdNanos = Math.max(properties.getTaskTimeoutMillis(), 1L) * 1_000_000L;
            long now = System.nanoTime();
            inFlight.forEach((taskId, startNanos) -> {
                if (now - startNanos > thresholdNanos && inFlight.remove(taskId, startNanos)) {
                    int total = timedOut.incrementAndGet();
                    log.warn("记忆写入已运行超过 {} ms 仍未结束（累计 {} 次；看门狗只告警不中断，"
                            + "中断可能留下半写文件）", properties.getTaskTimeoutMillis(), total);
                }
            });
        } catch (Exception e) {
            log.warn("记忆写入看门狗巡检异常（已忽略）: {}", e.getMessage());
        }
    }

    /**
     * 等所有已提交的写入跑完（测试与评测用）。
     *
     * @return true=已排空；false=超时仍有未完成的任务
     */
    public boolean awaitIdle(long timeoutMillis) {
        long deadline = System.nanoTime() + Math.max(timeoutMillis, 0) * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (isIdle()) {
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return isIdle();
            }
        }
        return isIdle();
    }

    private boolean isIdle() {
        return pending.get() == 0 && executor.getQueue().isEmpty() && executor.getActiveCount() == 0;
    }

    /** 指标行：排障时一眼看清「提交了多少、丢了多少、卡了多少」 */
    public String stats() {
        return String.format("submitted=%d completed=%d failed=%d rejected=%d timedOut=%d pending=%d",
                submitted.get(), completed.get(), failed.get(), rejected.get(), timedOut.get(), pending.get());
    }

    /**
     * 关停：先停看门狗，再等队列排空（限时）。等不到只告警——工作线程是 daemon，
     * 不阻塞 JVM 退出，也不为了「写干净」去中断可能正在写文件的任务。
     */
    @PreDestroy
    public void shutdown() {
        watchdog.shutdownNow();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(Math.max(properties.getDrainTimeoutMillis(), 0), TimeUnit.MILLISECONDS)) {
                log.warn("记忆写入未在 {} ms 内排空，剩余 {} 条随进程退出丢弃（{}）",
                        properties.getDrainTimeoutMillis(), pending.get(), stats());
                executor.shutdownNow();
            } else if (submitted.get() > 0) {
                log.info("记忆写入队列已排空（{}）", stats());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- 以下 getter 供测试与排障使用 ----

    public int getSubmitted() {
        return submitted.get();
    }

    public int getCompleted() {
        return completed.get();
    }

    public int getFailed() {
        return failed.get();
    }

    public int getRejected() {
        return rejected.get();
    }

    public int getTimedOut() {
        return timedOut.get();
    }

    public int getPending() {
        return pending.get();
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            // daemon：进程退出不被记忆写入拖住（代价是最后一条可能丢，已在类注释里写明）
            thread.setDaemon(true);
            return thread;
        };
    }
}
