# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

Spring AI 1.0 教学项目（AI 智能体平台）：ReAct 自主智能体（YuManus）、多智能体协调器（YuManusMultiAgent）、工具全量加载（默认）与动态调用机制（元工具 / 语义路由 / 降级链 / 熔断，保留为教学/回退）、分层记忆（**单用户单会话**：画像 + 滚动摘要 + 近场历史恢复 + 向量归档，2026-10-11 收敛）、RAG（PGVector）、SSE 流式输出。代码注释为中文；教学性注释与 TODO 是刻意保留的教程素材，不要清理。

## 终极目标（每一次优化的北极星）

把本项目的 agent 做成**高性能、高可用、低延迟、低 token 消耗**、能真正落地解决真实问题的智能体。所有优化必须遵守四条准则：

1. **对标业界最强，禁止凭空设计**：每次优化前先调研业界最强 agent（Claude Code、OpenHands、Manus、OpenAI Agents SDK 等）对同一问题的做法，在方案中写明「参考对象 / 其机制 / 本项目的取舍与理由」；没有参照对象的方案不进入执行。
2. **优化必须可度量**：每次优化完成后必须跑效果评测并给出**具体指标**（端到端延迟、token 消耗、工具选择准确率、任务成功率、失败率等），与优化前对比；**没有指标数字的优化不算完成**。评测入口见「评测体系」一节，新指标要落到测试或脚本里。
3. **四个维度一起取舍**：性能（吞吐/并发）、可用性（失败可恢复、不挂死、可观测）、延迟（端到端）、token 成本（上下文压缩、工具结果裁剪、路由与元工具开销），与正确性并列，作为每个方案的取舍依据。
4. **可讲述性（本项目的现实使命是成为一份能拿到 Java/Agent 实习 offer 的作品）**：每一次改进除了指标，还要沉淀成面试里讲得清的一个点——**「问题 → 业界怎么做 → 我的取舍 → 实测数字」**；方案文档里的「面试口径」一节就是它的落地形式。面试官考察的是决策过程而不是功能清单，说不清「为什么这么改」的改动等于没做。反过来，为了简历好看而堆的、讲不出取舍的「闪光点」（跟风选型、无对照的新技术）不算闪光点——真闪光点 = 别人踩过的坑 + 你量化过的解法。

## 常用命令

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # 需要 JDK 21

./mvnw -DskipTests compile       # 编译主代码（干净构建约 1 分钟）
./mvnw spring-boot:run           # 启动：8123 端口，context-path /api，默认 local profile
./mvnw test -Dtest=ToolPathUtilsTest        # 跑单个测试类（逗号分隔可跑多个）
```

**⚠️ 绝对不要直接跑 `./mvnw test`**：39 个测试类里 14 个是 `@SpringBootTest`（要本地 PG + application-local.yml 的真实 key），其中 **4 个无门控地打真实外部调用**——`LoveAppTest`（3 轮真实对话）、`AiControllerTest`（1 轮真实对话，走 RAG）、以及联网的 `WebSearchToolTest`/`WebScrapingToolTest`。**4 个昂贵评测类都有环境变量门控**（`RUN_TOOL_EVAL` / `RUN_MEMORY_EVAL` / `RUN_CONTEXT_EVAL`，见下），不开就跳过。剩下 25 个非 Spring 类里 **22 个标了 `@Tag("unit")`**（离线子集，一条命令跑完，约 8 秒 **127 用例**；其中 `ResourceDownloadToolTest` 会真实下载 example.com，非严格离线），另外 3 个不随分组跑：联网的 `WebSearchToolTest`/`WebScrapingToolTest` 与真实调用的 `ToolLatencyBenchmarkTest`：

```bash
./mvnw -q -Dgroups=unit test      # 等价 CI 命令；新增离线测试请照抄类上的 @Tag("unit")
```

（历史遗留已修：`ToolSelectionAccuracyTest:191` / `LayeredMemoryEvaluationTest:83/84/86/125/147` / `MemoryUpdateServiceTest:16` 三个类的旧签名曾让整个 test source set 编译失败、连 `-Dtest=X` 都跑不了；现在 `./mvnw -q test-compile` 是绿的，三个类也都补了环境变量门控。）

```bash
# 工具选择准确性评测（真实 LLM，昂贵；必须显式开 RUN_TOOL_EVAL）
RUN_TOOL_EVAL=true EVAL_CASE_COUNT=20 EVAL_ROUNDS=3 EVAL_CATEGORY=multi ./mvnw test -Dtest=ToolSelectionAccuracyTest
# 分层记忆评测 / 画像写入测试（都被 @EnabledIfEnvironmentVariable 默认关闭）
RUN_MEMORY_EVAL=true ./mvnw test -Dtest=LayeredMemoryEvaluationTest
RUN_MEMORY_EVAL=true ./mvnw test -Dtest=MemoryUpdateServiceTest
# 上下文压缩 A/B（legacy vs budget，真实 LLM，约 4 分钟）
RUN_CONTEXT_EVAL=true CONTEXT_EVAL_CASES=5 ./mvnw test -Dtest=ContextCompactionAbEvalTest
# 越界压力轮（窗口小于任务工作集，看退化边界而非收益）：加 CONTEXT_EVAL_WINDOW_TOKENS=6000
```

同级的两个前端：`yu-ai-agent-frontend`（原版，带 Dockerfile/nginx）、`he-ai-agent-frontend`（本地开发版）；接口基址都硬编码 `http://localhost:8123/api`。

## 核心架构（跨文件才能看懂的部分）

### Agent 继承链与"手动 new"约定

`BaseAgent`（执行循环 + 状态机 + SSE + 记忆钩子）→ `ReActAgent`（think/act 拆分）→ `ToolCallAgent`（工具暴露/降级/上下文压缩/熔断/重试；关键词+向量路由与 L2 会话缓存仅 dynamic 模式）→ `YuManus`（maxSteps 20）/ `SearchAgent`（3）/ `DocumentAgent`（6）；`YuManusMultiAgent` **直接继承 BaseAgent**（自己不碰工具，见下）。

**关键约定**：Controller 每次请求 `new` Agent，不注入单例（`YuManus` 的 @Component 是死代码，Agent 状态非线程安全）。`ToolVectorRouter`、`ToolRoutingProperties`、`ContextWindowManager`、`StreamingProperties` 必须沿构造器链显式传递（AiController → YuManus/YuManusMultiAgent → 子 Agent），**不要用 setter 或 @Autowired 注入**——这是历史 bug 的修复方向。AiController 自身用 `@Resource` 字段取依赖，再手动 new Agent。（流式轮给 `BaseAgent` 加了 `protected BaseAgent(StreamingProperties)`，**它现在是显式构造器**：Lombok 不再生成无参构造器，测试桩要写 `super(null)`。）

### 模型与嵌入（一个反直觉的坑）

