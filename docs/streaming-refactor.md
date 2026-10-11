# Agent 逐 token 真流式改造（2026-10-11）

> 依据 CLAUDE.md「终极目标」四准则：①对标业界最强（写明参考对象/其机制/本项目取舍）②优化必须可度量（没有数字不算完成）
> ③性能/可用性/延迟/token 四维一起取舍 ④可讲述性（沉淀成「问题 → 业界怎么做 → 我的取舍 → 实测数字」）。
>
> **结论先行**：SSE 通道早就在（`session` / `process` / `final` 三种具名事件），但用户看到的是
> 「长时间空白 + 整段砸下来」——**根因不在通道，而在调用方式**：全项目每一个 LLM 调用都是阻塞
> `.call()`，SSE 只是把「整步跑完」的结果当帧发；模型 think 出来的思考文本甚至只进日志、从未进过 SSE。
> 本轮把它改成**逐 token 推送**：新增 `delta` 事件，think 叙述与最终答案都逐字流出，
> 工具调用仍是离散步骤行（对齐 Claude Code 的观感）；`final` 保留为**终态权威**，
> 收到即覆盖流式期间的临时内容。
>
> 核心数字（实测，命令与原始输出见 §6）：**两轮独立 A/B 平均首字时间 4305→937ms（提前 78%）
> 与 4096→826ms（提前 80%）**，增量帧 537 / 1084，`usageMissing` 两轮都是 0；冒烟纯聊天首字 **636ms**（阻塞式同场景首个内容帧 1987ms，
> 且最终答案要等到 11342ms）。**端到端与 token 没有变好也没有变坏**（流式不改总时长、不改 prompt，
> 它只把「用户多早看到东西」提前）——这条边界在 §6.2 用逐用例数据说明了，不粉饰。
> 回滚开关 `yuai.stream.enabled=false` 实测**零 delta 帧**、行为与改造前一致。

---

## 1. 问题清单（全部带证据）

| # | 现状 | 证据 |
| --- | --- | --- |
| S1 | **所有 LLM 调用都是阻塞 `.call()`**，全项目没有任何 Spring AI 流式调用（`LoveApp.doChatByStream` 的 `.stream()` 是唯一例外，且是恋爱大师自己的裸文本通道，与 Manus 链路无关） | `ToolCallAgent.think()` / `BaseAgent.synthesizeFinalAnswer()` |
| S2 | SSE 只推「整步」：循环里每跑完一步发一条 `process`，内容是**工具结果的拼接**，不是模型 token | `BaseAgent.runStream` 循环 |
| S3 | 模型 think 的思考文本**只打日志**，从未进 SSE——用户永远看不到「模型在想什么」 | `ToolCallAgent`（`log.info("思考完成: {}", ...)`） |
| S4 | `final` 一次性发整段：长回答的首字时间 = 整轮总耗时 | `BaseAgent.runStream` |
| S5 | 前端把 `final` 追加拼在步骤气泡尾部（`updateLastAiMessage`），最终答案与执行过程糊在一条气泡里 | he 工程 `chatApi.js` 把 final 回落到 `onMessage` |

> 修复状态：S1~S5 全部落地。**前端两工程都改**（he = 日常本地版，yu = 原版）。

---

## 2. 业界对标（参考对象 / 其机制 / 本项目取舍）

| 参考对象 | 其机制 | 本项目取舍 |
| --- | --- | --- |
| Claude Code | 回复文本逐 token 流式；工具调用是**离散事件行**；任务中途不改变请求前缀（缓存友好） | 学其「文本流 + 工具行分离」的观感；工具行沿用既有 `process` 事件，不新造事件类型 |
| OpenAI Agents SDK | 语义化流事件：`response.output_text.delta`（增量）→ `response.completed`（终态全文） | 新增 `delta` 增量事件 + `final` 作为**终态权威**：流式只做临时渲染，`final` 一到就覆盖 |
| Spring AI 1.0.0 `ToolCallAdvisor` | 文本 chunk 推给用户、工具调用 chunk 从用户输出中过滤掉，聚合另存 | 同样「文本上屏、工具块不上屏」；但聚合器必须自建（见 §3 事实 3） |
| Vercel AI SDK | `text-delta` / `tool-input-start` / `tool-call` 分事件；流式期间临时渲染，完成后落定 | 前端的「临时渲染 + 终态覆盖」照此实现 |

