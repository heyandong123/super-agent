# AGENTS.md —— 项目入口（Codex / 其他读取 AGENTS.md 的 agent）

> **本文件是入口与硬约束摘要，不是全文**。完整、权威的项目说明在 **`CLAUDE.md`**（53 KB，开工前请先完整读一遍），
> 各轮优化方案与实测数字在 `docs/*.md`，最近一次交接状态在 `HANDOFF.md`。**冲突时以 `CLAUDE.md` 为准。**
> 本项目由 Claude Code 长期维护，`CLAUDE.md` 是它的项目说明文件；本文件只是让别的 agent 也能接手。

## 项目一句话

Spring AI 1.0 教学项目（AI 智能体平台）：ReAct 自主智能体 YuManus + 多智能体协调器 + 分层记忆 + RAG + SSE 流式。
用户的现实目标是拿这份作品找 Java / Agent 实习 offer。**代码注释、日志、文档、回复一律中文。**

## 先读这三处（按顺序）

1. `CLAUDE.md` —— 项目宪法：架构、四处反直觉的坑、禁区、验证命令、密钥政策。
2. `HANDOFF.md` —— 最近一次交接：当前在飞状态（未提交改动）、待办、协作偏好。
3. `docs/` —— 每轮优化的「问题 → 业界对标 → 取舍 → 实测数字」：`optimization-plan.md`（轮次索引）、
   `tool-refactor-plan.md`、`context-memory-refactor.md`、`streaming-refactor.md`。

## 硬约束（违反会造成真实损失）

1. **密钥**：真实 key 只允许出现在 `src/main/resources/application-local.yml`（未跟踪 + 已忽略）。
   被跟踪文件一律 `${yuai.api-keys.xxx}` 占位。**提交前必跑 `bash scripts/check-secrets.sh`（期望 0 命中）**。
2. **推送**：只推 `main` → `origin`（`heyandong123/super-agent`）。`master` / `develop` 含明文凭据泄露历史，**永不推送**。
3. **不要自行 commit / push**：用户要求改动先攒着、等他指令。
4. **评测类必须带环境变量门控**（真实 LLM 计费）：`RUN_TOOL_EVAL` / `RUN_MEMORY_EVAL` / `RUN_CONTEXT_EVAL` / `RUN_STREAM_EVAL`。
   **绝不跑 `./mvnw test`**（14 个 @SpringBootTest 要本地 PG + 真实 key，其中 4 个无门控会打真实 LLM/外网）。
5. **教学素材不许清理**：约 44 处 TODO 与成段原理注释是课程内容，只增不删；不要"顺手"整体格式化 `ToolCallAgent`（856 行）。

## 用户已拍板的语义（不要再改回去）

- **单用户单会话**：记忆工件全局唯一一份（`tmp/memory/{profile,summary}.json` + `history.jsonl`）；
  **不许引入 userId / conversationId 记忆键**；子 Agent 必须 `setMemoryWriteEnabled(false)`。
- `yuai.context.mode` 默认保持 `legacy`（budget 模式 A/B 六轮未达标，理由见 `docs/context-memory-refactor.md` §5.2）。
- 不要把 RAG 接回 `YuManus`；不要动 `ToolVectorRouter` 的 0.45 / topK 2；不要给 Agent 加单例或共享状态。
- 依赖只能沿构造器链传（`AiController` 手动 `new` Agent），**不加 setter / @Autowired**。
- 流式的增量出口（sink）只有 `runStream` 安装；`StreamDisconnectedException` 不许被通用 catch 吞掉。

## 常用命令

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # 需要 JDK 21
./mvnw -q -DskipTests compile && ./mvnw -q test-compile
./mvnw -q -Dgroups=unit test        # 离线子集，约 7 秒，142 例应全绿（新增离线测试请照抄 @Tag("unit")）
./mvnw spring-boot:run              # 8123 端口，context-path /api，profile=local（需真实 key + 本地 PG）
bash scripts/check-secrets.sh       # 提交前必跑
(cd he-ai-agent-frontend && npm run build) && (cd yu-ai-agent-frontend && npm run build)
```

冒烟：`curl -N -H 'Accept: text/event-stream' 'http://localhost:8123/api/ai/manus/chat?message=你好'`
期望帧序：`session` → 若干 `delta(scope=think)` → `process` → `delta(scope=final)` → `final`。

## 协作偏好

- 每次优化都要给「**问题 → 业界怎么做 → 我的取舍 → 实测数字**」；**没有指标数字的优化不算完成**。
- 诚实报告失败项与噪声（例：流式轮 token 差异两轮方向相反 → 明确写「不作结论」）。
- 涉及真实成本的操作（跑真实 LLM 评测、提交、推送）先问再做。