- **所有 `ChatModel` 注入拿到的都是 `@Primary deepSeekChatModel`**，包括构造函数里参数名叫 `dashscopeChatModel` 的那些（YuManus / SearchAgent / DocumentAgent / LoveApp / QueryRewriter / MemoryUpdateService）。Spring 6.2 的多候选解析顺序是 `@Primary` → 参数名匹配 → `@Priority`（`DefaultListableBeanFactory#determineAutowireCandidate` 里 primary 命中即返回），所以**参数名是历史遗留的误导**：改参数名不会切到通义千问，要让 qwen-plus 生效必须挪走 @Primary 或改用 @Qualifier。
- 实际模型 id 来自 application.yml 的 `spring.ai.openai.chat.options.model: deepseek-v4-flash`；配置类里的 `deepseek-v4-pro` 只是属性缺失时的默认值，当前不生效。
- **嵌入走 DashScope `text-embedding-v1`（1536 维），不是 v2**：全仓库（含 git 历史）没有任何 `spring.ai.dashscope.embedding.options.model` 配置，走的是 `DashScopeEmbeddingProperties` 的默认常量（`spring-ai-alibaba-autoconfigure-dashscope` 1.0.0.2 里 `DEFAULT_EMBEDDING_MODEL = "text-embedding-v1"`）。要换模型必须显式配这个属性，且换完三张向量表（`vector_store`/`tool_vector_store`/`chat_memory_store`，均 1536 维）都得重灌——v1/v2 维度相同但向量空间不同，混用等于噪声。`MemoryVectorProperties` 里"与 dashscope text-embedding-v2 一致"的注释是错的。
- 嵌入模型 Bean 全容器只有 `dashscopeEmbeddingModel` 一个：Ollama 的 `ollamaEmbeddingModel` 带 `@ConditionalOnMissingBean` 会被顶掉（另一个坑是 DashScope 这个自动配置类在 1.0.0.2 里误条件在 `spring.ai.model.audio.speech` 上，靠 `matchIfMissing=true` 才默认生效），所以参数名 `dashscopeEmbeddingModel` 这次是名副其实的。

### 工具体系（四层来源）

1. **内置工具**：`tools/ToolRegistration.java` 的 `allTools` Bean = 8 个方法（readFile/writeFile/searchWeb/scrapeWebPage/downloadResource/executeTerminalCommand/generatePDF/doTerminate）。业务工具用 `@Tool`/`@ToolParam`，工具名 = 方法名（**不写 name 属性**）。`searchWeb` 的降级链是 **Tavily（主链路，key 读 `yuai.api-keys.tavily`，OkHttp 显式超时 10/15/20s）→ 百度 → Bing**（原 SearchAPI 随 key 泄露弃用而移除）。
2. **命名工具数组**：同文件另有 `searchTools`/`documentTools`/`executionTools` 三个 `ToolCallback[]` Bean，给多智能体的子 Agent 分工具集用（`executionTools` 目前无人引用）。
3. **MCP 工具**：`mcp-servers.json` 装配出 ToolCallbackProvider，AiController 的 `mcpTools()` 运行时合并（当前仅 searchImage）。**不进向量索引**。注意该 json 里是 MCP 服务 jar 的**绝对路径**，换机器或未 `mvn package` 该子项目时 MCP 工具会静默消失。
4. **元工具**：`SearchMemoryTool` 在 `ToolCallAgent` 构造器内 per-agent new、追加进 availableTools——**不注册 Bean、不进 allTools、不进索引**，与业务工具相反**显式写了 `@Tool(name = ...)`**。`ToolSearchTool`/`ListToolsTool` 只在 `exposure-mode=dynamic` 时创建（默认 full 模式不注册），源码保留为教学素材；dynamic 下 searchTools 持有 Agent 的 L2 集合直接引用写入，靠 `ToolCallbacks.from()` 包装（它返回包装对象，拿不回原实例，所以构造器里先存原实例引用）。

### 工具暴露模式（full 全量加载为默认；dynamic 为教学/回退）

- **默认 `yuai.tool-routing.exposure-mode: full`（2026-10 工具重构引入）**：每轮把全部常驻工具原样发给模型，不做关键词/向量路由，也不注册 searchTools/listTools。对标 Claude Code / OpenHands 的固定工具面，以及 Manus "Mask, don't remove"（任务中途不增删工具，请求前缀稳定、缓存友好）。决策过程与实测数字见 docs/tool-refactor-plan.md §9
- full 模式的真实 schema 开销（`ToolSchemaBudgetTest` 可复现）：常驻 9 个工具 5860 字符 ≈ 1465 tokens/次 LLM 调用；相对 dynamic 省掉每步一次路由 embedding 往返（延迟↓），代价是 schema 多几百 tokens/次
- **dynamic 是保留的教学/回退能力**：实现完整保留在 `routeToolsForCurrentStep`——暴露集合 = 元工具（searchTools/listTools/searchMemory）∪ `fixed-tools` ∪ L2 会话缓存 ∪ 本轮路由结果；路由 = 关键词（`routeToolNames` 硬编码分组）∪ 向量（`ToolVectorRouter`，表 `tool_vector_store`，threshold 0.45 / topK 2，**调优过的值，别改回 0.2/5**——那会让 topK 每轮饱和、路由失效）；`schema-version` 递增在 dynamic 启动时清空重建索引（改工具集时要 +1）
- dynamic 的两条兜底：路由结果与 availableTools 完全不匹配 → 回退全量；只剩 doTerminate 时**不回退**（纯聊天场景本来就该只给 doTerminate）
- 熔断过滤两种模式共用：全部工具被熔断时只放行 doTerminate
- 容错常量：单次工具调用 30s 超时、失败重试 2 次、同一工具连续失败 3 次熔断 60s；`chatOptions` 关闭自动执行与并行调用、maxTokens 1024
- 上下文压缩有**两套实现**，由 `yuai.context.mode` 二选一（2026-10 上下文重构 · 阶段 2）：`legacy`（默认，消息数 > 15 时把前段用 LLM 摘要成一条 SystemMessage，保留最近 6 条；`MAX_CONTEXT_MESSAGES`/`RECENT_MESSAGES_TO_KEEP`，触发与重建逻辑一行未改）| `budget`（token 预算两级压缩，见下）。分流点在 `ToolCallAgent` 的压缩调用处
- **budget 模式**（`context/ContextWindowManager`，对标 Claude Code 运行层 + Anthropic `clear_tool_uses`）：一级微压缩（估算 ≥ 窗口 35% 时把最旧的工具结果换成占位文案，零 LLM 成本、保留最近 3 条、一次至少清够 1500 tokens）→ 二级摘要（≥ 50% 时先落盘 transcript 再用 LLM 摘要，保留最近 6 条并**重注入**记忆 SystemMessage 原文 + 原始用户任务 + 最近 5 个文件路径）。估算走 `ContextTokenEstimator`（CJK×0.6 + ASCII÷4，安全系数 1.1），**它特判 `ToolResponseMessage.getResponses()`**——`getText()` 恒为空串，legacy 摘要器正是栽在这里
- 证据链与 messageList 解耦：`doTerminate` 的三方比对读的是 `act()` 写入的环形缓冲（最近 8 条），不读 messageList——否则微压缩后会把占位符当证据
- searchTools 降级链（仅 dynamic）：语义（阈值常量硬编码 0.2，**与 yml 里给路由用的 0.45 不是一回事**）→ 阈值放宽（×0.5、topK×2）→ 关键词回退 → 结构化失败提示
- `buildRoutingContext` 排除 ToolResponseMessage 防关键词污染（RAG 检索共用）；路由关键词一律走 `KeywordMatcher.containsAnyWord`（英文按词边界），**别退回 `String.contains`**——`"file"` 会命中记忆注入的 `[User Profile]`、`"ls"` 会命中 `tools`/`results`
- `doTerminate` 带必填 `taskChecklist`；act() 终止前用 LLM 做三方比对（原始任务 + 自述清单 + 工具执行证据），遗漏则拒绝终止并反馈模型；校验调用本身失败时 fail-open
- SearchAgent 专属裁剪：`limitRedundantToolCalls` 去掉重复的 around_search / searchWeb，`isSearchAgentToolFailure` 遇 429 / "no search results" 直接置 FINISHED

### 分层记忆系统（单用户单会话版，2026-10-11 收敛）

