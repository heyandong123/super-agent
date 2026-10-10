# 工具重构方案（2026-10-10 执行：full 全量加载已落地，见 §9.4）

> 依据：CLAUDE.md「终极目标」三准则——对标业界最强（禁止凭空设计）/ 优化必须可度量 / 四维度取舍。
> 本文所有对标结论均来自公开资料（见 §2），先基线后改动，不预设数据结论。
>
> **结论先行**：模型可见工具 **12 → 11（元工具 3 → 2），零新增**。本轮重点不在"砍数量"（业务 8 个本来就正交），而在：**合并元工具、重写描述与返回契约、补护栏、收紧每轮可见面**。
>
> **2026-10-10 追加（优先级最高，已裁决执行）**：用户提议整个动态调用机制退役、改全量常驻加载，并明确**跳过 A/B 评测直接落地**。离线度量 + 业界对标见 **§9**；执行记录与当前数字见 **§9.4**。**元工具合并（D2）免做**，方案进一步简化。

---

## 1. 现状（12 个活跃工具 + 5 个问题）

| 分类 | 工具 | 数量 |
| --- | --- | --- |
| 业务（进索引） | readFile / writeFile / searchWeb / scrapeWebPage / downloadResource / executeTerminalCommand / generatePDF / doTerminate | 8 |
| 元工具（L1 常驻） | searchTools / listTools / searchMemory | 3 |
| MCP | searchImage | 1 |

问题（上一轮盘点的收敛版）：

1. **元工具 3 个常驻**，每个 LLM 调用都要携带 schema；`searchTools` 与 `listTools` 职责重叠（都是"找工具"，一个语义、一个全量列举）
2. **命名撞车**：元工具 `searchTools` 与 Bean `searchTools`（工具集数组）同名不同物
3. **描述质量参差**：`executeTerminalCommand` 写得专业（主动划界），`searchWeb` 只有一句话，不告诉模型引擎链/条数/中文优先
4. **缺护栏**：`readFile` 无截断上限（能一次把上下文打爆）；`downloadResource` 无超时/大小/协议限制；`scrapeWebPage` 无 UA、超时依赖 Jsoup 默认值
5. **注册冗余**：`executionTools` 死数组、`PDFGenerationToolPro` 未接线（仅影响整洁度，不影响模型）

## 2. 业界对标（硬依据）

### 2.1 Anthropic 官方《Writing effective tools for agents》——直接可用的 8 条

1. **工具不是越多越好**：先小集合对准高价值工作流；重叠/过量的工具会分散 agent 注意力
2. **合并（consolidation）**：一个工具内部可做多步；把高频链式调用折叠为一次（官方例：`list_users`+`list_events`+`create_event` → `schedule_event`；`read_logs` → `search_logs`）
3. **命名**：按服务/资源加前缀；参数名无歧义（`user_id` 而非 `user`）；前缀 vs 后缀要实测
4. **描述是给"新员工"的简报**：写清输入格式、专有名词、**何时用/何时不用**；官方称描述精修是"最强的杠杆"之一（SWE-bench Verified SOTA 来自描述精修）
5. **返回只给高信号内容**：删低级 ID；需要两种粒度时给 `response_format=detailed/concise`（实测 206 → 72 tokens）
6. **截断/分页是默认项**：Claude Code 单次工具返回默认封顶 **25k tokens**；截断文案要引导 agent 收窄查询
7. **错误信息要可行动**：不是错误码，错误本身就是对模型的引导
8. **评测纪律**：采集 runtime / 调用次数 / token / 工具错误；冗余调用 → 调分页与上限，参数错误 → 改描述

### 2.2 Claude Code 工具集（官方 Tools Reference）

- 核心集：Read / Edit / Write / Glob / Grep / Bash / WebFetch / WebSearch（+ NotebookEdit / TodoWrite / Task / AskUserQuestion 等）
- **关键事实：macOS/Linux 上默认工具集不含 Glob/Grep**——Claude 直接用 Bash 里的 `find`/`grep`。专用工具存在的意义是**权限粒度与审计**，不是能力
- Read 支持 offset/limit；Edit 是精确字符串替换且要求先 Read 过；Bash 默认 2 分钟超时
- 单次工具返回默认 25k tokens 上限

### 2.3 OpenHands（CodeAct）——最小集哲学

