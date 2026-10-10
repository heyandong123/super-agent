# yu-ai-agent 优化计划与执行记录

> 本文档 = 侦察结论 + 优化候选 + 执行批次 + 验证证据 + 密钥事故处置方案。
> 写作时间：2026-10-10。**状态标记**：✅ 已完成并验证 / 🟡 部分完成 / ⬜ 未开始（含理由）。

---

## 1. 背景与目标

这是一个 Spring AI 1.0 教学项目（ReAct 智能体 + 多智能体协调器 + 工具动态调用 + 分层记忆 + RAG + SSE 流式）。教学属性带来两个副作用：**大量刻意保留的原理注释与 TODO**，以及**为演示而留下的真实凭据**。

本轮目标（按优先级）：

1. 把已泄露到公开 fork 的凭据影响面**归零**（轮换 + 防再犯的守卫）；
2. 恢复**验证能力**（测试全链路此前编译不过，等于没有回归网）；
3. 修掉几个**独立的真 bug**（前端不渲染、终端工具挂死、路由关键词误命中、SSE 生命周期）；
4. 让文档与代码一致（CLAUDE.md / README）；
5. 为"删库重建 + 分批入库"准备好方案与检查清单。

**明确不在本轮范围**：任何 git 写操作（`add/commit/push/rm --cached`）、任何历史改写、多智能体重构、全量格式化。这些留给"提交轮"或单独一轮。

---

## 2. 现状快照（侦察结论）

### 2.1 工程

| 项 | 值 |
| --- | --- |
| 语言/运行时 | Java 21（Corretto 21.0.10），Maven Wrapper 3.9.9 |
| 框架 | Spring Boot 3.4.4 + Spring AI 1.0.0 + Spring AI Alibaba 1.0.0.2 |
| 模型 | 对话 `deepseek-v4-flash`（@Primary）；嵌入 DashScope `text-embedding-v1`（1536 维，走自动配置默认常量） |
| 存储 | PostgreSQL + pgvector，3 张向量表（`vector_store` / `tool_vector_store` / `chat_memory_store`） |
| 规模 | `src/main/java` 70 个类 6960 行；`src/test/java` 24 个测试类 |
| 前端 | `yu-ai-agent-frontend`（原版）、`he-ai-agent-frontend`（本地开发版），Vue 3 + Vite，接口基址硬编码 |

### 2.2 测试与质量门禁

| 类别 | 数量 | 说明 |
| --- | --- | --- |
| 非 Spring 纯单测 | 11 | 其中 9 个已打 `@Tag("unit")`，可离线跑 |
| `@SpringBootTest` | 13 | 需要本地 PG + 真实密钥；其中 4 个无门控地打真实大模型/外网 |

**质量门禁为零**：无 checkstyle / spotless / pmd / sonar / .editorconfig、无 CI、无 pre-commit（本轮补了 `.editorconfig` + 密钥守卫钩子）。

### 2.3 仓库卫生

跟踪 1318 个文件，其中 **1200 个是制品**：1178 个 `node_modules` + 4 个前端 `dist` + 42 个 `.DS_Store`（三类互有重叠，去重后 1200）。`he-ai-agent-frontend` 整个目录曾被误入库（该目录当时没有 `.gitignore`）。

此外有 **41 个源文件（31 main + 10 test）尚未入库**，包括整个 `memory/`（12 个类）与 `toolrouter/`（4 个类）——误删即丢失。（提交轮已全部收入新历史，见 §5。）

### 2.4 密钥（事故）

提交 `3fdb175`（"更新了1.txt"）把 4 个真实凭据推进了公开 fork，且该提交在 fork 的 `main` 上。取证方式：对历史做**前 8 位指纹比对**（指纹仅本地留档，不写入仓库）：

| 凭据 | 原位置 | 最终处置 |
| --- | --- | --- |
| 阿里云 RDS 口令（账号 `yu_ai_agent` @ 某阿里云 RDS 实例） | `application.yml:10`、`application-prod.yml:12` | **实例长期停机**（连接不可达，口令等同失效）。日后若重新开机：第一件事改密 + 检查 IP 白名单是否放开过 `0.0.0.0/0` |
| 百炼 DashScope API Key | `application.yml:14`、`application-prod.yml:16` | **已作废重建** ✅ 旧 key 实测 `401 API-key is blocked`；新 key 只写入 `application-local.yml`（未跟踪 + 已忽略），实测嵌入返回 **1536 维**正常 |
| SearchAPI Key | `application.yml:62`、`application-prod.yml:64` | **弃用**（search 工具待重写）。应去 searchapi.io **撤销**该 key 而非重建 |
| 高德地图 API Key（`mcp-servers.json` 的 amap-maps server） | `mcp-servers.json:10` | 配置整段删除；若该 key 仍有效，去高德开放平台删除 / 重置 |

