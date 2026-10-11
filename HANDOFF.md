# HANDOFF —— 交给另一个 AI Agent 的交接说明

> 生成于 2026-10-11，作者：Claude Code（会话 `426bfc0a`）。
> **这份文件是那个会话的「要点提炼」，不是聊天记录**：只保留接手需要的——当前状态、四轮改动与实测数字、
> 待办、硬约束。项目正在从 Claude Code 转由 Codex 维护，本文件就是为这次交接写的。
> **这份文件是一次性交接材料，不是项目文档**：新 agent 读完即可上手，任务结束后可删除。
> **信息源优先级**：`CLAUDE.md`（项目宪法，最权威）> `docs/*.md`（各轮方案 + 实测数字）> 本文件（在飞状态）。
> 同目录的 `AGENTS.md` 是给 Codex 的常驻入口（小而硬的规则摘要），Codex 启动时会自动加载，无需手动指路。

---

## 0. 30 秒概览

- **项目**：`yu-ai-agent`，Spring AI 1.0 教学项目（ReAct 智能体 YuManus + 多智能体协调器 + 分层记忆 + RAG + SSE）。
  用户的现实目标：拿这份作品找 Java/Agent 实习 offer。
- **仓库**：`main` = `11af22d`（工具重构轮，**已提交但未推送**，`origin/main` 落后 1 个提交）。
  `origin` = `heyandong123/super-agent`（公开仓库）；`master`/`develop` 是旧历史，**永不推送**。
- **工作区**：**37 个已跟踪文件改动 + 24 个新文件未跟踪，全部未提交**，是连续四轮优化的成果，用户明确要求「等指令再提交」。
- **当前状态**：四轮全部完成并验证（离线单测 142 例全绿、密钥守卫 0 命中、两前端 build 绿）。
  没有进行中的半成品改动；下一步是「等用户决定提交切分」。

---

## 1. 硬约束（违反会造成真实损失，先读这一节）

1. **密钥**：真实 key 只允许出现在 `src/main/resources/application-local.yml`（未跟踪 + 已 gitignore）。
   被跟踪文件一律 `${yuai.api-keys.xxx}` 占位。**提交前必跑 `bash scripts/check-secrets.sh`（期望 0 命中）**。
   新增第三方 key 时同步给该脚本补前缀规则。历史事故与后续处置见 CLAUDE.md「密钥政策与事故记录」。
2. **推送**：只推 `main` → `origin`。`master`/`develop` 含明文凭据泄露提交 `3fdb175`，**永不推送**。
3. **不要自行提交/推送**：用户的原话是「改动一律不提交，等指令」。任何 commit/push 前先问。
4. **评测类必须带环境变量门控**（真实 LLM 计费）：`RUN_TOOL_EVAL` / `RUN_MEMORY_EVAL` / `RUN_CONTEXT_EVAL` / `RUN_STREAM_EVAL`，
   不开就自动跳过。**绝对不要跑 `./mvnw test`**（14 个 @SpringBootTest 要 PG + 真实 key，其中 4 个无门控打真实 LLM/外网）。
5. **教学素材不许清理**：44 处 TODO 与成段原理注释是课程内容，只增不删；不要"顺手"整体格式化 `ToolCallAgent`（856 行）。
6. **用户已拍板的语义，不要再改回去**（详见 CLAUDE.md 禁区）：
   - **单用户单会话**：记忆工件全局唯一一份（`tmp/memory/{profile,summary}.json` + `history.jsonl`），
     **不许再引入 userId/conversationId 记忆键**；子 Agent 必须 `setMemoryWriteEnabled(false)`。
   - `yuai.context.mode` 默认保持 `legacy`（budget 模式 A/B 六轮未达标，原因见 docs/context-memory-refactor.md §5.2）。
   - 不要把 RAG 接回 `YuManus`；不要动 `ToolVectorRouter` 的 0.45/topK2；不要给 Agent 加单例。
   - 不要在 `MemoryWriteQueue` 加 `CallerRunsPolicy`；不要给 `run()` 加"合成最终答案"的 LLM 调用。

---

## 2. 未提交改动的四轮内容（每轮都有独立方案文档）

| 轮次 | 文档 | 一句话 | 关键数字 |
| --- | --- | --- | --- |
| 工具重构轮（2026-10-10） | docs/tool-refactor-plan.md | 工具暴露改全量加载（对标 Claude Code/OpenHands 固定工具面）+ 描述重写 + 护栏 | 常驻 9 工具 5860 字符 ≈ 1465 token/次；**已提交 11af22d，未推送** |
| 上下文/记忆重构轮（阶段 0~4） | docs/context-memory-refactor.md §1~§8 | 四层漏斗（入口截断/常驻做小/运行层两级压缩/退出落盘）+ 记忆写入异步化 | 超长归档 143~146s → **0.78s**；A/B 六轮判定未达标 → 默认保持 legacy |
| 单用户单会话收敛轮（2026-10-11） | docs/context-memory-refactor.md §9 | 删全部记忆寻址键、工件收敛三件、新增近场历史恢复 L4.5 | 离线 **127 例全绿**；记忆评测 6 例全绿；近场恢复成本 +1214 字符 ≈ +320 token |
| 流式轮（2026-10-11） | docs/streaming-refactor.md | 整步伪流式 → **逐 token 真流式**（新增 `delta` 事件，think + 最终答案逐字上屏，工具仍是离散步骤行） | 两轮 A/B 首字 **4305→937ms（−78%）/ 4096→826ms（−80%）**；离线 **142 例全绿**；回滚开关实测零 delta 帧 |

