# 上下文管理 + 记忆机制重构（2026-10-10）

> 依据 CLAUDE.md「终极目标」四准则：①对标业界最强（写明参考对象/其机制/本项目取舍）②优化必须可度量（没有数字不算完成）
> ③性能/可用性/延迟/token 四维一起取舍 ④可讲述性（沉淀成「问题 → 业界怎么做 → 我的取舍 → 实测数字」）。
>
> **结论先行**：本项目的上下文管理此前只做了 Claude Code 四层漏斗的**半层**——按「消息条数 > 15」压缩，
> 与真实体积无关，且压缩时渲染历史用错了 API（`ToolResponseMessage.getText()` 恒为空串），
> 等于**每次压缩都白花一次 LLM 且丢掉全部工具输出**。本轮补齐为：入口截断 → 常驻做小 →
> 运行层两级压缩（清旧工具输出 → 按 token 预算摘要）→ 退出层落盘与重注入；记忆写入移出请求线程。
>
> 核心数字：归档超长写入 **143~146 秒 → 0.78 秒**；记忆写入占用请求线程 **3~8 秒 → ~0**；
> 摘要器可见的工具输出占比 **0% → 100%**；画像文件 **15 份空画像 → 1 份/用户**。
> A/B 实测见 §5.2。
>
> **后续轮**：§9 单用户单会话收敛（2026-10-11）——记忆寻址键全部删除、工件收敛为固定三件、
> 新增近场历史恢复（`tmp/memory/history.jsonl`），离线单测 113 → **127 例全绿**。

---

## 1. 问题清单（全部带证据，构成本轮动机）

| # | 现状 | 证据 |
| --- | --- | --- |
| P1 | 压缩只按**消息条数**触发（>15 就压），与真实体积无关——一个工具步固定 +2~3 条，但一步可能 32KB 也可能 5 个字 | `ToolCallAgent:60,62,738`（`MAX_CONTEXT_MESSAGES=15` / `RECENT_MESSAGES_TO_KEEP=6`） |
| P2 | 压缩**一压就不可逆**：原文直接丢，无落盘 | `ToolCallAgent.optimizeContextWindowIfNeeded` |
| P3 | 摘要器**看不见工具输出**：`summarizeMessages` 用 `getText()` 渲染，而 `ToolResponseMessage` 的 text 是空串常量（字节码实锤 `AbstractMessage.<init>(TOOL, "", metadata)`） | `ToolCallAgent` 摘要路径 + spring-ai-model-1.0.0 字节码 |
| P4 | 最初那条记忆 SystemMessage 也在被压缩的前段里，压完只剩转述 | `BaseAgent.initializeMemoryLayers` + 压缩切分点 |
| P5 | 摘要失败回落截的是**最老**的 1200 字符（最该丢的），丢掉离当前最近的 | `ToolCallAgent` 摘要失败分支 |
| P6 | 用户画像**实际按会话存**（`userKey=conversationId` 被当文件名），15 个会话 15 份空画像，「跨会话记得你是谁」完全失效 | `MemoryUpdateService` / `FileUserProfileRepository` + `ls tmp/memory/profile/default` 实测 |
| P7 | L5 检索只按 chatId 过滤，userId 写了但不用；归档文本 = 用户原话 + **原始 step 轨迹**（不是给用户的答案）且**无截断** | `ConversationMemoryService` |
| P8 | 归档超长 → DashScope `text-embedding-v1` 400（单条上限 2048 token）→ 被重试 10 次 ×15s ≈ **146 秒**后 fail-open 静默丢记忆 | 实测 143/146/146s；根因经 javap 确认为 DashScopeImageModel 构造器改写**共享** RetryTemplate |
| P9 | 记忆写入**同步**在响应路径上（2 次 LLM + 1 次 embedding）；LoveApp 的 6 个写入点**无 fail-open**；文件写入非原子、坏 JSON 直接抛 RuntimeException | `BaseAgent` finally / `LoveApp` 6 处 / `FileUserProfileRepository` |
| P10 | 运行时**零埋点**：没有 token 计数、计时、compaction 事件，准则②无法落地 | 全仓 grep 无 AgentRunMetrics / MeterRegistry |

> **修复状态**：P1~P10 全部落地（证据见 §5）。其中 P3/P5 最终做成**两条路径共享的修复**（§3.4）——
> 最初只修在 budget 路径，收尾时补回 legacy，因为 legacy 才是默认模式：**已知 bug 不该留在默认路径上等"策略之争"出结果**。

---

## 2. 业界对标（参考对象 / 其机制 / 本项目取舍）

Claude Code 的上下文管理是一个**四层漏斗**，本项目此前只做了第三层的半层：

| 层 | Claude Code 的做法 | 本项目落地 | 取舍与理由 |
| --- | --- | --- | --- |
| ① 入口截断 | 工具返回默认封顶 25k tokens；Read 支持 offset/limit | `readFile` offset/limit（500 行默认 / 2000 行硬顶 / 32KB 字符顶）、`scrapeWebPage` 10s 超时、`downloadResource` 20MB 上限 | 上一轮工具重构已做；这层最便宜——**别让大内容进来**胜过进来再想办法 |
| ② 常驻做小 | `CLAUDE.md` ≤200 行、`MEMORY.md` 前 200 行/25KB 才常驻 | 记忆注入块 `memory-inject-max-chars=1200`；工具 schema 全量 1465 tokens（`ToolSchemaBudgetTest` 可复现） | 每轮都在的东西，1KB 就是每步 250 token。L2 画像改按用户后会跨会话累积，**不设限就是新的膨胀源** |
| ③ 运行层两级 | `clear_tool_uses_20250919`：trigger（默认 10 万 token）/ keep（保留最近 3 个工具结果）/ `clear_at_least`（一次至少清够多少才动手，防反复打穿 prompt cache）；不够才做 LLM 摘要 | 一级微压缩（trigger 35% 窗口、keep=3、clear_at_least=1500 tokens）+ 二级摘要（trigger 50% 窗口、keep 6 条、min-steps-between=2、max-per-run=3） | **参数全量对齐**。核心判断：摘要是**一次 LLM + 不可逆**，清旧工具输出是**零 LLM + 可重取**，所以一级必须先行。`clear_at_least` 这条最容易被忽略——小碎步改历史会让 prompt cache 前缀反复失效，反而更贵 |
| ④ 退出层 | transcript JSONL 落盘 + `/resume`；压缩后从磁盘**重注入** CLAUDE.md、记忆、plan、最近 5 个文件 | 压缩前落盘 transcript（每会话留 5 份，有界）；压缩后重注入**记忆 SystemMessage 原文 + 原始用户任务 + 最近 6 条 + 最近 5 个文件路径** | 只做有界版，不做 `/resume`（超教学范围）。重注入是本轮**信息保全的关键**：压缩前先把"绝不能丢的三样"摘出来（见 §3.3 细节 1）。最近文件只回路径不回内容——省 I/O 也省 token，模型需要时自己会去读 |