**已泄露面的封堵** ✅：GitHub fork 已删除（API 实测 404）；账号改名后旧用户名已释放（可被任何人注册）；泄露提交 `3fdb175` 从未到达上游 `liyupi/yu-ai-agent`（API 实测 422）。
**未泄露**：DeepSeek Key（对历史做 `git log -S` 零命中，只存在于未跟踪的 `application-local.yml`）——不必轮换。
**当前工作区状态**：被跟踪的配置文件已全部改为 `${ENV:}` 占位符，守卫脚本 0 命中 ✅。

---

## 3. 十个优化候选（收益 / 风险 / 成本）

| # | 候选 | 收益 | 风险 | 成本 | 状态 |
| --- | --- | --- | --- | --- | --- |
| 1 | 密钥防泄露加固（扫描 + `.gitignore` + 守卫脚本 + 钩子） | 极高 | 低 | 低 | ✅ |
| 2 | 修复 3 个测试类编译 + 给昂贵评测加开关 | 高 | 极低 | 低 | ✅ |
| 3 | he 前端补 `process`/`final` 事件监听 | 高 | 低 | 低 | ✅ |
| 4 | 终端工具进程管理（stderr/超时/销毁） | 中高 | 低 | 低 | ✅ |
| 5 | 路由关键词英文词边界匹配 | 中 | 低 | 低 | ✅ |
| 6 | BaseAgent 生命周期（finally 顺序 / 状态机 / 可取消 / 心跳 / onError） | 中高 | 中 | 中 | ✅ |
| 7 | 文档修正（CLAUDE.md / README） | 中 | 极低 | 低 | ✅ |
| 8 | 仓库瘦身 + 分批入库 + 干净历史推送 | 高 | 低 | 中 | ✅ 已执行（2026-10-10，已推送） |
| 9 | 质量门禁（`.editorconfig` + `@Tag("unit")` + CI） | 中 | 低 | 中 | 🟡 CI 留到提交轮 |
| 10 | 多智能体重构（`run()` 结构化返回、去双重合成） | 中 | 中高 | 中 | ⬜ 需单独一轮设计 |

排序理由：**密钥是唯一有外部后果的问题**；测试编译不过则后面所有修复都没有验证手段；3/4/5 是三个互不冲突、单文件、可离线验证的真 bug；6 牵动状态机所以拆小步做；8 与提交策略绑定；9 里的 Spotless 明确不引入（会把 856 行的 `ToolCallAgent` 近乎逐行重排，叠加当前 ±2500 行功能 diff 后 review 不可读）。

---

## 4. 执行批次与验证证据

### 批次 0：凭据处置 ✅ 已收口

最终动作：**RDS 实例停机**（无需改密）→ **DashScope key 作废重建**（旧 key 返回 401；新 key 已写入 `application-local.yml` 并实测 1536 维可用）→ **SearchAPI key 弃用**（待去后台撤销）→ **GitHub fork 已删除**、账号改名 `heyandong123`。
遗留待办：本地 git remote 仍指向 `git@github.com:peace7love/yu-ai-agent.git`，而 `peace7love` 这个名字已被释放——等用户批准后 `git remote remove main`。

> 删除远端**不能**收回已暴露的凭据：公开期间的 key 通常几分钟内就被爬虫扫走。轮换才是把风险归零的动作。
> 顺手检查 RDS 的 IP 白名单有没有放开 `0.0.0.0/0`——口令与公网地址同时泄露时，白名单是最后的屏障。

### 批次 1：密钥防泄露加固 ✅

| 改动 | 文件 |
| --- | --- |
| `.gitignore` 加固：`node_modules/`、`dist/`、`*.log`、`*.pdf`、`codex/`（原来写的是 `.codex/`，与实际目录不符）、`.claude/`、个人笔记 | `.gitignore` |
| 密钥扫描守卫：`sk-` 形态、常见「键=值」形态，输出**掩码**（只留前 4 位），支持 `${VAR}` / `<...>` / placeholder 等白名单，并按行号去重 | `scripts/check-secrets.sh`（新增） |
| pre-commit 钩子调用守卫 | `.githooks/pre-commit`（新增）+ `git config core.hooksPath .githooks` |