**工作区文件分布（61 项）**：主代码 `agent/`（构造器链 + 流式分支 + 快照字段）、`stream/`（新包 4 类）、
`context/`（新包 4 类）、`memory/`（新仓储 + 队列 + 配置装配）、`metrics/`（新包 2 类）、`controller/AiController`（手动 new 传参）、
`resources/application.yml`（配置开关）；测试 10+ 个新类；两前端（`he-ai-agent-frontend` / `yu-ai-agent-frontend`）同步改了 SSE 监听与渲染。

---

## 3. 验证快照（截至 2026-10-11 17:10，可复跑）

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./mvnw -q -DskipTests compile && ./mvnw -q test-compile     # 绿
./mvnw -q -Dgroups=unit test                                # 142 例全绿（约 7 秒）
bash scripts/check-secrets.sh                               # 0 命中
(cd he-ai-agent-frontend && npm run build) && (cd yu-ai-agent-frontend && npm run build)   # 均绿
```

已跑过的昂贵评测（**不要再无意重跑**，各需显式开关）：

- `RUN_MEMORY_EVAL=true`：记忆评测 6 例全绿（超长归档 1526ms）。
- `RUN_STREAM_EVAL=true ./mvnw test -Dtest=StreamingLatencyAbEvalTest`：两轮报告在 `tmp/metrics/streaming-ab-*.md`，均达标。
- `RUN_CONTEXT_EVAL=true`：上下文 A/B 六轮，报告在 `tmp/metrics/context-ab-*.md`，判定未达标（决策记录见文档）。
- 冒烟（需本地 key + 服务运行）：`curl -N -H 'Accept: text/event-stream' 'http://localhost:8123/api/ai/manus/chat?message=你好'`
  期望：`session` → 若干 `delta(scope=think)` → `process` → `delta(scope=final)` → `final`。

---

## 4. 下一步待办（按优先级）

1. **等用户决定：这四轮怎么提交**。建议切分：① 上下文/记忆重构 ② 单用户单会话收敛 ③ 流式轮 ④ 文档/CLAUDE.md；
   提交前过 §3 的验证 + `check-secrets.sh`；**提交后是否推送也要问**（推送=对外发布）。
2. **Claude Code 运行时上下文机制调研（已存档，用户未拍板是否实现）**：结论在本仓库 memory 之外，
   要点是「本项目与 Claude Code 差三处：usage 实测锚点 / 触发位置（35-50% 应贴上限 ~84%）/ 摘要模板（9 节交接文档）」，
   详见 `docs/context-memory-refactor.md` 与 CLAUDE.md 的相关节。若用户要继续优化上下文，从这三处入手。
3. **已知未修问题**（CLAUDE.md「已知问题」有完整清单）：`YuManusMultiAgent` 的 `run()` 返回契约
   （异常被吞成字符串 → 子任务 success 恒为 true；最终答案合成两次）、`executionTools` 等死代码。

---

## 5. 上手命令与关键文件地图

```bash
./mvnw spring-boot:run          # 8123 端口，context-path /api，profile=local（需要 application-local.yml 的真实 key + 本地 PG）
```

- **先读**：`CLAUDE.md`（项目概述 → 核心架构 → 禁区 → 验证命令，约 280 行，信息密度最高）。
- `agent/BaseAgent.java`：执行循环 + 状态机 + SSE + **流式增量出口**（`emitTextDelta`，sink 只有 `runStream` 安装）。
- `agent/ToolCallAgent.java`：think/act、工具暴露、压缩分流、熔断重试（856 行，**不要整体格式化**）。
- `stream/StreamingChatAggregator.java`：自建聚合器（框架 `MessageAggregator` 不聚合 toolCalls，别换）。
- `context/ContextWindowManager.java`：budget 两级压缩；`memory/`：分层记忆 + 异步写队列。
- `controller/AiController.java`：每请求手动 `new` Agent，所有依赖沿构造器链传（**不加 setter/@Autowired**）。

---

## 6. 与用户协作的偏好（从历史会话总结）

- **全程中文**（注释、日志、文档、回复）。
- 用户是研究生、在找 Java/Agent 实习：**每次优化都要给「问题 → 业界怎么做 → 我的取舍 → 实测数字」**，
  没指标数字的优化会被视为未完成（CLAUDE.md「终极目标」四准则）。
- 用户会拿面试真题来提问，要按面试标准作答（讲清取舍，不是复述功能）。
- 用户偏好：先给结论和数字，再给细节；诚实报告失败项与噪声（例如流式轮的 token 差异两轮方向相反，就明说"不作结论"）。
- 涉及真实成本的操作（评测跑真实 LLM、提交、推送）先问再做。