---

## 3. 源码级已验证事实（spring-ai 1.0.0，读的是本地 `~/.m2` 的 sources jar）

写代码前逐条核对过，避免凭印象设计：

1. `ChatClient.stream()` 存在，`.stream().chatResponse()` 返回**逐 chunk** 的 `Flux<ChatResponse>`；
   `OpenAiChatModel.stream()` 不跨 chunk 累计。
2. **文本 chunk 逐 token 到达**（每 chunk 一个增量，`getText()` 可能为 null）；而
   **工具调用被 `OpenAiApi.chatCompletionStream` 的 `windowUntil + merge` 压成「一整块完整 chunk」**
   （id/name/arguments 齐全、`finishReason=TOOL_CALLS`）——**不需要自己拼 arguments 分片**。
3. `MessageAggregator`（1.0.0）**只聚合 text/metadata/usage，不聚合 toolCalls**
   （`doOnComplete` 里 `new AssistantMessage(text, metadataMap)`）——而本项目的 `act()` 依赖完整工具调用，
   所以**聚合器必须自建**，不能直接拿框架的用。
4. `ChatResponse.getResult()` 在 `generations` 为空时**返回 null**（usage-only 末帧就是这个形态），
   聚合器必须挡这一下，否则 NPE。
5. `AbstractMessage` 允许 ASSISTANT 消息的 text 为 null（只有 SYSTEM/USER 断言非空）——
   「纯工具块」是合法 chunk。
6. 框架自己的 `MessageAggregator` 聚合 usage 的规则是**逐字段「取最近一次 >0 的值」**——
   因为流式下**每一块都自带一份 metadata**，而只有末帧（`stream_options.include_usage`）里 usage 才有值，
   其余块的 `getUsage()` 是 `EmptyUsage`（`getPromptTokens() == 0`）。**照「后一块覆盖前一块」取元数据，
   一块紧跟其后的普通 chunk 就能把 token 数擦成 0**——这条是离线单测抓到的真 bug（见 §5）。

---

## 4. 设计

### 4.1 开关与边界

`yuai.stream.enabled`（默认 **true**）：true = think/最终答案逐 token 流式；false = 完全走改造前的阻塞路径。
两套代码共存，**回滚即改配置**。

**只流「给人看的文本」**：think 叙述 + 最终答案。以下内部调用保持阻塞——
`verifyTaskCompletion`（终止校验的 JSON 判定）、上下文压缩摘要、`YuManusMultiAgent` 的规划与内部合成。
它们是给机器看的结构化输出，逐 token 推给用户没有意义，还会把「流式发送失败 = 立即停止」的语义
扩散到本不该受它影响的地方。这条边界落在一个判断上：

```java
protected boolean isTextStreamingActive() {
    return isStreamingEnabled() && textDeltaSink != null;
}
```

**增量出口（sink）只有 `runStream` 会安装**。于是 `run()`（评测、多智能体子 Agent）天然保持阻塞、
零行为变化——「流式是给人看的，不是给机器的工具」。

### 4.2 新增 `stream/` 包（4 个类）

| 类 | 职责 |
| --- | --- |
| `StreamingProperties` | `yuai.stream.enabled` / `include-usage` 的 `@ConfigurationProperties` |
| `StreamingChatAggregator` | 在**调用线程**（agent worker）上同步消费 `Flux<ChatResponse>`：逐块回调文本增量、累计全文、捕获工具块与 usage，重建形状与阻塞路径一致的 `ChatResponse` |
| `StreamDeltaSink` | 文本增量出口（`@FunctionalInterface`）——只有一个方法，测试里用 lambda 装计数器 |
| `StreamDisconnectedException` | 「客户端断开」专用异常，见 §4.4 |

`collectBlocking(flux, onTextDelta)` 用 `flux.doOnNext(aggregator::accept).blockLast()`：
**blockLast 保证时序与阻塞 `.call()` 完全一致**（调用返回时文本已收完，`act()` 之后照旧），
且中途异常原样上抛、绝不返回半成品聚合。