另外三处对标：

- **度量**：Anthropic《Writing effective tools for agents》第 8 条——采集 runtime / 调用次数 / token / 错误。
  本项目此前零埋点，于是"优化有没有效"只能靠感觉。本轮的答案是 `AgentRunMetrics`（每 run 一行结构化日志 + 供评测直接读的对象）。
- **记忆容量**：Managed Agents 记忆库用**固定配额**（单条 ≤100kB、每库 ≤10000 条）而非 TTL。
  本项目对齐为配额：`notableFacts ≤ 20`、transcript 每会话 ≤5 份。理由：画像偏好是**长期资产**，TTL 会随机删掉"上次说的偏好"，
  而配额是确定的、可单测的、可讲述的。
- **异步化**：长耗时动作挪出关键路径的通用做法，本项目的具体形态见 §4.3。

**明确"没有参照对象所以不做"**：不改 RAG 接线、不动 `ToolVectorRouter` 0.45/topK2、不动 full 工具面、不给 LoveApp 换掉 Kryo 窗口记忆、不做跨项目全局记忆库。

---

## 3. 上下文管理：从"按条数"到"按预算"

### 3.1 新包 `com.yupi.yuaiagent.context`（4 个类）

| 文件 | 职责 |
| --- | --- |
| `ContextProperties` | `@ConfigurationProperties("yuai.context")`，全部参数（见 §3.4） |
| `ContextTokenEstimator` | 纯计算无 Spring 依赖：CJK ×0.6 + ASCII ÷4 + 其他 ×0.5，再乘安全系数 1.1。**特判 `ToolResponseMessage`**（读 `getResponses()` 的 name+responseData，因为 `getText()` 恒为空串——修 P3） |
| `TranscriptWriter` | 压缩前把完整 messageList 落 JSONL；按会话裁剪到 5 份；原子写；fail-open |
| `ContextWindowManager` | 两级机制唯一入口；**不持有 Agent、无共享状态**（每次请求 new 的 Agent 持有一个），摘要用的 ChatClient 由调用方传入 |

### 3.2 算法（挂在 `think()` 里、**追加 nextStepPrompt 之前**）

```
manage(messages, req):
  est = estimate(messages)
  # 一级：微压缩（零 LLM 成本）
  if est + staticOverhead >= window × 0.35:
      候选 = 所有 ToolResponseMessage（从旧到新），排除最近 3 条、全 doTerminate 的、已清理过的
      从最旧开始替换为占位，直到 freed >= 1500 tokens；原子替换，不半清
  # 二级：整体压缩（用微压缩后的估算值再判）
  est = estimate(messages)
  if est + staticOverhead >= window × 0.5 && 压缩次数 < 3 && 距上次压缩 >= 2 步:
      transcriptWriter.write(...)          # 先落盘，再丢（P2）
      protected = [记忆 SystemMessage 原文] + [第一条 UserMessage 原始任务]   # 修 P4
      summary   = summarize(其余部分)      # 失败 → 保留**最近** N 字符     # 修 P5
      newList   = protected + [Context Summary] + [Recently used files] + 最近 6 条（对齐 turn 边界）
```

### 3.3 五个必须做对的实现细节（都是真实踩出来的）

1. **重注入的三样东西**：记忆 SystemMessage **原文**（不是摘要里的转述）、原始 UserMessage（压缩后模型最容易忘的就是"用户到底要什么"）、最近 5 个文件**路径**。
2. **turn 边界对齐**：切分点若落在 `ToolResponseMessage` 上必须向前退到它的 `AssistantMessage`，
   否则重建出的列表以孤立的工具响应开头，OpenAI 兼容接口会报 tool_call_id 找不到。
3. **微压缩要重建消息而不是删消息**：`ToolResponseMessage(List<ToolResponse>, Map)` 是 public 构造器，
   `ToolResponse` 是 record `(id, name, responseData)`——保留 id 与 name、只换 responseData，协议层完全合法。
   占位文案自带重取指引：`[工具结果已清理以节省上下文] 工具 X 的返回内容已被移除；如仍需要请重新调用 X（readFile 可用 offset/limit 缩小范围）。`
4. **证据链与 messageList 解耦**：`doTerminate` 前要做「原始任务 + 自述清单 + 工具执行证据」三方比对，
   而证据原本是从 messageList 倒扫 ToolResponse 拼的——微压缩后会把**占位符当证据**。
   改法：`act()` 里把证据行写进 agent 内的环形缓冲（最近 8 条），三方校验改读它。顺带让校验不再受任何压缩影响。
5. **估算器不是 tokenizer**：预调用决策只需要误差方向稳定，不需要精确值；
   安全系数 1.1 偏向"早压缩"，误差用 A/B 里的 `tokenEstimateRatio`（估算/实测比）标定，见 §5.2。

### 3.4 两条路径共用的渲染器（默认路径不能留已知 bug）

写完 A/B 才发现的问题：P3（摘要时用 `getText()` 渲染，工具输出恒为空串）**只修在了 budget 路径**，
而 `legacy` 是**默认模式**——默认路径仍在把工具输出喂成空串。

- 最终做法：`ContextWindowManager.renderForSummary()` 改成 public，`ToolCallAgent.summarizeMessages()`（legacy 路径）
  也调它；失败回落方向（P5）同样在 legacy 路径改成保留"最近"的内容。
- **为什么不干脆把默认翻成 budget**：判定标准三条里 ②③ 未达标（§5.2），翻默认没有数字支撑；
  但"已知 bug 留在默认路径"和"触发策略之争"是两件事——bug 该修，策略该等数据。
- **顺带得到方法论上的好处**：A/B 里两组现在只差"触发策略"这一个变量。改之前 budget 因渲染更好而占便宜，
  即便那样它也没在 token 上赢——修完之后这个比较才是公平的。

### 3.5 配置与回滚

唯一开关 `yuai.context.mode`：`legacy`（默认，旧行为一行未改）| `budget`。
回滚 = 改回 `legacy`；临时试跑 = `--yuai.context.mode=budget`。
`ContextWindowManager` 只改 messageList，**从不触碰** `chatOptions.setToolCallbacks(...)` 与 `selectToolsForCurrentStep()`，
所以 full 模式工具面稳定（缓存友好）与 dynamic 教学代码都不受影响。

---

## 4. 记忆机制

### 4.1 L2 画像改按用户跨会话共享（修 P6）