5 层，全部落在 `${user.dir}/tmp/`（`FileConstant.FILE_SAVE_DIR`）与 PG 里。
**寻址键已全部删除**：无登录体系时 userId 恒为 `"default"`（死复杂度），而"会话"在本项目语义里也只是一条线——记忆工件全局唯一一份，`conversationId` 只作日志/SSE/前端兼容，**不参与任何记忆寻址**：

| 层 | 内容 | 位置 |
| --- | --- | --- |
| L1 | 会话元数据（channel/locale/mode/requestType） | 每请求内存态，不落盘 |
| L2 | 用户画像（感情状态/昵称/回答风格/长期目标/notableFacts） | `tmp/memory/profile.json`（单份，跨会话/跨端共享） |
| L3 | 滚动摘要（承载全程历史概览） | `tmp/memory/summary.json`（单份） |
| L4 | 短期窗口：Agent 用 `messageList` + 上下文压缩 + **近场历史恢复**；LoveApp 用 Kryo 窗口记忆（20 条，**固定 key `single`**） | LoveApp 落 `tmp/chat-memory/single_memory.bin` |
| L4.5 | **近场历史日志（本轮新增）**：append-only 对话逐字记录，每次 run 起始恢复最近 N 轮原文 | `tmp/memory/history.jsonl`（每行 `{"ts","user","assistant","source"}`，上限 200 轮，越限原子裁剪） |
| L5 | 全量对话向量归档 | PG 表 `chat_memory_store`（metadata 只剩 `source` + `timestamp`） |

- **三段式"全量加载"**（对标 Claude Code 的单会话连续模型：`--continue` 从 transcript 恢复 + 压缩=摘要+保留近场）：真·逐字全量回放每轮重新付费且必然越线触发压缩，落地为 **近场 N 轮原文（L4.5）+ 全程滚动摘要（L3）+ 全库检索兜底（L5）**
- **恢复的算术硬约束**：`BaseAgent.initializeMemoryLayers` 的消息顺序是 `[记忆 SM] → [2N 条历史] → [本轮用户消息]`，**恢复严格 2N 条、不许加任何包装 Head**。默认 `restore-turns=6` ⇒ `2*6+3=15 = ToolCallAgent.MAX_CONTEXT_MESSAGES`，而 legacy 压缩触发条件是**严格大于** 15 ⇒ 纯聊天（doTerminate 单步）与单步任务**不因恢复触发压缩**；N=7 则首查 16 必触发。`BaseAgentMemoryRestoreTest` 用真常量钉死这个平衡，改任一方必须让它跟着改
- **注入**：`MemoryAssembler.buildPromptSection()` 拼成 `[Session Metadata]` / `[User Profile]` / `[Recent Summary]` 文本；Agent 链路作为首条 SystemMessage，LoveApp 拼进 system prompt。整块有 `yuai.context.memory-inject-max-chars`（默认 1200）上限——常驻的东西刻意做小；`loadHistoryTurns()` 供恢复用，每条按 `max-chars-per-message`（默认 1200）截断补「…（历史已截断）」
- **写入双通道**：①画像/摘要/归档走 `MemoryWriteQueue` **异步**（finally 里投递；`yuai.memory.async.enabled=false` 回同步），每次交互 2 次 LLM 调用（抽画像 + 摘要）+ 1 次 embedding，全程 fail-open；②**历史日志 `appendHistory` 是同步的**——它与异步队列写同一个文件会形成双通道竞态（且 `AtomicJsonWriter` 的单写者假设会被打破），所以 `FileConversationHistoryRepository` 的 append/裁剪切在**同一个临界区锁**里。画像字段有白名单/长度上限/notableFacts ≤20，归档文本按 1800 token 截断（防 DashScope 400）
- **失败轮不落历史**：`run`/`runStream` 的 finally 里**先快照 `state == ERROR`**（cleanup 会把 ERROR 复位 IDLE，判定必须前移）再决定是否 `appendHistory`——把「出错了：…」回放进下一轮上下文只会污染对话；画像/摘要照写（与改造前一致）
- **L5 归档文本 = 用户原话 + 给用户的最终答案**（不是 run() 返回的步骤轨迹）：`runStream` 用 `buildFinalSummary` 的结果、`run()` 走 `extractFinalAnswer()`，赋值一律在 `cleanup()` 之前。**不要再给 `run()` 加"合成最终答案"的 LLM 调用**——多智能体每请求会 +3 次
- **检索**：入口是常驻元工具 `searchMemory` → `ConversationMemoryService`（QueryRewriter 重写 → **无过滤整库检索** → topK 3 / threshold 0.1）。无过滤是单用户单会话语义的必然结果：恋爱大师与 agent 检索同一库、互相可见。**2026-10-11 已把 `chat_memory_store` 清空（135 行旧档 + 评测噪音，`truncate`）**——旧档带 chatId metadata 但这版是无过滤检索，留着就会被召回；`vector_store`（知识库）/`tool_vector_store`（工具路由）不是记忆，未动。记忆阈值**必须比知识库宽松**，别照搬 0.45
- **子 Agent 记忆全关**：一个开关 `memoryWriteEnabled`（默认 true）同时管「恢复 + 写入」，`YuManusMultiAgent` 三处子代理构造设 false。单键化后没有"子会话文件"可隔离——子 Agent 手里是被包装过的子任务指令（不是用户的话语），照写就是污染唯一那份画像/摘要/历史；`SearchMemoryTool` 构造器只剩 1 个参数（`ConversationMemoryService`），不再有会话 ID `Supplier`
- **四处「首条 UserMessage = 本轮任务」的假设已全部改为 `BaseAgent.currentUserPrompt`**（run/runStream 入口赋值）：`synthesizeFinalAnswer`、`ToolCallAgent.verifyTaskCompletion`、`ContextWindowManager` 的 originalTask、`YuManusMultiAgent.getOriginalUserGoal`——恢复注入历史 UserMessage 后，再去 messageList 里"找第一条 UserMessage"抓到的会是历史消息

### 上下文 / 记忆的配置开关与回滚（2026-10 重构新增）

| 键 | 默认 | 作用 / 回滚 |
| --- | --- | --- |
| `yuai.context.mode` | `legacy` | `legacy`=旧的按条数压缩；`budget`=token 预算两级压缩。**回滚就是改回 legacy**，两种模式代码共存 |
| `yuai.context.*` | 见 application.yml | 窗口 32768 / 常驻开销 2200 / 微压缩 35% / 压缩 50% / 保留 6 条 / 重注入文件数 5 / 单 run 最多压 3 次 / 两次压缩间隔 ≥2 步 / 记忆块 ≤1200 字符 |
| `yuai.context.transcript-enabled` | true | 压缩前落盘 `tmp/transcripts/`（每会话留 5 份）。关掉它 = 压缩不可恢复且无审计 |
| `yuai.memory.async.enabled` | true | 记忆写入异步化；**false = 同步执行**（行为与改造前一致，调试/回滚用） |
| `yuai.memory.dir` | `${user.dir}/tmp/memory` | 记忆根目录（画像/摘要/历史三件都在它下面）。**测试隔离的唯一手段**：单键化后无法再用随机 userId 换目录，评测类一律 `@DynamicPropertySource` 指到 `target/eval-memory/` |
| `yuai.memory.history.restore-turns` | 6 | 每次 run 起始恢复的近场轮数；**0=不恢复**。默认 6 与 legacy 压缩阈值 15 的算术对齐（见「分层记忆系统」），改前先看 `BaseAgentMemoryRestoreTest`。评测类固定设 0 保持单一变量 |
| `yuai.memory.history.max-turns` | 200 | `history.jsonl` 容量上限（轮），越限原子裁剪 |
| `yuai.memory.history.max-chars-per-message` | 1200 | 恢复时单条消息字符上限（超长回答会被每次 run 重新灌进上下文，每步 LLM 调用重复付费） |
| `yuai.memory.history.enabled` | true | 历史日志总开关：false 时不写也不恢复 |
| `yuai.stream.enabled` | true | **逐 token 真流式开关**（2026-10 流式轮）：true = think 叙述 + 最终答案走 `.stream()` 逐字推 `delta` 事件；false = 完全走改造前的阻塞 `.call()` 路径（两套代码共存）。**回滚就是改 false**，实测零 delta 帧、行为与改造前一致。只影响 `runStream`（sink 只有它安装），`run()`/子 Agent/评测零行为变化 |
| `yuai.stream.include-usage` | true | 流式请求是否带 `stream_options.include_usage`（**必须跟 `stream:true` 绑定，不能常开**：Spring AI 在非流式请求上会剥掉它并打 WARN）。关掉它 = 流式下 token 全部采不到，而埋点会把"测不到"记为缺失而不是 0 |
| `spring.autoconfigure.exclude` | `DashScopeImageAutoConfiguration` | 必须排除：它的构造器会改写**共享的** RetryTemplate 成 10 次 ×15s 固定退避（全仓无 ImageModel 注入，排除零损失） |
| `spring.ai.retry` | 3 次 / 1s 起步 ×2 | 与上一条配套；只对瞬时错误重试，确定性 400 不再重试 10 次 |

