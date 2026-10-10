# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

Spring AI 1.0 教学项目（AI 智能体平台）：ReAct 自主智能体（YuManus）、多智能体协调器（YuManusMultiAgent）、工具动态调用机制（元工具 / 语义路由 / 降级链 / 熔断）、分层记忆（画像 + 摘要 + 向量归档）、RAG（PGVector）、SSE 流式输出。代码注释为中文；教学性注释与 TODO 是刻意保留的教程素材，不要清理。

## 常用命令

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # 需要 JDK 21

./mvnw -DskipTests compile       # 编译主代码（干净构建约 1 分钟）
./mvnw spring-boot:run           # 启动：8123 端口，context-path /api，默认 local profile
./mvnw test -Dtest=ToolPathUtilsTest        # 跑单个测试类（逗号分隔可跑多个）
```

**⚠️ 绝对不要直接跑 `./mvnw test`**：24 个测试类里 13 个是 `@SpringBootTest`（要 Docker PG + application-local.yml 的真实 key），其中 **4 个无门控地打真实外部调用**——`ToolSelectionAccuracyTest`（100 场景、300~400 次 LLM 调用、35~40 分钟，现已加 `RUN_TOOL_EVAL` 门控）、`LoveAppTest`（3 轮真实对话）、`AiControllerTest`（1 轮真实对话，走 RAG）、以及联网的 `WebSearchToolTest`/`WebScrapingToolTest`。剩下 11 个非 Spring 类是可信的离线子集，其中 9 个标了 `@Tag("unit")`，一条命令跑完（约 4 秒，36 用例）：

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
```

同级的两个前端：`yu-ai-agent-frontend`（原版，带 Dockerfile/nginx）、`he-ai-agent-frontend`（本地开发版）；接口基址都硬编码 `http://localhost:8123/api`。

## 核心架构（跨文件才能看懂的部分）

### Agent 继承链与"手动 new"约定

`BaseAgent`（执行循环 + 状态机 + SSE + 记忆钩子）→ `ReActAgent`（think/act 拆分）→ `ToolCallAgent`（工具路由/降级/上下文压缩/熔断/重试/L2 缓存）→ `YuManus`（maxSteps 20）/ `SearchAgent`（3）/ `DocumentAgent`（6）；`YuManusMultiAgent` **直接继承 BaseAgent**（自己不碰工具，见下）。

**关键约定**：Controller 每次请求 `new` Agent，不注入单例（`YuManus` 的 @Component 是死代码，Agent 状态非线程安全）。`ToolVectorRouter` 和 `ToolRoutingProperties` 必须沿构造器链显式传递（AiController → YuManus/YuManusMultiAgent → 子 Agent），**不要用 setter 或 @Autowired 注入**——这是历史 bug 的修复方向。AiController 自身用 `@Resource` 字段取依赖，再手动 new Agent。

### 模型与嵌入（一个反直觉的坑）

- **所有 `ChatModel` 注入拿到的都是 `@Primary deepSeekChatModel`**，包括构造函数里参数名叫 `dashscopeChatModel` 的那些（YuManus / SearchAgent / DocumentAgent / LoveApp / QueryRewriter / MemoryUpdateService）。Spring 6.2 的多候选解析顺序是 `@Primary` → 参数名匹配 → `@Priority`（`DefaultListableBeanFactory#determineAutowireCandidate` 里 primary 命中即返回），所以**参数名是历史遗留的误导**：改参数名不会切到通义千问，要让 qwen-plus 生效必须挪走 @Primary 或改用 @Qualifier。
- 实际模型 id 来自 application.yml 的 `spring.ai.openai.chat.options.model: deepseek-v4-flash`；配置类里的 `deepseek-v4-pro` 只是属性缺失时的默认值，当前不生效。
- **嵌入走 DashScope `text-embedding-v1`（1536 维），不是 v2**：全仓库（含 git 历史）没有任何 `spring.ai.dashscope.embedding.options.model` 配置，走的是 `DashScopeEmbeddingProperties` 的默认常量（`spring-ai-alibaba-autoconfigure-dashscope` 1.0.0.2 里 `DEFAULT_EMBEDDING_MODEL = "text-embedding-v1"`）。要换模型必须显式配这个属性，且换完三张向量表（`vector_store`/`tool_vector_store`/`chat_memory_store`，均 1536 维）都得重灌——v1/v2 维度相同但向量空间不同，混用等于噪声。`MemoryVectorProperties` 里"与 dashscope text-embedding-v2 一致"的注释是错的。
- 嵌入模型 Bean 全容器只有 `dashscopeEmbeddingModel` 一个：Ollama 的 `ollamaEmbeddingModel` 带 `@ConditionalOnMissingBean` 会被顶掉（另一个坑是 DashScope 这个自动配置类在 1.0.0.2 里误条件在 `spring.ai.model.audio.speech` 上，靠 `matchIfMissing=true` 才默认生效），所以参数名 `dashscopeEmbeddingModel` 这次是名副其实的。

