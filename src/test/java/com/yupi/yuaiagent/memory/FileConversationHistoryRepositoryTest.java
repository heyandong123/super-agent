package com.yupi.yuaiagent.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 对话历史文件仓库离线回归（2026-10-11 单用户单会话重构新增）。
 * <p>
 * history.jsonl 是「近场恢复」的唯一数据源，它的三条不变量决定恢复内容的正确性：
 * <ul>
 *   <li><b>顺序</b>：文件顺序即时间序，loadRecentTurns 从尾部取——取错头尾会把最老的一轮
 *       当成"刚刚聊过的"灌回上下文；</li>
 *   <li><b>容量有界</b>：超过 max-turns 必须原子裁剪，否则文件与恢复成本都无界增长；</li>
 *   <li><b>并发不丢行</b>：append 是同步写在多个请求线程上的（刻意不走异步队列，见类注释），
 *       append 与裁剪必须同锁——否则裁剪会把"刚要写回的行"再写一遍。</li>
 * </ul>
 * 全部离线：@TempDir + 直接构造仓库，不碰真实 sandbox、不需要 Spring。
 */
@Tag("unit")
class FileConversationHistoryRepositoryTest {

    @TempDir
    Path tempDir;

    private FileConversationHistoryRepository repository(int maxTurns) {
        return new FileConversationHistoryRepository(tempDir.toFile(), maxTurns);
    }

    private static ConversationTurn turn(String user, String assistant) {
        // ts 用固定值：断言只关心顺序与内容，不依赖时钟
        return new ConversationTurn("2026-10-11T10:00:00+08:00", user, assistant, "test");
    }

    @Test
    @DisplayName("顺序：文件顺序即时间序，loadRecentTurns 取尾部而不是头部")
    void loadsTurnsInFileOrderFromTail() throws Exception {
        FileConversationHistoryRepository repository = repository(200);
        repository.append(turn("问题一", "回答一"));
        repository.append(turn("问题二", "回答二"));
        repository.append(turn("问题三", "回答三"));

        List<ConversationTurn> turns = repository.loadRecentTurns(10);

        assertEquals(3, turns.size());
        assertEquals("问题一", turns.get(0).user(), "第一条应是最早写入的");
        assertEquals("回答三", turns.get(2).assistant());
        // 只取最近 N 轮：n=2 时拿后两条（"问题一"必须被排除）
        List<ConversationTurn> recent = repository.loadRecentTurns(2);
        assertEquals(List.of("问题二", "问题三"), recent.stream().map(ConversationTurn::user).toList());
        assertEquals(List.of(), repository.loadRecentTurns(0), "n<=0 直接返回空，不读文件");
    }

    @Test
    @DisplayName("容量上限：追加越限后原子裁剪，只留最近 max-turns 轮")
    void trimsToMaxTurns() throws Exception {
        FileConversationHistoryRepository repository = repository(3);
        for (int i = 1; i <= 5; i++) {
            repository.append(turn("问题" + i, "回答" + i));
        }

        List<ConversationTurn> turns = repository.loadRecentTurns(10);

        assertEquals(3, turns.size(), "文件容量上限=3，越限必须裁剪");
        assertEquals(List.of("问题3", "问题4", "问题5"),
                turns.stream().map(ConversationTurn::user).toList(),
                "裁剪保留的是最近的，不是最老的");
        assertEquals(3, Files.readAllLines(tempDir.resolve("history.jsonl")).size(),
                "裁剪是重写文件本身，不只是读取时截断");
    }

    @Test
    @DisplayName("坏行容错：一行坏 JSON 被跳过，不影响其余历史可用")
    void malformedLinesAreSkipped() throws Exception {
        FileConversationHistoryRepository repository = repository(200);
        repository.append(turn("问题一", "回答一"));
        // 模拟写坏的一行（进程在写一半时被杀留下的现场；带换行，坏的是这一行本身）
        Files.writeString(tempDir.resolve("history.jsonl"), "{\"ts\":\"2026\",\"user\":\"半截\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        repository.append(turn("问题二", "回答二"));

        List<ConversationTurn> turns = repository.loadRecentTurns(10);

        assertEquals(2, turns.size(), "坏行被跳过，好行照常返回");
        assertEquals(List.of("问题一", "问题二"), turns.stream().map(ConversationTurn::user).toList());
    }

    @Test
    @DisplayName("并发 append 不丢行：多请求线程同时写，全部落地且都可解析")
    void concurrentAppendsDoNotLoseLines() throws Exception {
        FileConversationHistoryRepository repository = repository(500);
        int threads = 4;
        int perThread = 25;
        runConcurrently(threads, perThread, (threadIndex, i) -> {
            repository.append(turn("t" + threadIndex + "-q" + i, "t" + threadIndex + "-a" + i));
        });

        List<ConversationTurn> turns = repository.loadRecentTurns(1000);

        assertEquals(threads * perThread, turns.size(), "一行都不许丢");
        assertEquals(threads * perThread,
                turns.stream().map(ConversationTurn::user).distinct().count(),
                "每行都是独立写入的那一条");
    }

    @Test
    @DisplayName("并发 + 裁剪：越限时 append 与裁剪同锁，最终恰好等于容量上限且每一行都可解析")
    void concurrentAppendsWithTrimStayExact() throws Exception {
        FileConversationHistoryRepository repository = repository(50);
        runConcurrently(4, 30, (threadIndex, i) -> {
            repository.append(turn("t" + threadIndex + "-q" + i, "t" + threadIndex + "-a" + i));
        });

        // 同锁的意义：若 append 与裁剪之间存在窗口，某次裁剪会基于旧快照重写，
        // 把「已经写回去的行」再写一遍——最终行数就会超过上限或出现重复
        List<ConversationTurn> turns = repository.loadRecentTurns(1000);
        assertEquals(50, turns.size(), "越限后应恰好保留容量上限那么多行");
        assertEquals(50, Files.readAllLines(tempDir.resolve("history.jsonl")).size(),
                "文件本身被原子重写为上限行数（无重复、无半截行）");
    }

    /** 并发执行工具：CountDownLatch 做发令枪，尽量制造真实的并发竞争 */
    private interface AppendAction {
        void run(int threadIndex, int iteration) throws Exception;
    }

    private static void runConcurrently(int threads, int perThread, AppendAction action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                int threadIndex = t;
                futures.add(pool.submit(() -> {
                    try {
                        // 发令枪：尽量让所有线程同时进入 append，制造真实的锁竞争
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            action.run(threadIndex, i);
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            start.countDown();
            // future.get() 是必须的：writer 线程里的异常不取就永远静默（测试会绿得毫无意义）
            for (java.util.concurrent.Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