- 新包 `context/`（4 类）与 `metrics/`（2 类）是上下文重构轮新增；**`stream/`（4 类：`StreamingProperties`/`StreamingChatAggregator`/`StreamDeltaSink`/`StreamDisconnectedException`）是流式轮新增**。`AgentRunMetrics` 每 run 一行 `AGENT_METRICS {json}`，`BaseAgent.getLastRunMetrics()` 供评测读最近一次快照；流式轮新增两个字段 `firstVisibleMs`（run 起点 → 第一个送达的 delta，**-1 = 没测到**）与 `deltaFrames`（**成功送达**的增量帧数，发失败不计数）
- 记忆写入统计：`MemoryWriteQueue.stats()` → `submitted/completed/failed/rejected/timedOut/pending`，关停时若投递过会打一行汇总

### 多智能体协调器（YuManusMultiAgent）

- 直接继承 BaseAgent：**自己没有工具**，也不继承 think/act/路由/熔断那套；只做「规划 → 分发 → 汇总」
- `step()` 三阶段：首次 step 用**一次** LLM 生成 JSON 计划（1~3 步，agentType ∈ SEARCH/DOCUMENT/GENERAL）；之后每次 step 从队列取一个子任务**串行**执行；队列空则合成最终答案并 FINISHED
- 规划失败/空计划 → `buildFallbackPlan` 关键词兜底；子任务失败只记 success=false 继续跑，**不重试、不重新规划**
- 子 Agent 每个子任务 new 一个，`conversationId = 父ID:类型小写`，用 `run()` 同步执行（子 Agent 不推 SSE，只有协调器逐步推）；GENERAL 类型走完整 YuManus
- 协调器 maxSteps 8，而「规划 1 + 最多 3 步 + 合成 1」够用；但计划步数一多会在队列耗尽前被截断，走不到合成阶段
- 最终答案被合成**两次**：协调器自己合成一次，`BaseAgent.buildFinalSummary` 又对执行轨迹合成一次（SSE `final` 事件用的是后者）

### SSE 事件协议（跨前后端契约）

`BaseAgent.runStream` 依次推四种具名事件，data 是手写拼接的 JSON 字符串：`session`（`{conversationId}`，服务端生成 UUID 回传，前端存 localStorage；**2026-10-11 起不再有 userId**）、`process`（每步 `{type, step, tool, preview, content}`）、**`delta`（`{type:"delta", scope:"think"|"final", step, text}`，2026-10 流式轮新增：模型文本逐 token 增量）**、`final`（`{type, content}`，**最终答案的唯一权威**——`delta` 只是流式期间的临时渲染，前端收到 `final` 用其内容覆盖）。前端 `he-ai-agent-frontend/src/services/chatApi.js` 用 `addEventListener("process"/"delta"/"final")` 按事件名监听（并保留 `onmessage` 兜底：有回调时 `extractContent` 解析 `{content}`；`final` 有 `onFinal` 回调时不再走 onMessage 追加路径），yu 工程在 `api/index.js` 的 `connectSSE` 同形。**改事件名或字段要两端一起改**。SseEmitter 超时 5 分钟，另有每 15s 一帧的 `: keep-alive` **注释帧**保活——注释不触发任何前端事件（既不是 `message` 也不是具名事件），别把它当业务数据。

### RAG 的分工（重要约定）

- **Agent（YuManus）链路不接 RAG**：`ToolCallAgent` 里有完整的动态 RAG 机制（`shouldTriggerRagRetrieval` + `retrieveRagContext`），但构造器不再接收 VectorStore——**刻意回滚过**（曾接线、后按需求移除），`ragVectorStore` 恒为 null、不检索。不要重新接线。
- **恋爱大师（LoveApp）链路有 RAG**：`doChatByStream(message, chatId, useRag)` 默认开启，QueryRewriter 重写 + `LoveAppRagCustomAdvisorFactory` 检索知识库。status 参数传 null 不过滤——**传非空值会加 `status == xxx` 过滤条件，导致检索全空**。
- **容器里有 3 个 VectorStore Bean**，注入必须 `@Qualifier`/`@Resource(name=...)`，否则报 "required a single bean, but 2 were found"：
  1. `loveAppRagVectorStore` —— 知识库。别名是稳定的：`yuai.rag.store-type=memory`（默认）时是 `SimpleVectorStore`（Bean 名 `loveAppVectorStore`），`=pgvector` 时是 `PgVectorStore`（Bean 名 `pgVectorVectorStore`），两者都额外注册 `loveAppRagVectorStore` 别名，所以按别名注入与模式无关
  2. `toolRouterVectorStore` —— 工具路由（表 `tool_vector_store`）
  3. `chatMemoryVectorStore` —— 对话记忆（表 `chat_memory_store`）
- **本地知识库是 pgvector 且默认是空表**：application-local.yml 里是 `yuai.rag.store-type: pgvector`（**不是 memory**，`:19`）指向本地 `localhost:5432/yu_ai_agent`，且 `bootstrap-enabled: false` → 不启动灌库，`vector_store` 表没数据时 RAG 检索必然空手而归，这不是 bug（要灌库就临时把 `bootstrap-enabled` 改 true 跑一次）
- 灌库用**纯文本 loader**（`LoveAppDocumentLoader` 读原文按 `####` 自切分），不要改回 MarkdownDocumentReader——它会剥链接 URL、吞标题（有 LoaderCompletenessTest 回归验证）。PgVector 灌库前先清空旧数据，且按 20 条一批 add（DashScope embedding 单次上限 25）

### 评测体系