**验证**：阳性对照（临时放一个假 key 的跟踪文件）→ 退出码 1 且**只显示掩码**；脚本自检 `grep -c 'Pr0be'` = 0；阴性对照 → 退出码 0；`git hook run pre-commit` 双向验证通过。
**踩过的坑（值得记住）**：`sed` 是**最左匹配**，`grep -Hn` 前缀里的行号会被当成"键名"、`password` 会被当成"值"，导致掩码失效把明文打了出来。修法：先剥掉 `file:line:` 前缀再掩码，并加自检。

### 批次 2：恢复验证能力 ✅

- `ToolSelectionAccuracyTest:191` 补第 7 个实参（`ConversationMemoryService`）+ 类级 `@EnabledIfEnvironmentVariable(named="RUN_TOOL_EVAL")`；
- `LayeredMemoryEvaluationTest`：改新签名（userId 传 `"default"`，与生产 `recordLoveAppInteraction` 的落库键一致）；**附带修掉 `memoryFile()` 少一层 `{userId}` 目录**导致 `@AfterEach` 从来没删掉过文件的问题；
- `MemoryUpdateServiceTest`：改 4 参签名 + `RUN_MEMORY_EVAL` 门控 + 唯一 conversationId。

**验证**：`./mvnw -q test-compile` 由 **BUILD FAILURE → BUILD SUCCESS**；`-Dtest=ToolSelectionAccuracyTest` 由"跑不了" → `Tests run: 1, Skipped: 1`（0.002s，确认门控生效）。

### 批次 3：三个独立 bug ✅

**3.1 he 前端不渲染正文**：`chatApi.js` 只监听 `onmessage`，而后端推的是具名事件 `process`/`final`。改法：补两个 `addEventListener` + `extractContent()` 兜底 `onmessage`（页面组件零改动）。验证：`grep addEventListener` 命中 process/final/session ✅。

**3.2 终端工具会永久挂死**：旧实现只读 stdout、**从不消费 stderr**、`waitFor()` 无超时、截断后不再读——管道缓冲区（约 64KB）写满即死锁。
改法：`redirectErrorStream(true)` 让 stderr 合并进 stdout（比"再开一个线程读 stderr"更简单，整类问题消失）、守护线程持续排空、`waitFor(30s)` 超时后 `destroyForcibly`（连子进程一起杀）、截断后继续读、恢复中断标记。
**实测证据**（JEP-330 单文件程序跑旧实现）：stdout 洪泛 → 9ms 返回退出码 **141 (SIGPIPE)**（子进程被自己杀掉，然后把"失败"报给模型）；stderr 洪泛 → **永久挂起**（60 秒未返回）。
验证：`TerminalOperationToolTest` 6/6（含 stderr 洪泛与超时用例），1.1 秒。

**3.3 路由关键词子串误命中**：`"file"` 会命中记忆注入的 `[User Profile]` → readFile/writeFile 每轮恒定暴露；`"ls"` 命中 `tools`/`results` → 终端工具无端暴露。
改法：抽出 `toolrouter/KeywordMatcher`——英文关键词走词边界 `(?<![a-z0-9])kw s?(?![a-z0-9])`（允许复数、下划线/点号算边界），中文与含符号关键词仍走子串；工具名与工具返回文本（如 `amap_around_search`、`http status: 429`）继续用子串，**语义分三类不能一刀切**。
顺带纠正：代码里**从来没有** `"read"` 关键词，旧文档里的 `"read" ⊂ "already"` 并不存在。
验证：`KeywordMatcherTest` 7/7。

### 批次 4：BaseAgent 生命周期 ✅

| 小步 | 问题 | 改法 |
| --- | --- | --- |
| 4.1 | 记忆写入在 `cleanup()` 之前且会调 LLM，一抛异常 `cleanup()` 就被跳过 → 状态机永远停在 RUNNING | 两处 finally 都改成先 `cleanup()`，记忆写入包进 `recordMemorySafely()` 自己吞异常（fail-open 本意） |
| 4.2 | 循环条件是 `state != FINISHED`；超时回调置 ERROR → `cleanup()` 把它改回 IDLE → **条件依然成立**，连接已断还会继续跑含 LLM 调用与工具副作用的步骤 | `state` 加 `volatile`；循环条件改为 `state == AgentState.RUNNING`（改前已 grep 全部状态写入点确认安全：只有 RUNNING/FINISHED/ERROR，没有实现类在循环中途写 IDLE） |
| 4.3 | `CompletableFuture.runAsync` 拿不到句柄，且 `cancel(true)` **对已开始执行的 lambda 不产生中断** | 换成静态 `ExecutorService`（守护线程）+ `Future`，超时/错误/客户端断开时 `worker.cancel(true)` |
| 4.4 | 长步骤间无事件 → 中间代理/浏览器判连接空闲断开；无 `onError` 回调 | 15s 心跳用 `SseEmitter.event().comment()`（注释帧不触发前端事件、不污染会话内容）；补 `onError`；三个回调里统一 取消 future + 停心跳 + `cleanup()` |