### 4.3 事件协议（跨前后端契约）

新增 `delta`：`{"type":"delta","scope":"think"|"final","step":N,"text":"..."}`。
`session` / `process` / `final` **结构一字未改**；`final` 仍是最终答案的唯一权威。

前端收到 `final` 时用其内容**覆盖**流式期间的临时气泡（终态覆盖 = OpenAI Agents SDK 的 `response.completed` 语义）：
中途失败或服务端走兜底文本时，用户不会看到半截「看起来完成了」的假象。

### 4.4 「发送失败 = 立即停」的语义必须保住

改造前 send 是「每步一次」，失败会被 `runStream` 的 catch 捕获 → 置 ERROR 退出。
改造后变成「每 token 一次」，而发送是在 `think()` 深处的回调里发生的——
异常若放任它从聚合器的 `doOnNext` 抛出，会被 `think()` 自己的 catch 当成普通业务异常吞掉：
状态虽然停了，**循环却会继续跑下一步、继续烧 token**。

所以 `emitTextDelta` 主动收手，三步一起做：

1. 清空增量出口（后续内部调用回落阻塞，不会再触发第二次失败）；
2. `state = ERROR`（循环条件 `state == RUNNING` 立刻失效）；
3. 抛 `StreamDisconnectedException`，由 `think()` / `ReActAgent.step()` **单独放行**（`catch` 顺序在通用 `catch` 之前），
   一路传到 `runStream` 的收尾逻辑。

断连后最多损失当前一次 LLM 调用——与改造前的行为对齐。

### 4.5 usage 采集（不能把「测不到」记成「省了 token」）

流式请求要带 `stream_options.include_usage` 才有 usage，而 Spring AI 在**非流式**请求上会剥掉
`streamOptions` 并打 WARN → 开关必须**跟模式绑定**，不能常开：

- `think()` 只在流式分支 `chatOptions.setStreamUsage(isStreamUsageEnabled())`；
- `synthesizeFinalAnswer()` 只在流式分支 `.options(streamUsageOptions())`（runtime options 覆盖
  默认值，只带 `streamUsage` 不会丢 model/temperature——`ModelOptionsUtils.merge` 语义）。

即便这样，**usage 仍有第三种「缺失形态」**：没带 `include_usage` 时框架聚合出的是
`DefaultUsage(0,0,0)`——既不是 null 也不是 `EmptyUsage`。`UsageMetricsAdvisor` 原来只判这两种，
于是流式下的 token 采集会静默退化成「每次 0 token」，而 A/B 拿这个数字会得出「流式很省 token」的假结论。
本轮把「数值为 0」也判为缺失（真实聊天补全的 prompt token 不可能为 0，光系统提示词就 >0），
并补了离线用例 `zeroUsageIsCountedAsMissing`。

### 4.6 埋点

`AgentRunMetrics` 新增两个字段：

- `firstVisibleMs`：run 起点 → **用户看到第一个内容帧**的时间（两种模式都可测，A/B 主指标）；
- `deltaFrames`：成功送达的增量帧数。

**计数放在发送之后**：口径是「用户真的收到多少」。发失败还计数的话，一次断连的 run 也会在报告里
显示「流式正常」。首字时间幂等（只记第一次），没发生流式时保持 `-1`（「没测到」而不是「0ms」）。

---

## 5. 修复的真 bug（离线单测抓到的）

| 现象 | 根因 | 用例 |
| --- | --- | --- |
| usage 帧之后跟一块普通 chunk，900 token 被擦成 0 | 聚合器按「后一块覆盖前一块」取元数据，而每块都自带 `EmptyUsage` | `StreamingChatAggregatorTest.keepsToolCallsFromMergedChunk` |
| 加显式构造器后，所有 `StubAgent extends BaseAgent` 的测试桩编译失败 | **Lombok `@Data` 一旦发现已有显式构造器就不再生成无参构造器**（不是"@Data 总会补一个无参构造器"） | `BaseAgentLifecycleTest` / `BaseAgentMemoryRestoreTest`（两处桩补 `super(null)`） |