### 工具体系（四层来源）

1. **内置工具**：`tools/ToolRegistration.java` 的 `allTools` Bean = 8 个方法（readFile/writeFile/searchWeb/scrapeWebPage/downloadResource/executeTerminalCommand/generatePDF/doTerminate）。业务工具用 `@Tool`/`@ToolParam`，工具名 = 方法名（**不写 name 属性**）。
2. **命名工具数组**：同文件另有 `searchTools`/`documentTools`/`executionTools` 三个 `ToolCallback[]` Bean，给多智能体的子 Agent 分工具集用（`executionTools` 目前无人引用）。
3. **MCP 工具**：`mcp-servers.json` 装配出 ToolCallbackProvider，AiController 的 `mcpTools()` 运行时合并（当前仅 searchImage）。**不进向量索引**。注意该 json 里是 MCP 服务 jar 的**绝对路径**，换机器或未 `mvn package` 该子项目时 MCP 工具会静默消失。
4. **元工具**：`ToolSearchTool`/`ListToolsTool`/`SearchMemoryTool` 在 `ToolCallAgent` 构造器内 per-agent new、追加进 availableTools——**不注册 Bean、不进 allTools、不进索引**。这三个与业务工具相反，**显式写了 `@Tool(name = ...)`**。searchTools 持有 Agent 的 L2 集合直接引用写入，靠 `ToolCallbacks.from()` 包装（它返回包装对象，拿不回原实例，所以构造器里先存原实例引用）。

### 工具动态调用机制

- 每轮暴露集合 = L1 元工具（searchTools/listTools/searchMemory）∪ `fixed-tools` 配置 ∪ L2 会话缓存 ∪ 本轮路由结果（`selectToolsForCurrentStep`）
- 路由 = 关键词（`routeToolNames` 硬编码分组）+ 向量（`ToolVectorRouter`，表 `tool_vector_store`，threshold 0.45 / topK 2，**调优过的值，别改回 0.2/5**——那会让 topK 每轮饱和、路由失效）取并集；`schema-version` 递增会在启动时清空重建索引（改工具集时要 +1）
- 两条兜底：路由结果与 availableTools 完全不匹配 → 回退全量；只剩 doTerminate 时**不回退**（纯聊天场景本来就该只给 doTerminate）。全部工具被熔断时只放行 doTerminate
- 容错常量：单次工具调用 30s 超时、失败重试 2 次、同一工具连续失败 3 次熔断 60s；`chatOptions` 关闭自动执行与并行调用、maxTokens 1024
- 上下文压缩：消息数 > 15 时把前段用 LLM 摘要成一条 SystemMessage（保留最近 6 条）
- searchTools 降级链：语义（阈值常量硬编码 0.2，**与 yml 里给路由用的 0.45 不是一回事**）→ 阈值放宽（×0.5、topK×2）→ 关键词回退 → 结构化失败提示
- `buildRoutingContext` 排除 ToolResponseMessage 防关键词污染（RAG 检索共用）；路由关键词一律走 `KeywordMatcher.containsAnyWord`（英文按词边界），**别退回 `String.contains`**——`"file"` 会命中记忆注入的 `[User Profile]`、`"ls"` 会命中 `tools`/`results`
- `doTerminate` 带必填 `taskChecklist`；act() 终止前用 LLM 做三方比对（原始任务 + 自述清单 + 工具执行证据），遗漏则拒绝终止并反馈模型；校验调用本身失败时 fail-open
- SearchAgent 专属裁剪：`limitRedundantToolCalls` 去掉重复的 around_search / searchWeb，`isSearchAgentToolFailure` 遇 429 / "no search results" 直接置 FINISHED

### 分层记忆系统

5 层，全部落在 `${user.dir}/tmp/`（`FileConstant.FILE_SAVE_DIR`）与 PG 里：

| 层 | 内容 | 位置 |
| --- | --- | --- |
| L1 | 会话元数据（channel/locale/mode/requestType） | 每请求内存态，不落盘 |
| L2 | 用户画像（感情状态/昵称/回答风格/长期目标/notableFacts） | `tmp/memory/profile/{userId}/{conversationId}.json` |
| L3 | 近期摘要 | `tmp/memory/summary/{userId}/{conversationId}.json` |
| L4 | 短期窗口：Agent 用 `messageList` + 上下文压缩；LoveApp 用 Kryo 窗口记忆（20 条） | LoveApp 落 `tmp/chat-memory/{chatId}_memory.bin` |
| L5 | 全量对话向量归档 | PG 表 `chat_memory_store` |