`tmp/memory/profile/{userId}/profile.json`（原先文件名是 conversationId，导致每个会话一份空画像）。
老数据不迁移（`tmp` 是临时目录），清理方式 `rm -rf tmp/memory/profile`。
子 Agent（`YuManusMultiAgent` 分发的）加 `setProfileWriteEnabled(false)`——父协调器已用**原始用户话语**写过画像，
子 Agent 用被包装过的子任务指令写只会污染；但摘要与归档仍按 `childConversationId` 写（会话级数据本就该按会话隔离）。

### 4.2 L5 归档语义修正（修 P7）

归档文本从「用户原话 + **原始 step 轨迹**」改成「用户原话 + **给用户的最终答案**」。
`runStream` 把已算好的 `buildFinalSummary` 结果先存局部变量再发送（**赋值必须在 `cleanup()` 之前**，cleanup 会清空 messageList）；
`run()` 走 `extractFinalAnswer()` → 最后一条非空 AssistantMessage → 最后一条非 doTerminate 工具结果（截 1500 字符）。
**刻意不新增 LLM 调用**：不给 `run()` 加 `synthesizeFinalAnswer`，否则多智能体每请求 +3 次调用。
检索侧补 `and(eq(chatId), eq(userId))` 双键过滤，杜绝跨用户串号。

实测对比（同一任务）：

| | 归档文本 | 长度 |
| --- | --- | --- |
| 改前 | `工具 searchWeb 返回的结果…`（步骤轨迹） | 5364 字符 |
| 改后 | `[User] 帮我在 tmp 下创建文件… / [Assistant] 已在 tmp 目录下创建文件并写入指定内容，回读校验通过…` | 345 字符 |

改后追问「回顾一下我们之前的对话」，日志 `searchMemory 调用 2 次, 命中 2 条历史记忆`，模型准确复述了用户原话 ⇒ 新文本仍可被语义检索命中。

### 4.3 写入异步化（修 P8 / P9）

`MemoryWriteQueue`：core 1 / max 2 / **有界队列 64** / keep-alive 60s / daemon 线程 / `AbortPolicy`。
放在 `MemoryUpdateService` 内部，因为两个 `record*` 是**全部调用方的唯一通道**——改这里等于调用方零改动，
LoveApp 的 6 个写入点顺带获得 fail-open。任务里只捕获值对象（字符串/布尔），**绝不出现 Agent 引用**（与"每请求 new Agent"天然兼容）。

四个刻意的设计决定：

| 决定 | 理由 |
| --- | --- |
| **不用 `CallerRunsPolicy`** | 队列满时它会把这 3~8 秒的写入压回**请求线程**，正好把异步化的收益全吐回去。改为丢弃 + 计数 + 抽样 WARN |
| **core=1（单工作线程）** | 画像写入是「读-改-写」，单线程即天然串行，**不需要文件锁**；只有队列积压到 64 才启用第 2 线程（`ThreadPoolExecutor` 的扩容时机就是队列满，不是 core 满） |
| **看门狗只观测不 interrupt** | 中断可能留下半写文件。用「可能超时未完成」换「绝不半写」，有界队列兜住内存，底部 HTTP 客户端各有超时 |
| **`@PreDestroy` 限时排空 5 秒** | 进程正常退出时把队列里的写入跑完；等不到只告警（工作线程是 daemon，不阻塞 JVM 退出） |

线程池选择上还有一条：不用 `CompletableFuture.runAsync`（`ForkJoinPool.commonPool` 无线程命名、无界、无拒绝语义），
用显式 `ThreadPoolExecutor`——有界、有名（`memory-writer-1`）、可观测、可关停。

---

## 5. 实测数字

### 5.1 A 类：结构决定、离线就能说死的

| 指标 | 改前 | 改后 | 取证方式 |
| --- | --- | --- | --- |
| 压缩触发判据 | 消息条数 > 15 | token 预算占比（35% 微压缩 / 50% 压缩） | 代码 + `ContextWindowManagerTest` |
| 摘要输入里工具输出的占比 | **0%**（`getText()` 恒为空串） | 100%（按 `getResponses()` 渲染） | spring-ai 字节码 + 单测 |
| 压缩后被丢弃的常驻项 | 记忆块 + 原始任务（都丢） | 0（原文重注入 + 最近 5 个文件路径） | `ContextWindowManagerTest` |
| 归档超长的写入耗时 | **143~146 秒后失败** | **0.78 秒成功**（写入前截断） | `RUN_MEMORY_EVAL=true` 的 `LayeredMemoryEvaluationTest`「P8 事故修复」用例 |
| 记忆写入占用请求/worker 线程 | 3~8 秒 | ~0 | 线程栈 A/B 取证（见下） |
| 画像文件数 / 跨会话命中 | 15 份空画像 / 0 | 1 份 / 新会话首轮即命中 | `ls tmp/memory/profile` + 跨会话断言 |
| 检索跨用户串号 | 可能（只按 chatId 过滤） | 不可能（`and(chatId, userId)`） | 单测 |
| 离线回归用例 | 44（`-Dgroups=unit`，本轮开工前） | **113**（+28 context 包 / +10 记忆队列 / +8 证据链 / +15 埋点 / +8 仓储原子写） | `./mvnw -Dgroups=unit test` |

**异步化的取证方法**（这是最能说明问题的一段）：光看日志证明不了，因为记忆链路的 ChatClient 不带 advisor、不打日志。
改用 **jcmd 线程栈**在"请求返回的瞬间"抓现场：

```
异步模式（默认）：请求 9.06s 返回，此刻线程栈里写入还在跑
  "memory-writer-1" daemon ... WAITING (parking)
    → OpenAiChatModel.internalCall → RestClient → JdkClientHttpRequest → CompletableFuture.get
同步模式（--yuai.memory.async.enabled=false）：同一段栈出现在 agent-worker 上
  "agent-worker" ... 同一段 OpenAiChatModel.internalCall → ... → CompletableFuture.get
```

时间戳对照：异步 `18:06:26.850`（AGENT_METRICS 落日志）→ profile `18:06:28` / summary `18:06:32`；
同步 `18:08:25.142` → `18:08:26` / `18:08:29`。**那 ~4 秒在同步模式下压在请求线程上，异步模式下为 0。**
关停统计：`记忆写入队列已排空（submitted=12 completed=12 failed=0 rejected=0 timedOut=0 pending=0）`（4 轮交互 × 3 次投递）。

### 5.2 B 类：A/B 实测（`ContextCompactionAbEvalTest`）

方法：同一批 **5 个多步任务**（搜索+写入+终端确认 / 读长文+摘要+写入 / 搜索+抓取+写入+确认 / 读两文件+对比+写入 /
长链路：三读一搜一写一终端），每条任务都要求**落一个产物文件**——判定的是"任务成功"，不是"工具选对"。
两组唯一变量是压缩策略（渲染器、工具面、模型、任务全同）。