验证：`BaseAgentLifecycleTest` 4/4 —— 记忆仓库抛异常时状态仍回到 IDLE 且消息清空；`step()` 内把状态挪出 RUNNING 后循环立刻结束（不再跑满 maxSteps）；流式 worker 在状态被回调改走后不再执行第二步。
**测试自身的坑**：一开始用"轮询 `state == IDLE`"当收尾信号，结果**假阳性通过**——初始状态本来就是 IDLE。改成用记忆写入作为"worker 已收尾"的闩锁（finally 里 `cleanup()` 在写记忆之前，所以闩锁一放行状态就已落定）。

### 批次 5：文档修正 ✅

- **CLAUDE.md**：修正 4 处（he 前端监听描述、`application-local.yml` 的 `store-type` 其实是 `pgvector` 不是 `memory`、删掉不存在的 `"read"` 关键词、`AiController.chatModel` 其实是构造实参不是死代码）；把文首"测试编译不过"的警告改成**"绝对不要直接跑 `./mvnw test`"**（13 个 `@SpringBootTest` + 4 个会打真实 LLM/外网）；新增 4 节：**代码规范 / 禁区 / 验证命令 / 密钥政策与事故记录**；新增"本轮已修复"清单。
- **README**：4 处绝对路径链接改相对路径、补完被截断的结尾（"AI 服务 Serverless 部"）、"3 层配置"实际是 4 个文件、说明密钥可写进 `application-local.yml` 或用环境变量、补"本仓库当前状态"（测试命令 / 前端 / 指向 CLAUDE.md）。

### 批次 6：门禁 🟡

`.editorconfig`（零源码 diff：4 空格 Java、2 空格 yml/js、md 不剪行尾空格）+ 9 个离线测试类打 `@Tag("unit")` → **`./mvnw -q -Dgroups=unit test` 成为等价 CI 命令**。CI workflow 留到提交轮（新仓库建好之后）。
验证：`-Dgroups=unit` 恰好跑那 9 个类，**36 个用例全绿，约 4 秒**。

---

## 5. 提交轮方案（✅ 已执行 2026-10-10）

### 5.1 仓库瘦身与分批入库（执行结果）

1. 制品出库：**1200 个**误入库文件（`node_modules` / 前端 `dist` / `.DS_Store`）移出索引，工作区文件保留；
2. 全新历史：`git checkout --orphan main` 起步，合计 **12 个提交** = 1 个基线快照 + 10 个主题（安全 → 记忆 → 工具路由 → 多智能体 → SSE 生命周期 → 工具健壮性 → RAG → 前端 → 评测 → 工程配置）+ 1 个文档；
3. 横跨多主题的文件（`ToolCallAgent` / `AiController`）整文件归入一个提交并在 body 说明；
4. 每个提交前做三件事：重跑守卫脚本、检查暂存区无制品 / 无 `application-local.yml`、预演提交内容；提交后 `git show --stat` 复核。

### 5.2 远端重建后的干净历史（已选方案 A 并执行）

- **方案 A（推荐）全新历史**：新仓库 + `git checkout --orphan` 做一次初始提交（或 `git init` 后导入当前工作区）。历史里不存在任何明文，操作最简单、无改写风险。
- **方案 B 保留历史**：`git filter-repo --replace-text` 清掉 `3fdb175` 里的明文再推。需要额外装工具，且该提交还夹带 1178 个 `node_modules`，重写面很大——只在"必须保留提交历史"时才值得。

先轮换凭据再推送 —— 这一步已完成（RDS 停机 / DashScope 已重建 / SearchAPI 弃用 / amap 配置删除），新历史 `main` 中不存在任何明文。

> ⚠️ 旧分支（`master` / `develop`）保留在本地仅作对照，**永不推送**——它们包含 `3fdb175` 的历史明文。推送时只推 `main`。