- `ToolSelectionAccuracyTest`：100 场景端到端，`EVAL_CASE_COUNT` 采样 / `EVAL_ROUNDS` 多数投票 / `EVAL_CATEGORY` 类别过滤；判定解析 act() 拼接的"工具 xxx 返回的结果"，write/pdf 类另做产物验证。已知局限：工具路径判据会误杀 shell 重定向等替代方案（靠产物验证兜底）
- `LayeredMemoryEvaluationTest` / `MemoryUpdateServiceTest`：需 `RUN_MEMORY_EVAL=true`；断言画像抽取/覆盖、跨会话共享（单会话语义）、三段 prompt 区块存在、超长归档不再重试风暴。2026-10-11 单用户单会话轮已按新契约重写（**删除 EVAL_USER_ID，`@DynamicPropertySource` 把 `yuai.memory.dir` 指到 `target/eval-memory/<用例>-<uuid>`**——单键化后换根目录是唯一隔离手段，也防评测语料污染真实 `tmp/memory`），2026-10-11 实测 6 用例全绿（超长归档 1526ms，修复前 143~146s）
- `BaseAgentMemoryRestoreTest` / `FileConversationHistoryRepositoryTest` / `MemoryUpdateServiceHistoryTest`（离线，随 `@Tag("unit")` 一起跑）：钉死恢复顺序与算术、历史文件的顺序/裁剪/坏行容错/并发不丢行、`appendHistory` 的 fail-open 与开关
- `ToolLatencyBenchmarkTest`：各工具**真实调用**延迟 p50/p95/max + 返回体积（未标 `@Tag("unit")`，需网络与 `TAVILY_API_KEY`，显式 `-Dtest=ToolLatencyBenchmarkTest` 跑；运行命令见类注释）。2026-10-10 基线（Mac/JDK21）：**本地工具全部 ≤43ms**（readFile 分页 1ms、generatePDF 9ms、terminal 4ms、writeFile/doTerminate <1ms），联网工具 searchWeb(Tavily) p50 1.9s、scrapeWebPage 0.6s、downloadResource 0.45s。结论：**端到端延迟由 LLM 轮次决定，工具本身不是瓶颈**；工具侧最坏情况有界（外层 30s + 各工具自身 10/15/20/30s 超时）。searchWeb 单次返回 ~6.5KB（5 条结果）是最大结果面
- `StreamingLatencyAbEvalTest`（2026-10 流式轮）：需 `RUN_STREAM_EVAL=true`，阻塞 vs 逐 token A/B（`STREAM_EVAL_CASES` 默认 3，最多 4 个任务：纯对话 / 读文件 / 写文件 / 读两文件对比，两种模式各跑一遍 ≈ 40 次真实 LLM 调用、约 1 分钟）。两组都走 `runStream`（唯一安装增量出口的入口 = 真实前端路径）、记忆恢复设 0、**唯一变量是 `yuai.stream.enabled`**。指标取自 `BaseAgent.getLastRunMetrics()`，报告写 `tmp/metrics/streaming-ab-<时间戳>.md` 且自带判定。硬断言三条：①成功率不降 + 产物落盘 100%（不变量）②`avgFirstVisible(on) < avgFirstVisible(off)`（**流式的定义性质**）③`ΣdeltaFrames > 0` 且 `ΣusageMissingCalls == 0`（流式真在送 + token 没采丢）。**2026-10-11 两轮实测**：首字 4305 → **937ms（提前 78%，3 用例）**、4096 → **826ms（提前 80%，4 用例）**，纯对话单格 11351 → 847ms / 13597 → 741ms，帧数 537 / 1084，两轮 usageMissing 均 0、成功率与产物 100%。**token 与端到端的差异两轮方向相反**（token +25% 然后 −13%；端到端 +16% 然后 −26%）——这正是「由轨迹随机性主导、与流式无关」的直接证据，**这两项不作结论**；流式的收益只在首字时间上，两轮一致。数字见 docs/streaming-refactor.md §6.2
- `ContextCompactionAbEvalTest`（2026-10 上下文重构 · 阶段 4）：需 `RUN_CONTEXT_EVAL=true`，压缩策略 A/B（legacy vs budget），`CONTEXT_EVAL_CASES` 默认 5（每例一个多步任务 + 产物验证，两种模式各跑一遍 ≈ 50~60 次真实 LLM 调用、约 4 分钟）。窗口用 `CONTEXT_EVAL_WINDOW_TOKENS` 调，默认 12000（**不是 32K**：默认窗口下这批任务到不了任何触发线，A/B 会退化成空跑）；再跑一轮 `=6000` 是越界压力轮。指标取自 `BaseAgent.getLastRunMetrics()`（volatile 快照字段），报告写 `tmp/metrics/context-ab-<时间戳>.md`，**报告末尾自带三条判定结论**（`judge()` 算的，达标与否都写出来）。**判定是决策记录、不是断言**：硬断言只有"成功率不降 + 产物落盘 100%"两条不变量，因为压力轮的预期就是 budget 更贵（2026-10-10 6K 轮实测过）。判定器有一条易踩的坑已修：`显著下降` 必须写成 `candidate < baseline && candidate <= baseline * 0.9`——两组都是 0 时 `0 <= 0` 恒真，会把"没测出差异"记成达标（空真陷阱）。数字与结论见 docs/context-memory-refactor.md §5.2

## 已知问题（改动前注意）

- **`./mvnw test` 在干净构建下必然失败**（14 个 `@SpringBootTest` 要 PG + 真实 key，且其中 4 个会打真实 LLM/外网，详见文首）——只跑指定的离线子集
- 死代码：`PDFGenerationToolPro`（未注册）、`ToolRegistration.executionTools`（无人引用）、`chatmemory/FileBasedChatMemory`（被 Kryo 版取代）、`ToolCallAgent.setToolVectorRouter`。工具总数按「8 内置 + searchMemory + MCP」算（full 模式常驻面）；dynamic 模式再加 searchTools/listTools 两个元工具。别再按 11/12 数。**`AiController.chatModel` 不是死代码**：它是 `:151`/`:174` 构造 Agent 时的实参
- **`YuManusMultiAgent` 的失败语义**（未修）：`BaseAgent.run` 把异常吞成 `"出错了：..."` 字符串返回，所以 `executeSubTask` 的 `catch` 几乎不触发，子任务 `success` 恒为 true；最终答案还会被合成两次（协调器一次 + `buildFinalSummary` 一次，SSE `final` 用的是后者）。要动就得改 `run()` 的返回契约，牵动所有调用方
- 教学素材别清理：44 处 TODO 与成段的原理注释是课程内容

- `FileOperationTool`/`PDFGenerationTool` 的路径解析必须走 `ToolPathUtils.resolveFilePath`（绝对路径按原样使用，相对路径拼 `${user.dir}/tmp` 沙箱）——曾有绝对路径被重复拼接的 bug
- `LoveApp` 里 `conversationMemoryService` 的非空判断已失效（字段是必填注入）；`MemoryUpdateService` 中传 relationshipStatus 必须过白名单，否则回落 LLM 抽取（曾误传 "stream_rag" 导致画像永远为空）

### 本轮已修复（避免后来者照旧描述去找 bug）

- **路由关键词子串误命中**：新增 `toolrouter/KeywordMatcher`，英文走词边界（`(?<![a-z0-9])kw s?(?![a-z0-9])`），中文/含符号仍走子串；`KeywordMatcherTest` 7 例回归。顺带确认代码里**从来没有** `"read"` 这个关键词，旧文档说的 `"read" ⊂ "already"` 并不存在
- **终端工具挂死**：`TerminalOperationTool` 改为 stderr 合并进 stdout（`redirectErrorStream`）、`waitFor(30s)` 超时后 `destroyForcibly`（连子进程一起杀）、截断后继续排空管道、恢复中断标记；`TerminalOperationToolTest` 6 例（含 stderr 洪泛与超时用例）。旧实现在 stderr 灌满管道时会**永久挂起**（已用 JEP-330 单文件程序实测复现）
- **SSE 生命周期**：`state` 改 `volatile`；执行循环条件由 `!= FINISHED` 改为 `== RUNNING`（否则超时回调把状态改回 IDLE 后循环照样成立，连接已断还会继续跑含 LLM 调用与工具副作用的步骤）；worker 从 `CompletableFuture.runAsync` 换成显式线程池 + `Future`（`cancel(true)` 才能真正中断，`CompletableFuture.cancel` 对已执行的 lambda 无效）；补 `onError` 回调；finally 里先 `cleanup()` 再写记忆、且记忆写入自己吞异常（原先记忆写入抛异常会跳过 cleanup，状态机永远停在 RUNNING）；`BaseAgentLifecycleTest` 4 例回归
- **he 前端不渲染正文**：`chatApi.js` 补 `process`/`final` 具名监听 + `onmessage` 兜底
- **3 个测试类签名过时**导致的 test 编译失败（见文首）