**窗口怎么定**：默认 32K 窗口下这批 4~7 步任务到不了任何一条触发线，A/B 会退化成"两组都没压缩"的空跑。
所以 budget 组改用 `CONTEXT_EVAL_WINDOW_TOKENS` 跑两轮：**12000**（任务与窗口同量级）与 **6000**（越界压力轮，
窗口已小于任务的正常工作集）。这两组设置都**对 budget 更不利**——它更容易被触发、要付更多压缩调用。

**判定谁来做**：每份报告末尾自带 `judge()` 写出的三条判定与结论（`tmp/metrics/context-ab-<ts>.md`）——
数字与决策在同一个文件里，事后不用翻聊天记录。判定器本身踩过一个坑，值得单独记：
第一版把"显著下降"写成 `candidate <= baseline * 0.9`，而两组 `dupToolCallRate` 实测**全是 0.000**——
`0 <= 0` 恒真，于是 6K 轮报告的 ② 被**空真**（vacuous truth）误标成达标（那一轮 token 实际只降 1.7%）。
修法是补一条"必须真的更低"：`candidate < baseline && candidate <= baseline * 0.9`。
**判据写宽松会让「没测出差异」冒充「测出了优势」，这比不做评测更糟。** 另一条相关的取舍：
②③ 只是写进报告的**决策记录**、不是硬断言——压力轮的设定（窗口小于保护集）注定让它们变红，
把已知会红的项写成断言，只会训练自己"看到红色就无视"。

**第一轮：`CONTEXT_EVAL_WINDOW_TOKENS=12000`（任务与窗口同量级）**

下表是判定器修复后的那一轮（19:17）；同日还跑过两轮 12K（19:03 / 19:10），三轮对照见本节末尾的跨轮次表——
**只挑一轮贴明细、其余进对照表**，是为了不让"选一轮好看的"变成隐性挑数。

| mode | task | steps | llmCalls | promptTokens | peakPrompt | peak/窗口 | compactions | microcompactions | dupToolCallRate | est/real | wallMs |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| legacy | 搜索+写入+终端确认 | 4 | 5 | 23469 | 7362 | 61% | 0 | 0 | 0.000 | 1.030 | 12504 |
| legacy | 读长文+摘要+写入 | 4 | 5 | 16064 | 4842 | 40% | 0 | 0 | 0.000 | 0.959 | 7802 |
| legacy | 搜索+抓取+写入+确认 | 5 | 6 | 27659 | 7366 | 61% | 0 | 0 | 0.000 | 0.981 | 14922 |
| legacy | 读两文件+对比+写入 | 4 | 5 | 13897 | 4237 | 35% | 0 | 0 | 0.000 | 0.871 | 8253 |
| legacy | 长链路：三读一搜一写一终端 | 4 | 5 | 26732 | 9009 | 75% | 0 | 0 | 0.000 | 0.956 | 12276 |
| budget | 搜索+写入+终端确认 | 4 | 6 | 22516 | 7442 | 62% | 1 | 0 | 0.000 | 0.995 | 15061 |
| budget | 读长文+摘要+写入 | 4 | 5 | 16378 | 5037 | 42% | 0 | 0 | 0.000 | 0.961 | 8204 |
| budget | 搜索+抓取+写入+确认 | 5 | 7 | 24747 | 5877 | 49% | 1 | 0 | 0.000 | 0.973 | 14945 |
| budget | 读两文件+对比+写入 | 4 | 5 | 13663 | 4118 | 34% | 0 | 0 | 0.000 | 0.877 | 7092 |
| budget | 长链路：三读一搜一写一终端 | 7 | 10 | 38833 | 7394 | 62% | 2 | 0 | 0.000 | 0.948 | 34603 |
| **汇总 legacy** | 5/5 成功 · 产物 100% | | **26** | **107821** | 6563 | 55% | **0** | 0 | 0.000 | | 11151 |
| **汇总 budget** | 5/5 成功 · 产物 100% | | **33** | **116137** | 5974 | 50% | **4** | 0 | 0.000 | | 15981 |

判定（报告末尾由 `judge()` 自动写出）：① 成功率 ✅ ② token **+7.7%（不降反升）**❌ ③ 压缩次数 4 vs 0 ❌ →
**未达标，`yuai.context.mode` 默认保持 legacy**。这一轮 budget 的峰值均值更低（50% vs 55%），但多付了 7 次 LLM 调用（压缩本身）与 4.8 秒平均延迟。
**微压缩六轮几乎没触发过**（八轮里只有两轮 6K 的长链路各清出过一次 1962 token），原因不是没到触发线，而是候选不够：
微压缩只动"最近 3 条之外"的工具结果，且按 `clear_at_least` 的约束**一次清不够 1500 token 就不动手**——
这批 4~7 步的任务在越线时，老的工具结果加起来通常清不够 1500 token（体积大的 `readFile` 结果往往还在最近 3 条里），
于是直接交给二级摘要。**微压缩要"历史里攒够又旧又大的工具输出"才划算**，短任务够不着。

**第二轮：`CONTEXT_EVAL_WINDOW_TOKENS=6000`（越界压力轮：窗口已小于任务的正常工作集）**

| mode | task | steps | llmCalls | promptTokens | peakPrompt | peak/窗口 | compactions | microcompactions | dupToolCallRate | est/real | wallMs |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| legacy | 搜索+写入+终端确认 | 4 | 5 | 18707 | 5756 | 96% | 0 | 0 | 0.000 | 0.970 | 9369 |
| legacy | 读长文+摘要+写入 | 4 | 5 | 16137 | 4925 | 82% | 0 | 0 | 0.000 | 0.960 | 8159 |
| legacy | 搜索+抓取+写入+确认 | 5 | 6 | 27470 | 7271 | **121%** | 0 | 0 | 0.000 | 0.982 | 12504 |
| legacy | 读两文件+对比+写入 | 4 | 5 | 14579 | 4975 | 83% | 0 | 0 | 0.000 | 0.866 | 8815 |
| legacy | 长链路：三读一搜一写一终端 | 5 | 6 | 31717 | 9039 | **151%** | 0 | 0 | 0.000 | 0.961 | 14585 |
| budget | 搜索+写入+终端确认 | 4 | 6 | 18524 | 5514 | 92% | 1 | 0 | 0.000 | 0.962 | 13809 |
| budget | 读长文+摘要+写入 | 4 | 6 | 16556 | 4522 | 75% | 1 | 0 | 0.000 | 0.957 | 14047 |
| budget | 搜索+抓取+写入+确认 | 5 | 7 | 24720 | 5875 | 98% | 1 | 0 | 0.000 | 0.974 | 20830 |
| budget | 读两文件+对比+写入 | 4 | 6 | 14622 | 3987 | 66% | 1 | 0 | 0.000 | 0.886 | 9891 |
| budget | 长链路：三读一搜一写一终端 | 5 | 7 | 30270 | 7301 | **122%** | 1 | 0 | 0.000 | 0.944 | 17982 |
| **汇总 legacy** | 5/5 成功 · 产物 100% | | **27** | **108610** | 6393 | **107%** | **0** | 0 | 0.000 | | 10686 |
| **汇总 budget** | 5/5 成功 · 产物 100% | | **32** | **104692** | 5440 | 91% | **5** | 0 | 0.000 | | 15311 |