- 主 Agent 只有两个核心原语：`execute_bash` + `str_replace_editor`（**单个**编辑器工具内部内聚 view / create / edit / undo，带行号视图与精确替换）
- 其余能力全部是 config 开关：`enable_browsing` / `enable_jupyter` / `enable_llm_editor`
- 结论：强 agent 的工具面可以小到 **2 个原语 + 可选扩展**

### 2.4 对我们的五条结论

| # | 结论 | 依据 |
| --- | --- | --- |
| 1 | **业务 8 个全部保留**（无冗余、正交） | 每个都有业界对应物：readFile/writeFile ↔ Read/Write；terminal ↔ Bash；searchWeb ↔ WebSearch；scrapeWebPage ↔ WebFetch；downloadResource/generatePDF 是本项目真实工作流（SearchAgent 无 terminal、文档产出链需要） |
| 2 | **不加 Glob/Grep/ls/Edit/TodoWrite** | Claude Code 自己在 macOS 上都用 Bash 干这些（§2.2）；我们的 terminal 已覆盖；Edit 留待数据裁决（§5-B） |
| 3 | **元工具 3 → 2**：searchTools + listTools 合并为 `findTools` | Anthropic 合并原则（`search_contacts` 而非 `list_contacts`）；每步少一个常驻 schema，且少一次"搜不到再 list"的往返 |
| 4 | **描述 / 返回 / 截断全面重写** | §2.1 第 4/5/6/7 条；这是本轮收益最大项 |
| 5 | **先基线、后改动、再复测** | 准则 2；指标定义见 §4 |

## 3. 重构方案

### 3.1 目标工具面

| 层面 | 现在 | 之后 | 说明 |
| --- | --- | --- | --- |
| 业务（索引内） | 8 | **8（不动）** | 每个对应真实 E2E 场景 |
| 元工具（L1 常驻） | 3 | **2**：`findTools` + `searchMemory` | searchTools+listTools 合并 |
| MCP | 1 | 1（暂不动） | 去留见 §5-C |
| 稳态每步可见 schema | 3 + 路由 2~5 | **2 + 路由 2~5 ≈ 4~7** | |
| 最坏（路由全量回退） | 3 + 8 = 11 | **2 + 8 = 10** | |
| 纯聊天路径 | doTerminate + 3 meta | doTerminate + 2 meta | |

### 3.2 决策与理由

- **D1 业务 8 个全保留**：§2.4-1。删除任何一个都会打断一条真实链路（搜图 PDF 行程、文档产出、系统操作），不符合"解决真实问题"
- **D2 元工具合并为 `findTools`**（合并 searchTools + listTools）：
  - 语义检索命中 → 命中工具加入本会话 L2，返回命中列表（现状不变）
  - 未命中 → 阈值放宽 → 关键词回退（现状不变）→ **仍无命中时，自动返回全量工具目录第 1 页 + 分页提示**（吸收 listTools 的职责，替代原来的"结构化失败提示"）
  - 模型只需要学**一个**"找工具"的工具
- **D3 `searchMemory` 保留常驻**：它不是工具发现，是记忆能力——"上次说的那个建议"必须可被发现，且无业界替代物
- **D4 命名修复**：元工具更名 `findTools`，消除与 Bean `searchTools` 的同名混淆（日志/文档/评测不再二义）
- **D5 零新增工具**：见 §7 不做清单

### 3.3 描述重写规范 + 示例

规范（每个工具都必须包含）：① 一句话能力 ② 关键参数的语义与格式 ③ **何时用 / 何时不用（主动划界）** ④ 返回结构 ⑤ 限制（截断/超时）。

两个示意例子（实施时全部重写）：

- `searchWeb`：`"Search the web for real-time information and news"` →
  `"Search the web for real-time information and news. Uses a Chinese-first engine chain (Baidu → Bing → paid fallback) and returns up to 5 results as Title/Snippet/Link. Prefer short keyword queries; when you have a specific URL, use scrapeWebPage instead."`
- `readFile`：`"Read content from a file"` →
  `"Read a file's content. A relative name resolves under the sandbox directory (absolute paths are read as-is). Output is truncated to the first 500 lines / 32KB — use offset/limit to page through large files. To create or overwrite, use writeFile."`

### 3.4 返回契约与护栏（全部不新增工具）