### 工具重构轮（2026-10-10：全量加载 + 描述重写 + 护栏）

- **暴露模式改全量加载**（见「工具暴露模式」节）：`ToolRoutingProperties.exposureMode` 默认 `full`；full 下 `ToolCallAgent` 构造器只注册 searchMemory，`selectToolsForCurrentStep` 直接返回全量（原路由整体挪进 `routeToolsForCurrentStep`），`ToolVectorRouter.init()` 不再构建索引（也就摆脱了 tool_router_meta / tool_vector_store 的启动依赖）
- **描述重写**（readFile/writeFile/searchWeb/scrapeWebPage/downloadResource/generatePDF）：写明沙箱路径、分页/截断、协议与大小限制、能力边界；教学 TODO 全部保留，改动的描述旁加了"重构轮"对照注释；`schema-version` 2→3
- **新护栏**：readFile 支持 offset/limit（默认 500 行 / 硬上限 2000 行 / 32KB 字符上限，超限输出翻页提示，越界 offset 返回带指引的 Error）；scrapeWebPage 显式 10s 超时 + 浏览器 UA；downloadResource 仅 http/https、30s 超时、20MB 上限（超限删除并报错）、文件名改走 `ToolPathUtils`
- **测试**：新增 `ToolGuardrailTest` 7 例（离线）；`ToolSearchToolUnitTest` 显式传 `dynamic`（该测试直接调 `router.init()`，full 下会旁路早退）；`ResourceDownloadToolTest`/`FileOperationToolTest` 跟进新签名

### 上下文/记忆重构轮（2026-10-10 · 阶段 0~4，方案与数字见 docs/context-memory-refactor.md）

- **嵌入事故收口**：`application.yml` 排除 `DashScopeImageAutoConfiguration`（它构造时把**共享的** RetryTemplate 改成 10 次 ×15s）+ `spring.ai.retry` 收敛到 3 次 + `chatMemoryVectorStore` 用 `TokenCountBatchingStrategy(2048)` + 归档前按 1800 token 截断。实测：超长归档 **143~146 秒 → 0.78 秒**
- **原子写与坏数据隔离**：`memory/AtomicJsonWriter`（写 `.tmp` → `ATOMIC_MOVE`）；`FileUserProfileRepository`/`FileRecentSummaryRepository` 读失败改名 `.corrupt-<ts>` 并返回 null，不再让坏 JSON 打挂整轮；`initializeMemoryLayers()` 包 try/catch
- **L2 画像改按用户**（原按 conversationId 存 → 15 个会话 15 份空画像）；子 Agent `setProfileWriteEnabled(false)` 防污染
- **L5 归档语义修正 + 双键过滤**（见「分层记忆系统」节）
- **记忆写入异步化**：`memory/MemoryWriteQueue`（core1/max2/有界队列 64/丢弃计数/看门狗只观测不 interrupt/daemon 线程/`@PreDestroy` 限时排空）；`MemoryUpdateService` 的 `record*` 改投递，**调用方零改动**，LoveApp 6 个写入点顺带获得 fail-open
- **埋点**：`metrics/AgentRunMetrics` + `UsageMetricsAdvisor`（挂 4 个 Agent 的 ChatClient），每 run 一行 `AGENT_METRICS {json}`；`BaseAgent.getLastRunMetrics()` 供评测读快照
- **上下文预算模式**：新包 `context/` 四个类 + `ToolCallAgent` 分流 + 证据链环形缓冲解耦（见「工具暴露模式」节的 budget 条目）
- **`run()` 的最终答案归档**：`BaseAgent` 新增 `extractFinalAnswerForMemory()`，`runStream` 先留档再发 `final` 事件；赋值必须在 `cleanup()` 之前
- **legacy 压缩路径补记账**：`optimizeContextWindowIfNeeded` 也调 `recordCompaction(false)`——不记账的话"budget 模式压缩次数更少"就成了无法证伪的说法
- **渲染器两条路径共用（P3/P5 收尾）**：`ContextWindowManager.renderForSummary()` 改 public，legacy 的 `summarizeMessages` 也调它（默认路径不能留着"把工具输出渲染成空串"的已知 bug），失败回落同步改成保留**最近**的 1200 字符。**注意这改变了"legacy 一行未改"的表述**：触发与重建逻辑仍未改，只有摘要渲染与回落方向修了。修完 A/B 两组才只差"触发策略"一个变量
- 回归：`./mvnw -Dgroups=unit test` **113 例全绿**（新增 context 包 28 例、`MemoryWriteQueueTest` 10 例、`ToolCallAgentEvidenceTest` 8 例、埋点 15 例、内存仓储原子写 8 例）

### 单用户单会话收敛轮（2026-10-11，方案见 docs/context-memory-refactor.md §9）

- **删掉全部记忆寻址键**：userId 从 HTTP 参数 / SSE 事件 / 指标 / 记忆层 / 前端 localStorage 一并移除（无登录体系时恒为 `"default"`，是死复杂度）；`conversationId` 降级为纯日志追踪 + 前端会话 id，**不参与记忆寻址**
- **工件收敛为固定三件**：`tmp/memory/profile.json` + `summary.json` + `history.jsonl`（不再随会话数增长）；LoveApp 的 Kryo 窗口固定挂 `single` key（`tmp/chat-memory/single_memory.bin`）
- **新增 L4.5 近场恢复**：`FileConversationHistoryRepository`（append/loadRecentTurns/裁剪同一临界区单锁）+ `MemoryAssembler.loadHistoryTurns()` + `BaseAgent.initializeMemoryLayers` 注入 2N 条原文；`ConversationTurn` 只存「用户原话 + 最终答案」
- **`MemoryUpdateService`**：`record*` 全部去掉 key 参数（4 参），新增同步 `appendHistory`（fail-open）；`ConversationMemoryService.saveExchange(user, assistant, source)` 去 chatId/userId metadata、检索改无过滤；`SearchMemoryTool` 构造 3 参 → 1 参
- **`profileWriteEnabled` → `memoryWriteEnabled`**：一个开关同时管「恢复 + 写入」，子 Agent 三处设 false（顺手修了一个真 bug：原实现只跳过画像写入，子 Agent 的摘要/归档仍会写进唯一那份工件）
- **新增 `MemoryStoreProperties` + `MemoryStoreConfig`**：三个 `File*Repository` 从 `@Repository` 改 `@Bean` 装配，目录走 `yuai.memory.dir`（测试隔离入口）
- **前端两工程同步删 userId**（he：`ChatPage.vue`/`chatApi.js`/两个 View；yu：`api/index.js`/`SuperAgent.vue`/`MultiAgent.vue`），两工程 `npm run build` 均绿
- **近场恢复的可观测性与实测成本**：`initializeMemoryLayers` 恢复后打一行 `近场恢复 N 轮历史`（恢复本身是"看不见的行为"，文件坏/轮数为 0/路径不对时不复现就没法定位）。成本实测（`restore-turns=0` vs 默认 6，比对首轮 LLM 请求体）：**2003 → 3217 字符，净增 1214 字符 = 恰好 12 条消息（6 轮×2）≈ +320 token**，远低于设计上界（12 × ≤1200 字符）
- 回归：`./mvnw -Dgroups=unit test` **127 例全绿**（新增 `FileConversationHistoryRepositoryTest` 5 例、`BaseAgentMemoryRestoreTest` 5 例、`MemoryUpdateServiceHistoryTest` 3 例、`ContextWindowManagerTest` 的 originalTask 定位 1 例）；`RUN_MEMORY_EVAL=true` 记忆评测 **6 例全绿**（超长归档 1526ms）