判定：① 成功率 ✅ ② token −3.6%（<10% 门槛）❌ ③ 压缩次数 5 vs 0 ❌ → **未达标**。
这一轮把两个"结构性发现"同时钉死了：**legacy 组的峰值不受窗口约束**（107% 均值、最高 151%，消息数不到 15 就永远不会压缩），
而 budget 组也会越界（122%）——触发检查只发生在步边界，单步工具结果能一步顶穿（§6.9）。

**跨轮次方向性**（同一天，5 任务轮；任务集相同才并列，另有两轮 4 任务的参考值 −8.7% / −3.3%）：

| 轮次 | 窗口 | Σtoken legacy | Σtoken budget | Δ | 压缩 legacy→budget | llmCalls legacy→budget |
| --- | --- | --- | --- | --- | --- | --- |
| 19:03 | 12000 | 115245 | 108329 | −6.0% | 1 → 3 | 30 → 30 |
| 19:10 | 12000 | 127759 | 104973 | −17.8% | 1 → 3 | 31 → 30 |
| 19:17 | 12000 | 107821 | 116137 | **+7.7%** | 0 → 4 | 26 → 33 |
| 19:06 | 6000 | 98857 | 158295 | **+60.1%** | 0 → 7 | 26 → 42 |
| 19:13 | 6000 | 120254 | 118226 | −1.7% | 1 → 7 | 29 → 37 |
| 19:19 | 6000 | 108610 | 104692 | −3.6% | 0 → 5 | 27 → 32 |

**结论（为什么默认开关没有翻）**：

1. **成功率两组六轮全部 100%、产物 100%**（硬断言）——两级压缩**不会把任务做坏**，这是前提，前提成立。
2. **③ 压缩次数是六轮里唯一方向一致的指标：全部增加**（0~1 → 3~7），且每次都多付 LLM 调用（压缩本身）与延迟
   （平均 wallMs 一致变高）。要翻开关，先得让压缩次数不涨——目前没有做到。
3. **② 的 token 方向不稳定**：12K 三轮是 −6.0% / −17.8% / **+7.7%**，6K 三轮是 +60.1% / −1.7% / −3.6%。
   把六轮摆在一起看，**"更省 token"是一个落在 run-to-run 噪声（单任务 ≥±20%）里的说法，不能当作结论**；
   唯一稳定的收益是**峰值受控**：12K 三轮峰值/窗口均值 budget 49.6% vs legacy 52.7%（单轮看过一次翻转），
   6K 三轮 92.8% vs 104.3%，且 legacy 每轮都有任务顶穿窗口。
4. 所以按事先写死的三条标准：**① 达标、②③ 未达标 → 默认保持 legacy**，budget 作为已验证的备选保留（切回一行配置）。
   **这个"没达标就不上"的决定本身就是本轮的产出**——比一个挑轮次挑出来的百分比值钱。
5. 最有价值的产出不是开关，而是一条设计原则：**四层漏斗的额度必须互相成比例**。入口截断按绝对字节定
   （readFile 32 000 字符 ≈ 8k~19k token），运行层闸门按窗口比例定（35% / 50%）——单步洪峰大于窗口时，
   运行层再聪明也兜不住。这是下一轮要动的点（把单步工具输出额度与窗口挂钩），也是这批数字里唯一
   **跨轮次稳定复现**的结论。

---

## 6. 已知限制（主动交底）

1. **异步写入不保证进程被 `kill -9` 时不丢最后一条**：`@PreDestroy` 排空 5 秒是正常退出的兜底，强杀没有兜底。
2. **估算器是启发式不是精确分词**：误差方向稳定即可，安全系数 1.1 偏向早压缩；误差用 A/B 的估算/实测比标定。
3. **单写者假设**：画像「读-改-写」靠单线程串行，多进程/多实例部署会互相覆盖（当前部署模型内不成立，跨进程不在范围内）。
4. **transcript 只落盘不做恢复**：没有 `/resume`，落盘的价值是"不可逆操作前留证据"与事后审计。
5. **压缩比例参数（35% / 50% / keep=3 / clear_at_least=1500）来自对标默认值，未在本项目的真实流量上调参**——
   有的是小样本 A/B，不是流量调参。这是与产品级（Claude Code）的核心差距之一。
6. **没有服务端压缩**：本项目是单机应用，Claude Code 那种"服务端按 conversation 压缩"的形态涉及多租户与状态外置，不在范围内。
7. **A/B 样本小（4~5 任务），单任务 token 有 ±20%+ 的 run-to-run 抖动**——`deepseek-v4-flash` 每次搜索/摘要结果不同，
   步数也不同（同一个长链路任务在不同轮次里跑出过 4 步与 8 步），上下文体积随之变化。
   因此只有**结构性指标**（成功率、压缩次数、峰值/窗口比）是稳健结论，单任务的 token 差要按多轮看方向。
   实测：同一个 legacy 任务五轮分别花 23952 / 23541 / 19120 / 15108 / 22629 prompt token（1.59 倍跨度）。
8. **A/B 跑在真实 API 上，会碰上外部故障**：2026-10-10 18:36 起 DeepSeek 出现约 20 分钟的 `I/O error on POST` + 429/502，
   那一轮的 legacy 组 5 个任务全部 240 秒超时、数字作废。修法是让 harness 在**首次超时就中止整轮并断言失败**
   （而不是继续跑完产出一张"两组都很难看"的表），报告文件里也写明"本轮未跑完"。教训：`future.cancel(true)` 掐不断已经在跑的
   LLM 调用，被放弃的 agent 会继续占着 API 打，让后面每个任务都更难成功——所以"继续跑"不是更省，而是更贵。