第一条特别值得记：它不会让任何测试变红，只会让 token 数字静默变成 0——**而 0 在埋点里恰好表示
「没测到」**，两件事撞在一起，A/B 的 token 对比就失去了意义。

---

## 6. 实测（2026-10-11）

### 6.1 冒烟：帧序列与首帧时间

命令（`python3` 逐帧打时间戳，`event:` 行与 `data:` 行是一帧的两行）：

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8124
python3 /tmp/sse-smoke.py 'http://localhost:8124/api/ai/manus/chat?message=你好'
```

**纯聊天（`你好`）· 流式开**：

| 帧 | 时间 | 内容 |
| --- | --- | --- |
| `session` | 33ms | conversationId |
| `delta(think)` | **636ms** | `你好` |
| `process` | 3131ms | step 1 结束（含 doTerminate 终止校验） |
| `delta(final)` | 3911ms | `你好` |
| `final` | 4138ms | 最终答案全文 |

**同一条消息 · 流式关（`--yuai.stream.enabled=false`）**：零 `delta` 帧，
首个内容帧是 1987ms 的 `process`，`final` 在 11342ms。

→ 首字 **636ms vs 1987ms（≈3.1×）**；而最终答案的可见时间差距更大（3911ms 开始逐字 vs 11342ms 一次性）。

**含工具任务（读取 /tmp/stream-smoke.txt 并汇报）· 流式开**：`session` 34ms →
`process`(readFile) 581ms → `delta(think)` 1391ms → `delta(final)` 4875ms → `final` 5103ms，
共 **143 帧增量**。

这张表还暴露了一个诚实的事实：**第一步就是纯工具调用时，`delta(think)` 会晚于 `process`**（1391ms vs 581ms）——
因为模型那一步没说话、直接调了工具。流式的收益在这种任务上主要落在最终答案的逐字上屏，
而不是「第一个字更早」。首字时间的系统性对比见 §6.2 的 A/B。

### 6.2 A/B：阻塞 vs 逐 token（`RUN_STREAM_EVAL=true`）

```bash
RUN_STREAM_EVAL=true STREAM_EVAL_CASES=3 ./mvnw test -Dtest=StreamingLatencyAbEvalTest
# 报告落盘：tmp/metrics/streaming-ab-<时间戳>.md
```

实验设计：同一批任务跑两遍（`stream-off` = `yuai.stream.enabled=false`，`stream-on` = 默认），
两组都走 `runStream`（唯一会安装增量出口的入口 = 真实前端路径），记忆恢复关掉（`restore-turns=0`），
**唯一变量是流式开关**。判定：方向性指标（首字更早）是硬断言——那是这次改造的定义性质；
幅度与 token 进报告。若 `usageMissingCalls > 0`，报告会显式标注「token 数字不可比」。

**第一轮（2026-10-11 16:46，3 用例，报告 `tmp/metrics/streaming-ab-20261011-164652.md`）**：

| mode | 成功率 | 产物落盘 | 平均首字时间 | 平均端到端 | Σ deltaFrames | Σ promptTokens | Σ completionTokens | Σ usageMissing |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| stream-off | 100% | 100% | **4305 ms** | 7001 ms | 0 | 13593 | 3440 | 0 |
| stream-on | 100% | 100% | **937 ms** | 8130 ms | **537** | 16996 | 3861 | 0 |

逐用例（首字时间 / 端到端 / steps / llmCalls）：

| 用例 | off | on |
| --- | --- | --- |
| 纯对话（不调工具） | 11351 / 12827 / 1 步 / 3 次 | **847** / 9392 / 1 步 / 3 次 |
| 读文件 + 汇报 | 618 / 4375 / 2 步 / 4 次 | **895** / 8330 / 2 步 / 4 次 |
| 写文件 + 确认 | 946 / 3802 / 2 步 / 4 次 | **1070** / 6668 / **3 步 / 5 次** |

**怎么读这三行数字（诚实版）**：

1. **首字时间：4305 → 937ms（提前 78%）**，方向性硬断言通过。纯对话那一格差得最悬殊（11351 → 847ms，
   13×），原因不是「流式让模型变快」，而是**阻塞组的首帧在结构上就不可能早于「整轮跑完」**：
   它等的那个 `process` 帧要等 think + 终止校验 + 最终答案合成全部结束才发得出来（该 run 的 llmMs 12558，
   首帧 11351 就在这个量级）；流式组等的是「模型吐第一个字」（847ms，与 §6.1 冒烟的 636ms 同量级）。
   含工具的用例两组差距小（618 vs 895ms）——因为模型第一步直接调工具、没说话，
   阻塞组的 `process` 帧（工具跑完就发）反而来得早。**所以收益大小取决于任务里「模型先说话」还是「先动手」**，
   这条差异在 §6.1 也能看到，不粉饰。
2. **端到端：8130 vs 7001ms，流式组略慢**——不是流式拖慢的：写文件那一格 on 组多跑了 1 步 / 1 次 LLM 调用
   （3 步 5 次 vs 2 步 4 次，模型自己多绕了一步）。**流式不改变总时长，它改变的是「用户多早看到东西」**，这是它全部的价值，不该拿端到端去邀功。
3. **token：Σprompt 16996 vs 13593、Σcompletion 3861 vs 3440，同样不是流式的锅**。
   证据就在上一张表：纯对话两组 `llmCalls` 都是 3、prompt 也只差 4%（2575 → 2683）——差的是模型每次生成的字数
   （completion 2291 → 1618，模型自己写多写少）；写文件那格 prompt 涨 56% 完全对应「多跑一步」，
   多一步就多一整份上下文。**结构上流式也不可能改变 token**：它不动 prompt 组装的任何一行，
   只给请求体加了 `stream: true` 与 `stream_options.include_usage`（后者不计费）。
   A/B 每组每用例只跑一次，token 差异被「轨迹随机性」（步数/调用数/生成字数）主导——
   要拿 token 做结论需要多轮取均值，本轮不做这个结论，只做「不劣化」的观察。
4. `usageMissing = 0`：`include_usage` 生效、token 采集在流式下没有退化（§4.5 那个「把测不到记成省了」的坑没踩上）。

**第二轮（同日 16:48，4 用例，报告 `tmp/metrics/streaming-ab-20261011-164840.md`）**——换一批采样、多一个用例，看结论稳不稳：

| mode | 成功率 | 产物落盘 | 平均首字时间 | 平均端到端 | Σ deltaFrames | Σ promptTokens | Σ completionTokens | Σ usageMissing |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| stream-off | 100% | 100% | **4096 ms** | 10818 ms | 0 | 24098 | 7107 | 0 |
| stream-on | 100% | 100% | **826 ms** | 7981 ms | **1084** | 20969 | 5181 | 0 |

- **首字时间：4096 → 826ms（提前 80%），与第一轮的 78% 吻合**——两轮独立采样、两次达标，方向性结论可重复。
  纯对话单格这轮差到 18×（13597 → 741ms），再次印证「阻塞组的首帧等整轮、流式组等第一个字」的结构差异。
  含文件读取的格子仍是阻塞组略早（873 vs 1030ms，工具先跑完就发 `process`），与第一轮同形。
- **token 这轮反过来**：Σprompt 20969 vs 24098 —— **流式组比阻塞组少了 13%**。两轮方向相反（+25% / −13%）
  正是「token 差异由轨迹随机性主导、与流式无关」的最直接证据（流式不可能在两轮里一会儿加 token 一会儿减 token）。
  采样层面能对上：纯对话两组的 steps/llmCalls 完全相同（1 步 / 3 次），prompt 只差 3%（2569 → 2649）。
- 端到端这轮流式组反而更快（7981 vs 10818）——差在阻塞组的「读文件」那格跑了 15.0s（步骤与调用数和流式组一样是
  2 步 / 4 次，纯粹是那几次 LLM 调用本身慢：llmMs 15019）。**两轮的端到端差异方向相反 ⇒ 不作结论**，
  流式的收益只在首字时间上，这一条两轮一致。

---

## 7. 行为变化（用户可见）

1. 聊天正文出现模型的思考叙述，**逐字流出**；工具步骤行照旧（`process` 事件）。
2. 首字时间从「整轮跑完」降到「模型开始说话」；最终答案也逐字上屏，`final` 到达时定稿覆盖。
3. 最终答案是**独立一条气泡**了（改造前会拼在步骤气泡尾部）。
4. 思考叙述与最终答案可能内容相近——最终答案是基于执行轨迹**重新合成**的（既有语义，不是本轮引入）。
5. `yuai.stream.enabled=false` 完全回到改造前（两套路径共存，实测零 delta 帧）。
6. 埋点新增 `firstVisibleMs` / `deltaFrames`；流式下 usage 由 `stream_options.include_usage` 提供。
7. LoveApp 链路不变（本来就跑在裸文本流式通道上）。

---

## 8. 不做 / 边界 / 已知代价

- **不做 delta 合批**（每 token 一帧的 SSE 开销可忽略；真需要再加 `yuai.stream.flush-chars`）。
- **不引入 markdown 渲染**（两工程都无 md 库，本轮不扩范围）。
- **不改** `session`/`process`/`final` 结构、SseEmitter 超时与心跳、`maxTokens`。
- **已知代价**：每次流式调用会被聚合三遍（`MyLoggerAdvisor`、`UsageMetricsAdvisor` 各一遍——
  框架既有写法，本来就在流式路径上；加自建聚合器一遍）。都是 O(增量) 的字符串拼接，无额外 LLM 成本。
- **验收靠 A/B + 冒烟**：离线单测证明不了「用户体感」，所以 §6 的两张表是验收的一部分。

---

## 9. 面试口径（四段式）

**问题**：SSE 通道早就有了，为什么还是「整段砸下来」？
——因为每个 LLM 调用都是阻塞 `.call()`，SSE 只是把「整步」当帧发；模型的思考文本甚至只进日志。

**业界怎么做**：Claude Code 与 OpenAI Agents SDK 的共同形态是「文本增量流式 + 工具调用离散事件 + 终态消息覆盖」。

**我的取舍**：
① **只流给人看的文本**（think 叙述 + 最终答案），内部 JSON 调用保持阻塞——流式是给人看的，不是给机器看的；
边界落在一个 `sink != null` 判断上，`run()` 链路（评测、多智能体子 Agent）自动保持零行为变化。
② **聚合必须自建**——Spring AI 1.0.0 的 `MessageAggregator` 不聚合 toolCalls（读源码确认），
而 `act()` 依赖完整工具调用；好在 `OpenAiApi` 在 HTTP 层已用 `windowUntil + merge` 把工具分片压成完整块，
聚合器只需捕获、不必拼接。
③ **终态覆盖**：`delta` 只做临时渲染、`final` 是权威——中途失败不会留下半截「看起来完成了」的假象。
④ **断连语义不能退化**：流式把发送频率从「每步一次」放大到「每 token 一次」，
异常必须用专用类型穿透 `think()` 的通用 catch，否则循环会在无人接收的情况下继续烧 token。

**实测数字**：A/B 平均首字时间 4305ms → 937ms（提前 78%），纯对话单格 11351ms → 847ms；
537 个增量帧；`usageMissing = 0`（流式下 token 采集没退化）；端到端与 token 对比组持平
（差异全部能被「模型多绕一步 / 生成字数不同」解释，逐用例列了证据）；回滚开关实测零 delta 帧、行为不变；
离线单测 127 → 142 例全绿；A/B 报告落 `tmp/metrics/streaming-ab-*.md`。

---

## 附：参考来源

- Claude Code 流式输出与工具展示（官方文档）：<https://code.claude.com/docs/zh-CN/tools-reference>
- OpenAI Agents SDK 流式事件（`response.output_text.delta` / `response.completed`）：<https://openai.github.io/openai-agents-python/streaming/>
- Vercel AI SDK 流式协议（`text-delta` / `tool-call`）：<https://sdk.vercel.ai/docs/ai-sdk-ui/stream-protocol>
- Spring AI 1.0.0 `ToolCallAdvisor` 与 `MessageAggregator` 源码（本地 `~/.m2` sources jar 逐条核对）