- **复合键**：userId 做目录层、conversationId 做文件层（两段都做 `[^a-zA-Z0-9._-] → _` 清洗），空值回落 `default` / `anonymous`
- **注入**：`MemoryAssembler` 拼成 `[Session Metadata]` / `[User Profile]` / `[Recent Summary]` 文本；Agent 链路作为首条 SystemMessage（`BaseAgent.initializeMemoryLayers`），LoveApp 拼进 system prompt
- **写入**：`run`/`runStream` 的 finally 里**同步**写（没有 @Async），每次交互 2 次 LLM 调用（抽画像 + 摘要）；全程 fail-open（画像回落"无法判断"、摘要回落截断、向量写入失败只 warn）。画像字段有白名单/长度上限/notableFacts 去重，但**向量归档没有条数上限**
- **检索**：入口是常驻元工具 `searchMemory` → `ConversationMemoryService`（QueryRewriter 重写 → `chatId` metadata 过滤防串会话 → topK 3 / threshold 0.1）。记忆阈值**必须比知识库宽松**，别照搬 0.45
- conversationId 用 `Supplier` 延迟读取——工具在构造期创建，而会话 ID 是构造之后由 setter 设置的

### 多智能体协调器（YuManusMultiAgent）

- 直接继承 BaseAgent：**自己没有工具**，也不继承 think/act/路由/熔断那套；只做「规划 → 分发 → 汇总」
- `step()` 三阶段：首次 step 用**一次** LLM 生成 JSON 计划（1~3 步，agentType ∈ SEARCH/DOCUMENT/GENERAL）；之后每次 step 从队列取一个子任务**串行**执行；队列空则合成最终答案并 FINISHED
- 规划失败/空计划 → `buildFallbackPlan` 关键词兜底；子任务失败只记 success=false 继续跑，**不重试、不重新规划**
- 子 Agent 每个子任务 new 一个，`conversationId = 父ID:类型小写`，用 `run()` 同步执行（子 Agent 不推 SSE，只有协调器逐步推）；GENERAL 类型走完整 YuManus
- 协调器 maxSteps 8，而「规划 1 + 最多 3 步 + 合成 1」够用；但计划步数一多会在队列耗尽前被截断，走不到合成阶段
- 最终答案被合成**两次**：协调器自己合成一次，`BaseAgent.buildFinalSummary` 又对执行轨迹合成一次（SSE `final` 事件用的是后者）

### SSE 事件协议（跨前后端契约）

`BaseAgent.runStream` 依次推三种具名事件，data 是手写拼接的 JSON 字符串：`session`（`{conversationId, userId}`，服务端生成 UUID 回传，前端存 localStorage）、`process`（每步 `{type, step, tool, preview, content}`）、`final`（`{type, content}`）。前端 `he-ai-agent-frontend/src/services/chatApi.js` 用 `addEventListener("process"/"final")` 按事件名监听（并保留 `onmessage` 兜底：有回调时 `extractContent` 解析 `{content}`），**改事件名或字段要两端一起改**。SseEmitter 超时 5 分钟，另有每 15s 一帧的 `: keep-alive` **注释帧**保活——注释不触发任何前端事件（既不是 `message` 也不是具名事件），别把它当业务数据。

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
- `LayeredMemoryEvaluationTest`：需 `RUN_MEMORY_EVAL=true`；断言画像抽取/覆盖与三段 prompt 区块存在（当前签名过时，见开头警告）

## 已知问题（改动前注意）

- **`./mvnw test` 在干净构建下必然失败**（13 个 `@SpringBootTest` 要 PG + 真实 key，且其中 4 个会打真实 LLM/外网，详见文首）——只跑指定的离线子集
- 死代码：`PDFGenerationToolPro`（未注册）、`ToolRegistration.executionTools`（无人引用）、`chatmemory/FileBasedChatMemory`（被 Kryo 版取代）、`ToolCallAgent.setToolVectorRouter`。工具总数按"8 内置 + 3 元工具 + MCP"算，别再按 11/12 数。**`AiController.chatModel` 不是死代码**：它是 `:151`/`:174` 构造 Agent 时的实参
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

## 代码规范

