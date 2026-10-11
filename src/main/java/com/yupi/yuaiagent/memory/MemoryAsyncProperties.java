package com.yupi.yuaiagent.memory;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 记忆写入异步化配置（2026-10 记忆重构 · 阶段 3）
 * <p>
 * <b>为什么异步</b>：记忆写入是「响应之外的副作用」——一次交互要抽画像 + 写摘要 + 向量归档，
 * 3 次 LLM/嵌入往返（实测占用请求线程 3~8 秒），而用户早就拿到答案了。
 * 把它移出关键路径，端到端延迟直接少掉这部分（对标把状态外部化、长耗时动作不阻塞主链路）。
 * <p>
 * <b>为什么不干脆用 @Async</b>：无界队列 + 无统计 + 无排空 = 高并发下 OOM 且无人知道；
 * 这里的取舍是<b>有界队列 + 丢弃计数 + 看门狗超时观测</b>：宁可丢一次记忆写入，
 * 也不能让记忆的 I/O 把主链路拖垮。
 * <p>
 * {@link #enabled}=false 时回退同步执行（调试/回滚用，行为与改造前一致）。
 */
@Component
@ConfigurationProperties(prefix = "yuai.memory.async")
@Data
public class MemoryAsyncProperties {

    /** true=异步（默认）；false=同步执行（调试/回滚） */
    private boolean enabled = true;

    private int corePoolSize = 1;

    private int maxPoolSize = 2;

    /**
     * 有界队列容量。满了直接丢弃并计数——<b>刻意不用 CallerRunsPolicy</b>：
     * 那会把 3~8 秒的写入压回请求线程，等于在最忙的时候给主链路加最重的负担。
     */
    private int queueCapacity = 64;

    private int keepAliveSeconds = 60;

    /** 关停时最多等多久排空（超时只告警，不中断线程） */
    private long drainTimeoutMillis = 5000;

    /** 单条写入超过这个时长即视为超时（看门狗只观测不中断） */
    private long taskTimeoutMillis = 60000;

    /** 看门狗巡检间隔 */
    private long watchdogIntervalMillis = 30000;
}