### 5.3 推送前检查清单（✅ 已全部通过）

```bash
git log main --pickaxe-regex -S 'sk-[A-Za-z0-9_-]{20,}'   # 期望：无命中（只查新历史；旧分支仅本地对照）
bash scripts/check-secrets.sh     # 期望：退出码 0
git check-ignore application-local.yml   # 期望：有输出（被忽略）
git status --short | grep -c 'application-local.yml'   # 期望：0
```

> ✅ **执行结果（2026-10-10）**：四项预检全部通过后执行 `git push -u origin main`，推送成功——`origin`（`heyandong123/super-agent`，公开仓库）上**只有 `main` 一条历史**（SHA `71e13cf`）；`master` / `develop` 未推送；`upstream` 已设为仅 fetch（push URL → `DISABLED`），杜绝误推上游。

---

## 6. 不做清单（含理由）

| 不做 | 理由 |
| --- | --- |
| Spotless / 全量格式化 | 会把 856 行的 `ToolCallAgent` 近乎逐行重排，叠加当前 ±2500 行 diff 后 review 不可读 |
| git 历史改写（本轮） | 你选择删库重建；且必须先轮换凭据才有意义 |
| 记忆写入异步化（`@Async`） | 会改变 fail-open 语义与可观测性，还会打断刚修好的评测断言 |
| 清理 44 处教学 TODO 与死代码 | 教学素材；删除需先确认引用（`PDFGenerationToolPro`、`executionTools`、`FileBasedChatMemory` 等） |
| Testcontainers 让 `./mvnw test` 全绿 | 成本高；用 `-Dgroups=unit` 划出可信子集即可 |
| 外层 30s `Future.get` 不可取消 | 已知，但属批次 10 的重构范围 |
| 线程安全重构 | 当前"每请求 new 一个 Agent"的约定自洽，改成共享实例是另一件事 |
| 多智能体 `run()` 返回契约改造 | 会牵动所有调用方，需单独一轮设计（见候选 10） |

---

## 7. 面试口径（每个"为什么"）

- **为什么先修密钥？** 它是唯一有**外部后果**的问题：其他 bug 影响本机运行，密钥泄露影响账号资产。修复成本也最低（一个 `.gitignore` + 一个扫描脚本 + 一个钩子）。
- **为什么"删远端"不够？** 密钥一旦推到公开仓库，几分钟内就可能被扫描器抓走。删除远端只阻断未来暴露；**轮换**才是把风险归零。判断"有没有进过历史"要用 `git log --all -S`，不能只看当前文件。
- **为什么测试编译不过要第一个修？** 没有回归网，后面每个"修复"都只能靠肉眼。修完立刻有了 36 个 4 秒内跑完的用例，改动才敢小步走。
- **为什么用 `redirectErrorStream` 而不是"再开一个线程读 stderr"？** 两者都能解死锁，但合并流把"两个读者 + 一个 writer"的协调问题整个消掉，代码量与出错面都更小——**能用结构消除的问题不要用并发去管理**。
- **为什么关键词匹配要分三类语义？** 同一个 `contains` 调用在不同场景语义不同：路由上下文是自然语言（要词边界）、工具名是标识符（要子串）、错误文本要宽松（要子串）。一刀切任何一边都会踩坑——这也是原来 `"file" ⊆ "profile"` 的根因。
- **为什么循环条件要从 `!= FINISHED` 改成 `== RUNNING`？** 前者把"停止"定义成单一状态，任何新增的中间状态（ERROR/IDLE）都变成"继续跑"。写成白名单语义后，未知状态默认停止——**状态机的终止条件应该表达"什么情况下继续"，而不是"什么情况下停止"**。
- **为什么 `CompletableFuture` 要换掉？** 它拿不到可中断的句柄：`cancel(true)` 对已经开始执行的 lambda 不产生中断。连接断开后 agent 会继续烧 LLM 额度、继续产生文件副作用。换成显式线程池 + `Future` 后 `cancel(true)` 才真的能打断。
- **为什么心跳用注释帧？** 它走 SSE 协议但**不触发前端任何事件**（既不是 `message` 也不是具名事件），所以能在不改前端契约、不污染会话内容的前提下保活。
- **为什么给测试打 `@Tag("unit")`？** 这个仓库里 `./mvnw test` 是个陷阱（真实 LLM + PG）。用标签划出"能离线跑的可信子集"，让 `-Dgroups=unit` 成为可复制的 CI 命令。
