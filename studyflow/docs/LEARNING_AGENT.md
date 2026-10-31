# StudyFlow Knowledge Agent

当前 Agent 已从旧的文本 JSON 决策循环重构为四层十二模块 Harness。知识范围、记忆、工具、模型和执行状态分别管理，默认服务于个人知识库研究。

完整模块、依赖版本与配置见 [Harness 架构](agent/harness-architecture.md)，操作见 [知识工作区指南](guides/personal-workspace.md)，实测见 [验证记录](harness-verification.md)。

## 执行与状态

```text
PLAN → DECIDE ⇄ TOOL → SYNTHESIZE → VERIFY → 原子发布报告
       ↑
PLAN → TEAM_DISPATCH → WAIT_CHILDREN（完成后汇总）
```

LangGraph4j 负责图拓扑与恢复；MySQL 是唯一持久化状态权威。Runtime 调度有界节点执行，Harness 选择下一步，Environment 提供获准的运行能力。节点状态、工具结果、事件和报告提交前校验租约、用户范围、取消、deadline 与预算。

协作最多三个独立研究子任务，深度为一，分担父任务选定范围的子集。子任务共享根任务调用和 Token 预算，等待阶段释放工作线程，取消级联；重试创建新 run 和新预算并保留协作模式。外部调用可能在崩溃后重放，不宣称跨供应商 exactly-once。

## 模型、上下文和记忆

- 模型调用统一经过 Model Gateway；阶段可使用不同的 OpenAI-compatible 或 Claude 原生模型。
- 工具选择使用原生 Function Calling / Tool Use，不再解析旧 `action/tool/finalAnswer` 文本协议。
- 默认输入预算 8,000、输出预留 2,000 tokens；按 O200K 估算，并另记供应商实际报告用量。
- 目标、知识范围和计划固定；旧工具正文结构化压缩，完整有界结果可分页重读。
- 偏好、知识、方法和经历按类型保留候选与召回位置，过滤范围、来源版本与有效期，支持主动忘记。
- 会话状态不等于长期记忆；记忆和子任务报告不能直接充当当前事实证据。

## 工具与知识来源

| 工具/能力 | 约束 |
|---|---|
| `knowledge_overview` / `list_documents` | 只读取选定的活动知识库 |
| `search_knowledge` / `read_document` | 复用混合检索，校验文档归属、generation 与内容 |
| `search_notes` / `read_note` | 返回当前范围内笔记及版本证据 |
| `explore_graph` | 只导航明确链接；图关系不能代替原文 |
| `search_memory` / `read_tool_result` | 限定当前范围或当前 run，结果有界 |
| `complete_plan_step` | 需要已有成功工具观察和已完成前置步骤；属于 Agent 自评 |
| MCP | 管理员配置精确端点/白名单，用户绑定知识库；文本导入后进入来源核验链 |
| `run_python` | 仅启用沙箱后的 coding Skill 可用；无网络、无宿主机挂载且有资源/时间/输出限制 |

工具 Schema、范围和角色工具集均由服务端验证；不允许模型自行指定用户身份、服务器地址或宿主机 Shell 命令。部署配置和当前支持的 MCP 协议能力见架构文档。

## 成果与核验

研究来源由当前笔记和文档原文构成。声明必须绑定白名单来源及连续引文；真实模型模式额外执行独立核验请求，发布事务中再次检查版本与权限。来源变化后，历史成果显示失效提示。

默认 Mock 只生成 `EXTRACTIVE_ONLY` 原文摘录，不代表语义研究效果。运行评估检查任务结束、范围、共享预算和证据当前性，语义完整性标为 `NOT_INDEPENDENTLY_SCORED`。系统不保存模型私有推理。

## API 与事件

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/api/agent/runs` | 幂等创建持久化任务 |
| GET | `/api/agent/runs/{id}` | 状态、预算、成果 |
| POST | `/api/agent/runs/{id}/cancel`、`/retry` | 取消或创建重试任务 |
| GET | `/api/agent/sessions`、`/sessions/{id}/runs` | 会话和运行历史 |
| GET | `/api/agent/runs/{id}/events`、`/events/stream` | 分页事件与 SSE 回放，使用 after 游标 |
| GET | `/api/agent/runs/{id}/artifact` | 下载 Markdown 报告 |
| POST | `/api/agent/runs/{id}/save-note` | 幂等保存为带引用链接的笔记 |
| GET | `/api/agent/architecture` | 十二模块、Skill 和脱敏能力目录 |
| GET / POST / DELETE | `/api/agent/memories`、`/memories/{id}` | 检查、保存、忘记记忆 |
| GET / PUT | `/api/agent/connections`、`/connections/{key}` | 连接目录与范围绑定 |
| GET / POST | `/api/agent/connections/{key}/resources`、`/import` | 浏览和显式导入文本 |
| GET | `/api/agent/runs/{id}/diagnostics`、`/evaluation` | 上下文、子任务、用量、调用轨迹和运行检查 |

SSE 使用 `agent_event` 包装带 runId/seq/type/data 的已提交事件，以 `run_end` 终止；具体事件包括 plan_created、tool_selected、tool_completed、team_dispatched、team_collected、evidence_checked 和 run_finished。断流不等于任务失败，应从已保存游标恢复。

创建请求示例；知识库和笔记 ID 应替换为当前账号实际拥有的 ID：

```json
{
  "knowledgeBaseId": "123",
  "knowledgeBaseIds": ["123", "124"],
  "goal": "对比两个领域的资料，并生成带原文引用的报告",
  "skill": "compare",
  "contextNoteId": null,
  "collaboration": true,
  "requestKey": "docs-agent-task-001"
}
```

旧 `/api/agent/runs/stream` 兼容入口保留，但推荐分离创建和订阅。原有学习 Agent 数据保留为历史记录，不自动赋予当前核验保证。请求、响应字段和知识笔记接口见 [API 指南](guides/api.md)。