| 工具 | 改动 | 理由 |
| --- | --- | --- |
| `readFile` | 加 `offset`/`limit` 参数；默认截断 500 行 / 32KB，截断文案引导分页 | 现在无上限，一次可打爆上下文（§2.1-6） |
| `scrapeWebPage` | 显式 timeout 10s + 浏览器 UA；保留 3000 字符截断并带提示 | 现依赖 Jsoup 默认 30s + 外层 30s 双重叠加；无 UA 易被反爬 |
| `downloadResource` | 30s 超时、20MB 大小上限、仅允许 http/https | 现在工具自身无任何限制（靠外层 30s 兜底） |
| `executeTerminalCommand` | 不动 | 已有超时/杀进程树/50 行截断，已是业界对齐做法 |
| `doTerminate` | 不动（可选项：纯聊天零工具路径跳过三方比对 LLM 调用，省一次真实调用——标为可选，评审时定） | 项目特色机制 |
| 全部 | 统一错误前缀 `Error: … — <可行动建议>` | §2.1-7 |

### 3.5 注册与索引卫生

- `schema-version` 2 → **3**（启动时清空重建 `tool_vector_store`）
- `executionTools` 死数组 / `PDFGenerationToolPro` / `ListToolsTool`（被合并后不再注册）：**源码保留**（教学素材），不注册、不接线、不删除
- 涉及文件：[ToolRegistration.java](src/main/java/com/yupi/yuaiagent/tools/ToolRegistration.java)、[ToolCallAgent.java](src/main/java/com/yupi/yuaiagent/agent/ToolCallAgent.java)（L1 列表/构造器）、[ToolSearchTool.java](src/main/java/com/yupi/yuaiagent/tools/ToolSearchTool.java)（合并 + 更名）、[ListToolsTool.java](src/main/java/com/yupi/yuaiagent/tools/ListToolsTool.java)（停用）、4 个工具类（护栏/描述）、`ToolSearchToolUnitTest`、CLAUDE.md

## 4. 评测方案（先基线 → 改动 → 复测对比）

### 4.1 指标定义

| # | 指标 | 采集点 |
| --- | --- | --- |
| 1 | 每步可见工具数（均值/最大） | `selectToolsForCurrentStep` 返回值 size |
| 2 | 单任务 token（prompt + completion） | `ChatResponse.getMetadata().getUsage()` 累计 |
| 3 | LLM 调用数 / 工具调用数 / 工具错误数 / 冗余调用数（同工具同参重复） | act() 统计 |
| 4 | 端到端墙钟延迟（p50 / p95） | run / runStream 包裹计时 |
| 5 | 工具选择准确率 | `ToolSelectionAccuracyTest`（20 场景 × 1 轮） |
| 6 | 纯聊天场景：每步可见工具数、token | 同上采集 |

### 4.2 采集实现

新增 `AgentRunMetrics`（BaseAgent 持有、每 run 重建、finally 打一行结构化日志、提供 getter 给测试聚合）。改动小、全程 fail-open，顺带补齐"可观测"能力（终极目标里的可用性维度）。

### 4.3 基线跑法（改动前，落盘 `docs/tool-refactor-metrics.md`）

```bash
RUN_TOOL_EVAL=true EVAL_CASE_COUNT=20 EVAL_ROUNDS=1 ./mvnw test -Dtest=ToolSelectionAccuracyTest
# 预计 3~5 分钟真实 LLM 调用，费用低
```

### 4.4 判定标准（复测 vs 基线）

- 工具选择准确率**不下降**（次要波动容忍 ±5%）
- 单任务 token **不上升**
- 每步可见工具数**下降**
- 工具错误率**不上升**

不达标 → 回滚该项改动或调整方案，不允许"改了但没数"。

## 5. 数据裁决项（Phase 2，跑完基线 + 复测后决定，不预设结论）

| # | 议题 | 裁决依据 |
| --- | --- | --- |
| A | **工具暴露模式：full 全量常驻 vs dynamic 现状路由**（原「findTools 是否退役」升级为机制级 A/B，见 §9）——**先于其他一切裁决执行** | 同场景两模式对比：准确率 / 每任务 token / 步数 / 延迟 / 错误率 |
| B | 是否引入 `editFile`（str_replace 精确替换，对齐 Claude Code Edit / OpenHands str_replace_editor） | 基线中"读全文-改一行-写回"的 token 浪费占比 |
| C | MCP `searchImage` 去留；保留则修 jar 绝对路径/构建脆弱性 | 搜图场景的实际使用率 |
| D | `doTerminate` 三方比对的 LLM 调用成本 | 每任务多 1 次 LLM 调用的占比 |