- 注释、日志、文档一律中文；成段原理注释与 `// TODO` 是教学素材，只增不删
- 新增业务工具：`@Tool` + `@ToolParam`，**工具名 = 方法名，不写 `name` 属性**；元工具相反——必须显式 `@Tool(name = ...)` 且不注册 Bean、不进索引
- 增删工具或改工具描述后，必须把工具索引的 `schema-version` 递增（启动时据此清空重建），否则路由检索到的还是旧工具
- 文件路径统一走 `ToolPathUtils.resolveFilePath`；资源释放/进程管理参考 `TerminalOperationTool`（必须有超时与销毁）
- 依赖注入：Controller 用 `@Resource` 字段取依赖再手动 `new` Agent；`ToolVectorRouter` / `ToolRoutingProperties` 只走构造器链，**不加 setter、不加 @Autowired**
- 同类型多 Bean（3 个 VectorStore）必须 `@Qualifier` / `@Resource(name=...)`，否则报 "required a single bean"
- 新增英文路由关键词必须走 `KeywordMatcher`，不要裸 `String.contains`
- 两套阈值不要互相抄：工具路由 0.45 / topK 2、记忆检索 0.1 / topK 3，调优方向相反

## 禁区

- **绝不把真实密钥写进任何被跟踪的文件**（真值只放 `application-local.yml`，见下节）
- 不要把 RAG 重新接回 `YuManus`（`ToolCallAgent.ragVectorStore` 恒为 null 是刻意回滚的结果）
- 不要动 `ToolVectorRouter` 的 `0.45 / topK 2`（调优过的值；改回 0.2/5 会让 topK 每轮饱和、路由失效）
- 不要清理教学 TODO / 原理注释；不要"顺手"整体格式化 `ToolCallAgent`（856 行）
- 不要给 Agent 加单例或共享状态：状态机与 `messageList` 非线程安全，Controller 每请求 new 一个
- 不要在没开对应环境变量开关时跑评测类（真实 LLM 计费，最长 40 分钟）

## 验证命令

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)

./mvnw -q -DskipTests compile      # 改完 main 代码
./mvnw -q test-compile             # 改完测试
./mvnw -q -Dtest='KeywordMatcherTest,BaseAgentLifecycleTest,TerminalOperationToolTest,ToolPathUtilsTest,ToolSearchToolUnitTest' test
bash scripts/check-secrets.sh      # 提交前；期望 0 命中
(cd he-ai-agent-frontend && npm run build)
# 冒烟（需本地 key + 已启动服务）：
curl -N -H 'Accept: text/event-stream' 'http://localhost:8123/api/ai/manus/chat?message=你好'
#   期望依次看到 event:session / event:process / event:final，以及每 15s 一帧 ": keep-alive"
```

前端两个项目都**没有** lint / format / test 脚本，Maven 侧也没有 checkstyle / spotless / CI——本项目目前零质量门禁，新增代码请自己按住上面这些约束。

## 密钥政策与事故记录

- **真值只允许出现在 `application-local.yml`**（未跟踪且被 `.gitignore` 忽略）；被跟踪的 `application.yml` / `application-prod.yml` 一律用 `${ENV:默认值}` 占位符
- 提交前必跑 `bash scripts/check-secrets.sh`（已通过 `git config core.hooksPath .githooks` 挂在 pre-commit 上；它同时扫描"未跟踪但未被忽略"的文件，防止新文件带着 key 溜进去）
- **2026-10 事故记录（已收口）**：提交 `3fdb175`（"更新了1.txt"）曾把 4 个真实凭据推进公开 fork——阿里云 RDS 口令（账号 `yu_ai_agent` @ 某实例）、DashScope API Key、SearchAPI Key、高德地图 API Key（`mcp-servers.json`）。最终处置：**RDS 实例长期停机**（连接不可达，口令等于失效；日后若重新开机，第一件事是改密并检查白名单）；**DashScope key 已作废重建**（旧 key 实测 `401 API-key is blocked`，新 key 实测嵌入返回 1536 维正常）；**SearchAPI key 弃用**（search 工具待重写，应去 searchapi.io 撤销该 key 而非重建）；**高德 key 所在配置整段删除**（若该 key 仍有效，去高德开放平台撤销）；**GitHub fork 已删除**、账号改名。**教训：删远端不会收回已泄露的 key，轮换/撤销才是把风险归零的动作；判断"有没有进过 git 历史"要用 `git log -S '< 片段>'`，不能只看当前文件**
- 推送前检查清单：`git log main --pickaxe-regex -S 'sk-[A-Za-z0-9_-]{20,}'` 无命中（只查新历史；守卫脚本自身的规则串不会自命中）、`bash scripts/check-secrets.sh` 通过、`git check-ignore application-local.yml` 有输出
- **仓库历史布局（提交轮 2026-10-10 重建）**：`main` = 全新历史（12 个提交：1 基线 + 10 主题 + 1 文档，明文凭据从未进入）；`master` / `develop` = 旧历史，仅本地对照，**永不推送**。远端：`origin` = `heyandong123/super-agent`（推送目标）、`upstream` = `liyupi/yu-ai-agent`（只读拉更新）