### 流式轮（2026-10-11：整步伪流式 → 逐 token 真流式，方案与数字见 docs/streaming-refactor.md）

- **根因不是通道而是调用方式**：SSE 三种事件早就有，但每个 LLM 调用都是阻塞 `.call()`，`process` 是「整步跑完」的拼接、think 文本只进日志 → 用户看到「长空白 + 整段砸下来」
- **新包 `stream/` 4 类**：`StreamingProperties` / `StreamingChatAggregator`（自建聚合器——框架 `MessageAggregator` 1.0.0 **不聚合 toolCalls**，而 `act()` 依赖完整工具调用；好在 `OpenAiApi.chatCompletionStream` 已用 `windowUntil + merge` 把工具分片压成**一整块完整 chunk**，聚合器只需捕获不必拼接）/ `StreamDeltaSink` / `StreamDisconnectedException`
- **边界**：`isTextStreamingActive()` = `enabled && sink != null`，**sink 只有 `runStream` 安装** → `run()`（评测、多智能体子 Agent）天然保持阻塞。只流「给人看的文本」（think 叙述 + 最终答案）；`verifyTaskCompletion`、压缩摘要、多智能体规划保持阻塞——是给机器看的 JSON
- **断连语义**：流式把发送频率从「每步一次」放大到「每 token 一次」，异常若被 `think()` 的通用 catch 吞掉，**循环会在无人接收时继续烧 token** → `emitTextDelta` 捕获发送失败后三步一起做：清 sink（后续内部调用回落阻塞）→ `state = ERROR`（循环条件 `== RUNNING` 立刻失效）→ 抛 `StreamDisconnectedException`（`think()`/`ReActAgent.step()` 的 catch 顺序在通用 catch **之前**单独放行）
- **真 bug（离线单测抓到）**：聚合器按「后一块元数据覆盖前一块」会让一块紧跟其后的普通 chunk 把 usage 擦成 0（流式下每块都自带 `EmptyUsage`）→ 改为框架同款「逐字段取最近一次 >0」；另：`UsageMetricsAdvisor` 把 `DefaultUsage(0,0,0)`（没带 `include_usage` 时的形态）判为**缺失**，否则流式下 token 采集静默退化成 0，而 0 在埋点里恰好表示"没测到"→ A/B 会得出"流式很省 token"的假结论
- **构造器链新增 `StreamingProperties`**（沿 ToolRoutingProperties 纪律）：`BaseAgent` 因此有了**显式构造器** → **Lombok `@Data` 不再生成无参构造器**，所有 `StubAgent extends BaseAgent` 的测试桩必须写 `super(null)`（`BaseAgentLifecycleTest`/`BaseAgentMemoryRestoreTest`/`BaseAgentTextDeltaTest`）
- **前端两工程**：he 的 `chatApi.js` 增 `onDelta`/`onFinal`，`ChatPage.vue` 三路渲染（think 增量追加当前气泡 / **首个 final 增量强制新开一条气泡**并记住 id / `final` 到达按 id 覆盖落定）+ 闪烁光标；yu 的 `api/index.js` 增 `onDelta`，`SuperAgent.vue`（think 增量进「实时思考」行、下一条 process 到达即并入步骤列表）+ `MultiAgent.vue`（接 final 增量）
- **回归**：`./mvnw -q -Dgroups=unit test` **142 例全绿**（127 → 142：新增 `StreamingChatAggregatorTest` 7 例、`BaseAgentTextDeltaTest` 6 例、`UsageMetricsAdvisorTest` 的 0-usage 1 例、`AgentRunMetricsTest` 的流式延迟 1 例）；冒烟实测见 docs/streaming-refactor.md §6.1

## 代码规范

- 注释、日志、文档一律中文；成段原理注释与 `// TODO` 是教学素材，只增不删
- 新增业务工具：`@Tool` + `@ToolParam`，**工具名 = 方法名，不写 `name` 属性**；元工具相反——必须显式 `@Tool(name = ...)` 且不注册 Bean、不进索引
- 增删工具或改工具描述后，把 `yuai.tool-router.schema-version` 递增（dynamic 模式启动时据此清空重建索引；full 模式不建索引，递增是为切回 dynamic 时能正确重建）
- 工具 description 是模型唯一的接口文档：写清前置条件、参数语义、返回结构与限制（截断/超时/大小上限），别只写一句功能名；能力边界（做不到什么）也要写，减少误选
- 文件路径统一走 `ToolPathUtils.resolveFilePath`；资源释放/进程管理参考 `TerminalOperationTool`（必须有超时与销毁）
- 依赖注入：Controller 用 `@Resource` 字段取依赖再手动 `new` Agent；`ToolVectorRouter` / `ToolRoutingProperties` 只走构造器链，**不加 setter、不加 @Autowired**
- 同类型多 Bean（3 个 VectorStore）必须 `@Qualifier` / `@Resource(name=...)`，否则报 "required a single bean"
- 新增英文路由关键词必须走 `KeywordMatcher`，不要裸 `String.contains`
- 两套阈值不要互相抄：工具路由 0.45 / topK 2、记忆检索 0.1 / topK 3，调优方向相反
- **`ContextWindowManager` / `TranscriptWriter` / `ContextProperties` / `StreamingProperties` 与 `ToolVectorRouter` 同级**：只走构造器链，不加 setter/@Autowired。Agent 构造器新增参数时同步改 `AiController` 与 `ToolSelectionAccuracyTest`/`ContextCompactionAbEvalTest`/`ToolSchemaBudgetTest`/`StreamingLatencyAbEvalTest`
- **给 `BaseAgent` 流式分支加东西时先想「是不是给人看的文本」**：给人看的（think 叙述、最终答案）走 `emitTextDelta`；给机器看的（JSON 判定、摘要、规划）保持阻塞调用。往流式分支里塞机器调用 = 把「发送失败即停」的语义扩散到本不该受它影响的地方
- **`BaseAgent` 现在是显式构造器（`protected BaseAgent(StreamingProperties)`）**：Lombok `@Data` 一旦发现已有显式构造器就**不再生成无参构造器**，所以任何 `extends BaseAgent` 的测试桩必须显式 `super(null)`（不要指望 Lombok 补一个）
- **一切记忆写入只走 `MemoryUpdateService` 的 `record*` / `appendHistory`**，不要在别处直接调仓储——异步队列、fail-open、截断都在那一层，绕过去就绕过了全部保护。注意画像/摘要/归档走队列（异步）、history 是同步（双通道竞态），别把 appendHistory 也挪进队列
- **恢复历史的条数是硬约束**：改了 `restore-turns` 默认值、恢复条数（必须严格 2N、不加包装 Head）、或 `ToolCallAgent.MAX_CONTEXT_MESSAGES`，必须同步改 `BaseAgentMemoryRestoreTest` 的算术断言——三者失配的后果是「纯聊天也触发压缩」，而且不报错、只悄悄变贵
- **`BaseAgent.currentUserPrompt` 是「本轮任务」的唯一来源**：不要再从 messageList 里"找第一条 UserMessage"——恢复注入后那会是历史消息（四处历史 bug 的成因）
- 压缩相关的可调量一律进 `ContextProperties`（不许硬编码），因为 A/B 要能靠改配置造出对照组
- 渲染消息给 LLM 前先确认拿得到内容：`ToolResponseMessage.getText()` **恒为空串**，要读 `getResponses()`
- 改压缩逻辑时**两个分支都要看**：`legacy` 是回滚目标，`budget` 是目标形态；只改一个等于制造"回滚即回归"