## 6. 执行批次（每批独立可验证、可回滚）

| 批次 | 内容 | 验证 |
| --- | --- | --- |
| 0 | 加 `AgentRunMetrics` + 采集基线 | 离线单测绿；基线数据落盘 |
| 1 | 护栏与截断（§3.4） | 新单测：readFile 截断、scrape 超时、download 限额 |
| 2 | 元工具合并 `findTools`（D2/D4） | 更新 ToolSearchToolUnitTest + 路由回归 |
| 3 | 描述与返回重写（§3.3）；schema-version → 3 | 冒烟一条 searchWeb 场景 |
| 4 | 复测对比（§4.4） | 指标对比表 |
| 5 | 文档同步（CLAUDE.md / 归档本文件） | — |

## 7. 不做清单（非必要不引入）

| 不做 | 理由 |
| --- | --- |
| Edit / Glob / Grep / ls 等文件类新工具 | Claude Code 在 macOS 上默认集也没有 Glob/Grep（用 Bash）；我们的 terminal 已覆盖；Edit 见 §5-B 数据裁决 |
| TodoWrite / 任务计划类元工具 | 现有 maxSteps + doTerminate 清单已覆盖；无数据证明必要 |
| browser / ipython / 多模态工具 | OpenHands 里也是 config 关闭态；本项目无真实场景 |
| 新下载器 / 新搜索源 | downloadResource + searchWeb 降级链已覆盖 |
| 删除教学死代码（executionTools / Pro / ListToolsTool） | 教学素材只增不删（CLAUDE.md 禁区） |

## 8. 风险与回滚

- **影响面**：工具面变更只牵动 `ToolCallAgent` 构造器、L1 列表、文档与单测；SSE 协议、前端、记忆链路零影响
- **回滚**：每批次独立提交，`git revert` 即可；注意 `schema-version` 有重建索引副作用，回滚时要再 +1
- **成本**：基线 + 复测各约 3~5 分钟真实 LLM 调用
- **风险等级**：低（无破坏性 schema/交互协议变更；MCP 与终评项留待数据裁决）

---

## 9. 追加裁决：全量加载 vs 动态调用（2026-10-10，已裁决并执行）

**背景**：用户提议——若全量加载对 agent 表现影响微乎其微，则放弃动态调用机制，全部工具常驻。**最终裁决：不做 A/B，直接改全量加载（full）并完成工具重构**（原话："不用评测了，改为工具全量加载，弃用动态调用工具的机制，然后进行工具重构"）。

### 9.1 实测（本机离线度量，`ToolSchemaBudgetTest`，可复跑）

**决策输入（重构前，2026-10-10 度量）**：

| 项 | 字符数 | ≈ tokens / 次 LLM 调用 |
| --- | --- | --- |
| 9 个常驻候选（8 业务 + searchMemory） | 3901 | **975** |
| MCP searchImage（离线不可构造，量级估计） | ~600 | ~150 |
| 元工具 searchTools + listTools（full 模式下不存在） | 934 | 233 |
| **全量常驻合计** | ~4500 | **~1.1k** |
| 动态模式典型暴露面（3 元工具 385 + 路由 2~4 个 200~400） | — | ~0.6~0.8k |

**重构后（当前代码真实值）**：

| 项 | 字符数 | ≈ tokens / 次 LLM 调用 |
| --- | --- | --- |
| 常驻 9 个（full 模式每轮实际发送，含重写后的描述；searchWeb 改 Tavily 后 5860） | 5860 | **1465** |
| 元工具 searchTools + listTools（仅 dynamic 注册，full 不占 schema） | 934 | 233 |
| MCP searchImage | ~600 | ~150 |

描述重写让常驻面 3901 → 5860 字符（+1959）——这是**拿 tokens 换选择准确率的刻意取舍**：全量加载下 description 与参数 schema 是模型划界的唯一依据（Anthropic 8 条的第 1 条），且请求前缀恒定可吃 DeepSeek 上下文缓存；dynamic 省下的 schema 有相当部分会被每步一次的路由 embedding 往返（延迟 + 计费）与缓存不友好抵消。

- **差额 ≈ +300~450 tokens/次调用**；10 步任务约 **+4~5k input tokens**——DeepSeek 级价格可忽略，prefill 延迟增量 ~几十 ms
- 反向收益（现机制正在付出的代价）：① 工具集每步变动 → 请求前缀每步变 → 前缀缓存命中率受损（DeepSeek 类上下文缓存按请求前缀匹配，A/B 的延迟指标会直接反映）；② 路由看不到的工具需要发现往返（多一次 LLM 轮次 + 结果 token）；③ 一整套机制（索引/PG 表/降级链/L2）的复杂度与故障面，且启动期多一步索引构建