9. **预算是"步边界上的闸门"，不是"绝不溢出"的保证**：触发检查只在每步开始（`think()` 之前）做一次，
   而**单个工具结果一步就能把 prompt 顶上去**——`readFile` 的上限是 32 000 **字符**，按估算口径是 8k~19k token
   （ASCII ÷4 到 CJK ×0.6），比压力轮的 12K 窗口还大。**三轮 6K 实测：legacy 组每轮都有 2~3 个任务的峰值超窗
   （最高 151% ）**——它按消息条数压缩，消息只有 5 条时根本不会触发；budget 组把峰值压到窗口附近（三轮均值 93% vs 104%），
   但仍出现过 103% / 122% / 148% 的越界（压缩刚做完、下一条工具结果又把它顶上去）。
   结论是一条设计原则，不是一个 bug：**四层漏斗的额度必须互相成比例**——① 入口截断按绝对字节定（32KB），
   ③ 运行层闸门按窗口比例定（50%），两者没有耦合时，单步洪峰可以直接把 prompt 顶过窗口，任何运行层策略都兜不住。
10. **预算模式有自己的越界成本（thrashing）**：窗口小于"保护集（记忆块 + 原始任务 + 最近 6 条）+ 单步最大工具输出"时，
    压缩后仍在触发线之上 → 反复压缩；被清掉的工具结果又让模型重新调用（`dupToolCallRate` 就是给这个副作用准备的指标）。
    **最坏一轮实测**（6K，渲染器修复前）：长链路退化到 12 步 / 17 次 LLM / 82 290 token / dupRate 0.118 / 73.7 秒，
    而 legacy 在同一轮只要 5 次调用——代价是峰值无界（123% / 141% 窗口，真发出去就是 400）。
    修复渲染器之后的两轮 6K 没有再复现这个极端值：长链路分别是 8 步 / 12 次调用 / 45 498 token 与 5 步 / 7 次调用 / 30 270 token
    （`dupRate` 全程 0.000），但"比 legacy 多付调用"仍在（同两轮 legacy 只要 8 次与 6 次）。
    **"更省 token"不是预算模式的性质，是它在收益区间内的性质。**
11. **本轮没有测到 budget 的收益区间的中间段**：收益需要同时满足"任务长到会顶到触发线"与"窗口 ≥ 保护集 + 单步最大工具输出"。
    这批任务在 12K 下几乎不触发、在 6K 下两组都溢出，所以 A/B 只摸到区间两端。要画出完整曲线，需要
    "单步工具输出大 + 步数多 + 窗口取真实值"的一组实验（例如连续读 10 个大文件），**记为下一轮评测项，不拿现有数字硬说它更省**。

---

## 7. 面试口径

**① 主线（30 秒，先结论后数字）**

> "我的 Agent 上下文管理原来按消息条数触发压缩。我读 Spring AI 字节码时发现一个 bug：
> 压缩时渲染历史给 LLM 的代码用的是 `getText()`，而 `ToolResponseMessage` 的 text 是空串常量——
> 我每次压缩，工具输出在摘要器眼里全是空的，既丢信息又白花一次 LLM。
> 我按 Claude Code 的四层漏斗重做了：入口截断、常驻做小、运行时分两级（先清旧工具输出、再按 token 预算摘要）、退出层落盘重注入。
> 顺带把记忆写入移出请求线程：那 3~8 秒用户早就拿到答案了，我用线程栈 A/B 证明改前它压在请求线程上。
> 最后我给这套新机制做了 A/B——**结论是没达到我自己事先定的判定标准，所以默认开关我没有翻**，
> 原因和数字都写在文档里。"（主动交底一条负结果，比报一个说不清来源的百分比值钱得多）

**② 三个深水区（被追问时往里走，每个自带取舍）**

1. **为什么用估算器不用 tokenizer**：预调用决策只需误差方向稳定；安全系数 1.1 偏向早压缩；误差用 A/B 的估算/实测比校准。
2. **为什么微压缩保留最近 3 条且一次清够（`clear_at_least`）**：改历史会打穿 prompt cache 前缀，小碎步清理是反复烧钱；
   刚用过的输出最可能马上被引用——这个副作用我用新增的「重复工具调用率」量化，而不是嘴上说"影响不大"。
3. **为什么异步写入用看门狗而不是 interrupt**：中断可能留半写文件；拿"可能超时未完成"换"绝不半写"，
   有界队列兜住内存，队列满就丢弃并计数（`CallerRunsPolicy` 会把耗时压回请求线程，正好毁掉异步化的意义）。
4. **为什么我做了 A/B 却**没**把默认开关翻过来**（最能说明工程判断力的一条）：
   我在方案里先写死了三条判定标准（成功率不降 / token 或重复调用率降 10% / 压缩次数不增加），六轮实测下来
   ① 全达标、③ 全不达标、② 的 token 方向在噪声内**翻转过**（同一天 12K 三轮 −6.0% / −17.8% / +7.7%）——
   于是默认仍是 legacy，并把"未达标"和原因一起写进报告与文档。**优化不是"改了就更好"，而是"改了、测了、按事先定的标准决定要不要上"**；
   这里还有一个更普适的教训：**先定标准再看数字，和看完数字再挑标准，是两件完全不同的事**——
   如果我事后才定标准，那 −17.8% 那一轮就足够"证明"预算模式更省，而它是噪声。
   顺带一个反直觉的发现：预算是"步边界上的闸门"，入口截断（readFile 32KB 字符 ≈ 单步 8k~19k token）如果按绝对字节定，
   单步洪峰可以直接把 prompt 顶过窗口，任何运行层策略都兜不住——**四层漏斗的额度必须互相成比例**，这是下一轮要改的。

**③ 主动交底（说 2~3 条）**：不做 TTL 用配额（画像偏好是长期资产，TTL 会随机删掉"上次说的偏好"）；
异步不保证 `kill -9` 不丢最后一条；压缩参数来自对标默认值，只做过小样本 A/B、没做过流量调参；
以及**我自己的评测器踩过空真陷阱**——判定"下降 ≥10%"时 `0 <= 0` 恒真，把"两组都没重复调用"记成了达标，
是我复核报告时抓出来的（判据写宽松比不做评测更糟）。

**④ 可背的总结句**：
"我把上下文当成**一个有预算的资源**来管：进入时截断、常驻的小、运行时分两级压、退出时外部化——
每一层我都能说出对标谁、代价是什么、用哪个数字验证。"

**⑤ 陷阱提醒**：说"对标 Claude Code"必须补一句"**机制对标、规模差两个数量级**"，否则一问就露；
先给结论和数字，实现细节等对方要。

---

## 8. 验证命令

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)

