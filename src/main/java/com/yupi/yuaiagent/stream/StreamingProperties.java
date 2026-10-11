package com.yupi.yuaiagent.stream;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 流式改造配置（2026-10 流式轮）
 * <p>
 * 背景：改造前全项目每一个 LLM 调用都是阻塞 {@code .call()}，SSE 只是把「整步」当帧发，
 * 用户侧观感 = 长时间空白 + 整段砸下来。本轮把「给人看的文本」（think 叙述 + 最终答案）
 * 换成逐 token 流式（对标 Claude Code / OpenAI Agents SDK 的「文本增量 + 工具调用离散事件」形态）。
 * <p>
 * 回滚开关是 {@link #enabled}：false 时所有 LLM 调用回到阻塞路径（两套代码共存，行为与改造前一致）。
 * 沿构造器链透传（与 {@link com.yupi.yuaiagent.toolrouter.ToolRoutingProperties}、
 * {@link com.yupi.yuaiagent.context.ContextWindowManager} 同一条纪律：不加 setter、不加 @Autowired）。
 */
@Component
@ConfigurationProperties(prefix = "yuai.stream")
@Data
public class StreamingProperties {

    /**
     * 总开关（默认 true）。
     * <p>
     * true = {@code think()} 与最终答案合成走逐 token 流式；false = 全链路回到阻塞 {@code .call()}。
     * 注意流式还要求「有人在看」——{@code BaseAgent.runStream} 会安装文本增量出口（sink），
     * 而 {@code run()}（评测、多智能体子 Agent）不安装：流式是给人看的，不是给机器看的。
     */
    private boolean enabled = true;

    /**
     * 流式请求是否携带 {@code stream_options.include_usage}（默认 true）。
     * <p>
     * 为什么必须有它：流式响应默认<b>不带 usage</b>（Spring AI 侧是 {@code EmptyUsage}，
     * 聚合后还会变成 {@code DefaultUsage(0,0,0)}），token 埋点会整体归零——
     * 「测不到」被误读成「很省」。DeepSeek 兼容接口支持该参数（usage 挂在 [DONE] 前的最后一块）。
     * <p>
     * 为什么做成可关：它只在流式请求里合法（非流式带上会被 Spring AI 剥掉并打 WARN），
     * 且属于供应商扩展能力——万一某个兼容端点不认这个参数，关掉它即可保住流式主链路
     * （代价仅是 token 埋点退化为「缺失计数」）。{@code UsageMetricsAdvisor} 的
     * {@code usageMissingCalls} 会把这种情况显式统计出来，不会静默记 0。
     */
    private boolean includeUsage = true;
}