### 9.2 业界对标

| 参考对象 | 机制 | 指向 |
| --- | --- | --- |
| Claude Code | 固定工具集全量随每次请求，无路由 | full |
| OpenHands (CodeAct) | 固定 5~6 个（bash + editor + 可选），无路由 | full |
| OpenAI Agents SDK | 工具全量传入，无内置检索 | full |
| Manus（Context Engineering 文章） | **"Mask, don't remove"：不在任务中途增删工具**——会破坏 KV-cache、诱发模型引用不存在的工具；要约束就用 logit masking | 强烈反对动态增删 |
| Anthropic《Writing effective tools》 | "工具非越多越好"针对**重叠/歧义的工具设计**；工具检索的适用规模是 100+ 工具 | 本项目 4~9 个/Agent 远低于此 |

### 9.3 取舍与范围（full 模式）

| 关闭（代码保留为教学素材，配置开关控制） | 保留（与路由机制正交，不受影响） |
| --- | --- |
| searchTools / listTools 不注册 → 模型可见 12 → **10**（9 业务+记忆，+1 MCP） | 单工具 30s 超时 / 重试 2 次 / 连续失败 3 次熔断 60s / 全熔断只放行 doTerminate |
| 关键词路由表 + ToolVectorRouter 每步子集（启动期也不再需要建索引） | searchMemory 仍常驻（它是能力，不是"发现工具"） |
| L2 会话缓存 / 降级链 / 纯聊天 doTerminate-only 特例 | 上下文压缩 / doTerminate 三方比对 / SearchAgent 冗余裁剪 |

### 9.4 执行记录（2026-10-10；A/B 验证未做——用户裁决"不用评测了"）

原 9.4 设计的 A/B（同 20 场景跑 full/dynamic 对比准确率/延迟/token）**未执行**，用户明确指示跳过评测直接落地。替代证据链（全部离线、可复跑）：

1. `ToolSchemaBudgetTest`：常驻 9 工具 5860 字符 ≈ **1465 tokens/次 LLM 调用**（上表），20k 字符预算护栏（描述膨胀回归）
2. `ToolGuardrailTest` 7 例：readFile 分页/硬上限/越界/空文件、downloadResource 协议护栏
3. `./mvnw -Dgroups=unit test`：**44 用例全绿**（原 36 + 新增 8；含 ToolSearchToolUnitTest 9 例——dynamic 组件仍完整可用）
4. 落地清单：`yuai.tool-routing.exposure-mode`（默认 `full`）· `ToolCallAgent` 构造器门控（full 只注册 searchMemory）· 路由逻辑整体抽入 `routeToolsForCurrentStep`（dynamic 专用，源码保留为教学素材）· `ToolVectorRouter.init()` 在 full 下旁路（不再构建索引、不再依赖 tool_router_meta）· `schema-version` 2→3 · 6 个工具描述重写 + 3 处护栏 · CLAUDE.md / 本文档同步

**未做的度量（诚实记录）**：没有端到端 A/B 数字（工具选择准确率、每任务 token、p50/p95 延迟）——`AgentRunMetrics`（§4.2）未实现。日后要补时开关与设计都已在位（§5-A / §4.2），预计 20 场景 × 2 模式 ≈ 40~60 分钟真实 LLM 开销。

**风险（未验证项）**：纯聊天误触发（现在由机制保证，full 后靠描述 + 系统提示）；"找文件/搜索"类场景是否偏爱 terminal 替代 readFile/searchWeb——这两项原本靠 9.4 第 4 步场景验证，现降级为已知未验证风险。

---

## 附：参考来源

- Anthropic《Writing effective tools for agents》：<https://www.anthropic.com/engineering/writing-tools-for-agents>
- Claude Code 工具参考（官方文档）：<https://code.claude.com/docs/zh-CN/tools-reference>
- OpenHands（CodeAct Agent，最小工具集设计）：<https://github.com/All-Hands-AI/OpenHands>
- Manus《Context Engineering for AI Agents: Lessons from Building Manus》：<https://manus.im/blog/Context-Engineering-for-AI-Agents-Lessons-from-Building-Manus>