./mvnw -q test-compile
./mvnw -Dgroups=unit test                       # 127 例离线全绿（上轮 113 + 单用户单会话轮 14；每阶段准入条件）
RUN_MEMORY_EVAL=true ./mvnw test -Dtest='LayeredMemoryEvaluationTest,MemoryUpdateServiceTest'
RUN_CONTEXT_EVAL=true CONTEXT_EVAL_CASES=5 ./mvnw test -Dtest=ContextCompactionAbEvalTest   # 真实 LLM，约 4 分钟
# 越界压力轮（窗口小于任务工作集，用于观察退化边界）：
RUN_CONTEXT_EVAL=true CONTEXT_EVAL_CASES=5 CONTEXT_EVAL_WINDOW_TOKENS=6000 ./mvnw test -Dtest=ContextCompactionAbEvalTest
bash scripts/check-secrets.sh
```

冒烟：`./mvnw spring-boot:run` 后日志应出现 `AGENT_METRICS {json}` 行；`--yuai.context.mode=budget` 下多步任务应出现
`微压缩` / `上下文压缩` 日志与 `tmp/transcripts/` 落盘。

---

## 9. 单用户单会话收敛（2026-10-11，下一轮）

> 本轮决策来自需求方原话：「**不搞多用户了，搞单用户，每次和 AGENT 对话前都恢复之前的记忆**」
> 「**以后所有会话都当做单一会话来看，如果一个会话终止，下一个对话开启时，会全量加载上一次会话的历史，
> 因此这个项目只需要维护一个用户画像文件、摘要文件等**」。

### 9.1 问题（为什么上一轮的设计还不够）

| # | 现状 | 证据 |
| --- | --- | --- |
| Q1 | userId 是**死复杂度**：无登录体系，实际恒为 `"default"`，却贯穿 HTTP 参数、SSE 事件、指标日志、记忆层、前端 localStorage 五处 | `AiController` 两端点签名 / `AgentRunMetrics` / 前端 `localStorage` |
| Q2 | 记忆工件按 `(userId, conversationId)` 复合键散成多文件，随会话数增长 | `tmp/memory/profile/{userId}/`、`summary/{userId}/{conversationId}.json` |
| Q3 | **没有逐字恢复通道**：Agent 每请求 new 且 `messageList.clear()`，唯一恢复是 ≤1200 字符的记忆块。关页面/换 chatId 后，上一段对话的**原话**全部丢失，只剩有损摘要 | `BaseAgent.initializeMemoryLayers` / `MessageWindowChatMemory` |

Q3 是真正的能力缺口：摘要是"概览"，不是"记得"。用户问「我刚才让你做什么」，摘要答不出来。

### 9.2 业界对标（参考对象 / 其机制 / 本项目取舍）

| 参考对象 | 其机制 | 本项目取舍 |
| --- | --- | --- |
| **Claude Code 单会话连续模型** | 会话写 transcript；`--continue` 从 transcript 恢复；压缩 = 摘要 + 保留近场；进程重启不丢会话 | 采纳「近场原文恢复 + 滚动摘要」骨架；不做 `--resume` 交互命令（无 CLI 场景），恢复对用户透明 |
| **单线聊天产品（每轮重发全部历史）** | 把整段会话原样带上，简单、无状态 | **不采纳**：12 条历史 ≈ 每次 run 每步 LLM 调用都重新付费，且必然越线触发压缩（见 9.3 算术）。"全量加载"落地为三段式 |
| 本项目 L5 向量归档 | 全量落盘、按需检索 | 保留为"深挖"兜底：近场（原文）+ 中程（摘要）+ 全程（检索）各管一段 |

### 9.3 设计：三段式「全量加载」与一组算术

**消息顺序（硬约束）**：`[记忆 SystemMessage] → [近场 2N 条原文] → [本轮用户消息]`，恢复**严格 2N 条、不加任何包装 Head**。

**默认 N=6 的算术依据**（逐行核实过 legacy 压缩的触发点）：

```text
首查 size = 2N + 记忆SM(1) + 本轮用户消息(1) + nextStepPrompt(1) = 2N + 3
N=6 → 15 ≤ 15（触发条件是严格 > 15）→ 纯聊天/单步任务不因恢复触发压缩
N=7 → 17 > 15 → 首查即触发
```

代价被锁死在**多步任务**上（step2 起触发一次压缩，属预期）；收益是「接着上一段说」成为默认行为。
`BaseAgentMemoryRestoreTest` 用真常量（`ToolCallAgent.MAX_CONTEXT_MESSAGES`）钉死这个平衡——
阈值、默认轮数、恢复条数任一方被改动，测试必须跟着红。

**恢复的成本上界（算术）**：12 条 × ≤1200 字符 = ≤14 400 字符 ≈ 3.6k~8.6k token（按本项目估算口径 ASCII÷4 ~ CJK×0.6）；
真实对话一轮通常几十字符，量级在几百 token。**实测见 9.7「恢复成本」行**。

### 9.4 文件布局（收敛为固定三件）

```text
tmp/memory/profile.json     单份画像（跨会话/跨端共享）
tmp/memory/summary.json     单份滚动摘要（承载全程历史概览）
tmp/memory/history.jsonl    本轮新增：append-only 逐字历史，每行 {"ts","user","assistant","source"}，上限 200 轮
tmp/chat-memory/single_memory.bin   LoveApp Kryo 窗口固定 key
tmp/transcripts/{conversationId}/   去掉 userId 目录层
```

`conversationId` 仅保留为日志追踪 / SSE `session` 回执 / 前端会话 id，**不参与任何记忆寻址**。

### 9.5 写入：双通道与「失败轮不落历史」

- **画像 / 摘要 / 归档**：仍走 `MemoryWriteQueue` 异步（上一轮的成果，不动）
- **history.jsonl**：**同步**追加。刻意不走队列——异步队列与同步追加写同一个文件会形成双通道竞态，
  且会打破 `AtomicJsonWriter` 的单写者假设；`FileConversationHistoryRepository` 的 append/裁剪切在**同一个临界区锁**里
  （`FileConversationHistoryRepositoryTest` 用 4 线程并发 append + 裁剪验证"一行不丢、恰好等于上限"）
- **失败轮跳过 history**：finally 里**先快照 `state == ERROR`**（cleanup 会把 ERROR 复位 IDLE），
  否则「出错了：…」会被回放进下一轮上下文；画像/摘要照写（与改造前一致）
- **子 Agent 记忆全关**（`memoryWriteEnabled=false`，一个开关同管恢复 + 写入）。
  顺手修了一个真 bug：旧实现把开关当 `writeProfile` 传给 `recordAgentInteraction`，**只跳过画像**，
  子 Agent 的摘要与归档照写——单键化后那就是往唯一那份工件里灌包装过的子任务指令。
  修法是开关上移到调用方（`BaseAgent.recordMemoryAfterRun` 提前 return），签名少一个布尔参数、少一条"以为关上了其实没关"的路径
- **`appendHistory` fail-open**：同步写在请求线程上，落盘失败最多"少记一轮"，绝不能让一次已成功的对话变成 500
  （`MemoryUpdateServiceHistoryTest` 三例：仓库抛 IOException 不外传 / 开关关闭不写 / 空内容不写）

### 9.6 四处「首条 UserMessage = 本轮任务」的假设（恢复注入的直接后果）

恢复把历史 UserMessage 放进 messageList 后，所有"扫第一条 UserMessage"的代码都会抓到历史消息：
`synthesizeFinalAnswer`（会答错"原始问题"）、`ToolCallAgent.verifyTaskCompletion`、`ContextWindowManager.findOriginalTask`、
`YuManusMultiAgent.getOriginalUserGoal`（会 fallback join 整块记忆）。
统一改为读 `BaseAgent.currentUserPrompt`（run/runStream 入口赋值）。
`ContextWindowManagerTest` 新增一例钉死定位方式：**按原文从末尾向前精确匹配、返回列表里的原对象**（引用相等），
否则压缩后的「重注入原始任务」会注入错的那一条。

### 9.7 实测数字

| 指标 | 结果 |
| --- | --- |
| 离线单测 | `./mvnw -q -Dgroups=unit test` **127 例全绿**（上轮 113 + 本轮 14：历史仓库 5、恢复 5、appendHistory 3、originalTask 定位 1） |
| 记忆评测（真实 LLM） | `RUN_MEMORY_EVAL=true` **6 例全绿**；超长归档写入 **1526 ms**（修复前 143~146 s，回归守住） |
| 前端 | 两个工程 `npm run build` 均绿（he 132.43 kB / yu 107.75 kB 主包，删 userId 后无残留引用） |
| 工件收敛 | `tmp/memory/` 固定 3 文件，不随会话数增长（Q2 消除） |
| 恢复成本（实测） | 同一句消息、同一模型，`--yuai.memory.history.restore-turns=0` vs 默认 6，比对**首轮 LLM 请求体**（`MyLoggerAdvisor` 打的完整 Prompt）：2003 → 3217 字符，**净增 1214 字符 / 恰好 12 条消息**（=6 轮 ×2，日志行「近场恢复 6 轮历史」对得上）。按项目估算口径 ≈ **+348 token**，按 `AGENT_METRICS` 的估算/实测比 0.91 折算真实约 **+320 token**——远低于设计上界（12×1200 字符），因为真实对话每轮只有几十字符 |
| 恢复的可见性 | 同一份首轮请求里能直接读到恢复进去的原文（`UserMessage{content='记住一个暗号：紫罗兰…'}` / `AssistantMessage … textContent=紫罗兰`），即"模型确实拿到了上一段对话的逐字内容"，不再是只能靠回答反推 |

### 9.8 已知限制与交底

1. **不做数据迁移**：`tmp/` 是一次性目录，旧布局不自动迁移。**2026-10-11 收尾时按用户指令把旧布局整体删除**（`tmp/memory/` 146 个文件含 15 份按用户画像目录 + 16 份按会话摘要目录、`tmp/chat-memory/` 25 个按 chatId 的 Kryo 窗口、`tmp/transcripts/` 44 份压缩前落盘，共 215 个文件 ≈ 1.6 MB）——所以"手工迁移"这个入口也不存在了，记忆从零重新积累。删除前唯一一份真实画像（`profile/default/profile.json`）内容为「单身 / 昵称小帅 / 风格简洁 / 长期目标：想通过相亲找到对象 / notableFacts: 近期关注相亲场景」。目录在首次写入时由各仓储的 `mkdirs()` 按需重建（`FileUserProfileRepository:35`、`FileRecentSummaryRepository:31`、`FileConversationHistoryRepository:46`、`KryoFileBasedChatMemoryRepository:48`、`context/TranscriptWriter:63`），空目录启动已验证可正常装配（0 ERROR/WARN）。
**PG 侧同一轮清空**：`truncate table chat_memory_store` 删掉 **135 行**旧归档（旧会话 + `ctx-ab-*`/`phase3-*` 评测噪音 + 冒烟数据）——L5 改成无过滤检索后，带旧 chatId metadata 的行同样会被召回，不清等于旧记忆仍在。`vector_store`（知识库，15 行）与 `tool_vector_store`（工具路由，8 行）**不是记忆，未动**。
2. **"全量加载"是有损全量的落地形态**：近场原文（N 轮）+ 全程摘要 + 检索兜底，不是"把 10 万 token 全带上"。
3. **单写者假设被强化**：全局唯一一份工件，多实例部署会互相覆盖（与上一轮同一条限制，范围不变）。
4. **恋爱大师与 agent 共享同一份记忆/检索库**：单会话语义的必然结果，不是 bug；要分开就得重新引入键，与需求相反。
5. **恢复的 token 成本随轮数线性**：`restore-turns` 与 `max-chars-per-message` 是两个旋钮；评测类固定设 0，保持单一变量。

### 9.9 面试口径（四段式）

> **问题**：我的记忆系统按 `(用户, 会话)` 双键存文件——但项目根本没有登录，userId 永远是 `"default"`，
> 于是"双键"只是把同一份数据散成多个文件；更关键的是**没有逐字恢复**：新会话只能拿到一份有损摘要，
> 用户问"我刚才让你做什么"，答不出来。
>
> **业界怎么做**：Claude Code 是单会话连续模型——会话写 transcript，`--continue` 直接恢复；
> 压缩时才做"摘要 + 保留近场"。聊天产品则是每轮重发全部历史。
>
> **我的取舍**：真·全量重发每轮都要为整段历史付费，而且**必然越线触发压缩**——我把"全量"拆成三段：
> 近场 N 轮原文（每次 run 起始恢复）+ 全程滚动摘要 + 向量库检索兜底。默认 N=6 不是拍的：
> legacy 压缩的触发条件是消息数严格大于 15，恢复 6 轮 = 12 条，加记忆块、用户消息、nextStepPrompt 恰好 15，
> 纯聊天与单步任务**不因恢复触发压缩**；7 轮就必然触发。这个平衡我用真常量写了钉死单测。
>
> **实测数字**：离线 127 例全绿、真实 LLM 记忆评测 6 例全绿、工件从"按会话增长"收敛到固定 3 文件；
> 顺带在改造中发现并修掉一个真 bug——子 Agent 的"记忆开关"其实只关了画像，摘要和归档一直在写。

**陷阱提醒**：说"全量加载"时要主动补一句"是**近场原文 + 摘要 + 检索**的三段式，不是每轮重发全部历史"——
否则面试官会立刻追问"那你每次请求多大"。这条与 §7⑤ 同源：**机制可以对标，规模必须交底**。