## 禁区

- **绝不把真实密钥写进任何被跟踪的文件**（真值只放 `application-local.yml`，见下节）
- 不要把 RAG 重新接回 `YuManus`（`ToolCallAgent.ragVectorStore` 恒为 null 是刻意回滚的结果）
- 不要动 `ToolVectorRouter` 的 `0.45 / topK 2`（调优过的值；改回 0.2/5 会让 topK 每轮饱和、路由失效。仅 dynamic 模式生效）
- 不要清理教学 TODO / 原理注释；不要"顺手"整体格式化 `ToolCallAgent`（856 行）
- 不要给 Agent 加单例或共享状态：状态机与 `messageList` 非线程安全，Controller 每请求 new 一个
- 不要在没开对应环境变量开关时跑评测类（真实 LLM 计费，最长 40 分钟）
- **不要把 `yuai.context.mode` 的默认值改成 `budget`**，除非新一轮 A/B 达到方案里的判定标准（成功率不降 + token 或重复调用率显著下降 + 压缩次数不增加）。2026-10-10 那轮**没达到**，所以默认仍是 `legacy`，理由写在 docs/context-memory-refactor.md §5.2
- 不要在 `MemoryWriteQueue` 里加 `CallerRunsPolicy`、也不要改成 `interrupt` 超时任务：前者把 3~8 秒写入压回请求线程，后者会留下半写文件
- 不要给 `run()` 加"合成最终答案"的 LLM 调用（多智能体每请求 +3 次）；L5 归档用已有的最终答案
- **不要再引入 userId/conversationId 记忆键**（用户已明确「不搞多用户了，所有会话当单一会话看」）：记忆工件全局唯一一份，加回键就是加回 15 份空画像那类问题。`conversationId` 只准出现在日志/SSE/transcript 路径
- 子 Agent（`YuManusMultiAgent` 三处构造）必须 `setMemoryWriteEnabled(false)`：单键化后没有子会话文件可隔离，子任务指令写进去就是污染唯一那份工件
- **不要在 `run()`（阻塞链路）里安装增量出口或让流式影响到它**：sink 只有 `runStream` 安装，是「评测/子 Agent 零行为变化」的全部依据；给 `run()` 也接上 sink 等于让评测结果依赖终端渲染
- **不要给流式请求抛异常只 catch 一半**：`StreamDisconnectedException` 必须在 `think()`/`ReActAgent.step()` 的通用 `catch (Exception)` **之前**放行——被吞掉 = 断连后循环继续跑、继续烧 token（`BaseAgentTextDeltaTest.sendFailureAbortsStreamAndStops` 钉住了这条）

## 验证命令

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)

./mvnw -q -DskipTests compile      # 改完 main 代码
./mvnw -q test-compile             # 改完测试
./mvnw -q -Dtest='KeywordMatcherTest,BaseAgentLifecycleTest,BaseAgentMemoryRestoreTest,BaseAgentTextDeltaTest,TerminalOperationToolTest,ToolPathUtilsTest,ToolSearchToolUnitTest,ToolGuardrailTest,ToolSchemaBudgetTest,FileConversationHistoryRepositoryTest,MemoryUpdateServiceHistoryTest,StreamingChatAggregatorTest' test
bash scripts/check-secrets.sh      # 提交前；期望 0 命中
(cd he-ai-agent-frontend && npm run build) && (cd yu-ai-agent-frontend && npm run build)
# 冒烟（需本地 key + 已启动服务）：
curl -N -H 'Accept: text/event-stream' 'http://localhost:8123/api/ai/manus/chat?message=你好'
#   期望依次看到 event:session / 若干 event:delta(scope=think) / event:process / event:delta(scope=final) / event:final
#   以及每 15s 一帧 ": keep-alive"
# 流式回滚冒烟：加 --yuai.stream.enabled=false 启动，delta 帧应完全消失、行为与改造前一致
# 记忆恢复冒烟：同一句问话连问两轮，第二轮 AGENT_METRICS 的 promptTokens 应多出约「恢复内容」的 token 数；
#   且 ls tmp/memory/ 只有 profile.json / summary.json / history.jsonl

# A/B（真实 LLM，需显式开关，约 1 分钟）：
RUN_STREAM_EVAL=true STREAM_EVAL_CASES=4 ./mvnw test -Dtest=StreamingLatencyAbEvalTest
#   报告落 tmp/metrics/streaming-ab-<时间戳>.md，自带判定；主指标 AGENT_METRICS 的 firstVisibleMs / deltaFrames
```

前端两个项目都**没有** lint / format / test 脚本，Maven 侧也没有 checkstyle / spotless / CI——本项目目前零质量门禁，新增代码请自己按住上面这些约束。

## 密钥政策与事故记录

- **真值只允许出现在 `application-local.yml`**（未跟踪且被 `.gitignore` 忽略）；被跟踪的 `application.yml` / `application-prod.yml` / `application-local.example.yml` 一律用 `${ENV:默认值}` 占位符
- **密钥集中管理（2026-10 起）**：所有真实 key 只写 `application-local.yml` 的 `yuai.api-keys` 区块（现有 `deepseek` / `dashscope` / `tavily`），其余配置（含同一文件的下半段）一律写 `${yuai.api-keys.xxx}` 引用；新增第三方 key 时**同步给 `scripts/check-secrets.sh` 补前缀规则**（已有 `sk-` 与 `tvly-` 两条 + 打码规则 + RE_KV 键名表）——只加配置不加规则，守卫就拦不住这类 key
- 提交前必跑 `bash scripts/check-secrets.sh`（已通过 `git config core.hooksPath .githooks` 挂在 pre-commit 上；它同时扫描"未跟踪但未被忽略"的文件，防止新文件带着 key 溜进去）
- **2026-10 事故记录（已收口）**：提交 `3fdb175`（"更新了1.txt"）曾把 4 个真实凭据推进公开 fork——阿里云 RDS 口令（账号 `yu_ai_agent` @ 某实例）、DashScope API Key、SearchAPI Key、高德地图 API Key（`mcp-servers.json`）。最终处置：**RDS 实例长期停机**（连接不可达，口令等于失效；日后若重新开机，第一件事是改密并检查白名单）；**DashScope key 已作废重建**（旧 key 实测 `401 API-key is blocked`，新 key 实测嵌入返回 1536 维正常）；**SearchAPI key 已撤销**（2026-10-10 用户在 searchapi.io 后台撤销；search 工具已改用 **Tavily** 主链路）；**高德 key 所在配置整段删除**（若该 key 仍有效，去高德开放平台撤销）；**GitHub fork 已删除**、账号改名。**教训：删远端不会收回已泄露的 key，轮换/撤销才是把风险归零的动作；判断"有没有进过 git 历史"要用 `git log -S '< 片段>'`，不能只看当前文件**
- 推送前检查清单：`git log main --pickaxe-regex -S 'sk-[A-Za-z0-9_-]{20,}'` 无命中（只查新历史；守卫脚本自身的规则串不会自命中）、`bash scripts/check-secrets.sh` 通过、`git check-ignore application-local.yml` 有输出
- **仓库历史布局（提交轮 2026-10-10 重建）**：`main` = 全新历史（12 个提交：1 基线 + 10 主题 + 1 文档，明文凭据从未进入）；`master` / `develop` = 旧历史，仅本地对照，**永不推送**。远端：`origin` = `heyandong123/super-agent`（推送目标）、`upstream` = `liyupi/yu-ai-agent`（只读拉更新）
