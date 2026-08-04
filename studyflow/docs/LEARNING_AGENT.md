# StudyFlow Learning Agent

StudyFlow Learning Agent 是建立在知识库、混合检索、文档服务和 AI Client 之上的受控任务执行层。用户提供一个学习目标，Agent 根据目标和会话历史选择只读工具，获取证据后生成带来源的最终结果。

## 1. 执行协议

模型每轮必须返回一个 JSON 对象。工具调用格式：

```json
{
  "action": "tool",
  "tool": "search_knowledge",
  "arguments": {
    "query": "Redis AOF 与 RDB 的差异",
    "topK": 5
  },
  "summary": "检索知识库中的相关内容"
}
```

最终结果格式：

```json
{
  "action": "final",
  "finalAnswer": "整理后的结论，并用 [C123] 标注来源",
  "summary": "根据已读取证据完成归纳"
}
```

`AgentDecisionParser` 会从模型文本中提取 JSON，校验 action、工具名、参数对象和最终答案。非法决策会转成可记录的执行失败。

## 2. 执行循环

```text
创建或恢复 session
-> 创建 run
-> 加载最近历史消息
-> 构建系统提示、目标、历史与 observations
-> 调用模型得到 decision
   -> tool: 校验、执行、记录 step 与 source
   -> final: 保存最终 step、run 和会话消息
-> 将工具 observation 放入下一轮
-> 达到 final 或最大步骤数
```

默认最多 6 步，代码硬上限为 12。达到上限仍未产生最终答案时，运行会以明确状态结束，而不是无限循环。

## 3. 工具注册表

所有工具实现 `AgentTool`：

```text
name()
description()
inputSchema()
execute(context, arguments)
```

`AgentToolRegistry` 在应用启动时收集工具并检查重名。模型只看到工具目录，不知道实现类、数据库结构或基础设施凭据。

### search_knowledge

调用现有 `RagRetriever`，在当前用户和当前知识库执行混合检索。每个结果生成来源编号 `C{chunkId}`，并返回文档名、分片序号、得分和截断内容。

### list_documents

调用 `DocumentService.listByKnowledgeBase`，返回知识库内文档、类型、解析状态和摘要。权限检查仍由领域服务完成。

### read_document

先读取文档并确认其属于活动知识库，再返回前若干分片。最大分片数由 `max-read-chunks` 控制。

## 4. 数据模型

```text
agent_session 1 --- n agent_run
agent_session 1 --- n agent_message
agent_run     1 --- n agent_step
agent_run     1 --- n agent_source
```

| 表 | 说明 |
| --- | --- |
| `agent_session` | 多轮任务会话，绑定用户和知识库 |
| `agent_message` | 用户目标与最终回答组成的短期记忆 |
| `agent_run` | 一次完整运行，保存状态、最终答案、模型、token、步骤数和耗时 |
| `agent_step` | 每轮决策或工具调用的输入、输出、摘要、状态和耗时 |
| `agent_source` | 运行实际查看过的证据分片 |

来源表以 `(run_id, chunk_id)` 保证同一次运行不会重复保存同一证据。

## 5. SSE 事件

```text
run_start   已创建运行与会话
tool_start  即将调用一个工具
step        一个步骤已经持久化
run_end     返回完整 AgentRunResponse
error       运行失败
```

前端可以在执行过程中增量展示工具和步骤，并在 `run_end` 后显示最终答案、来源和完整轨迹。

## 6. 安全控制

- 只允许注册表中的工具。
- 工具上下文由服务端生成，模型不能指定 userId。
- 每个工具调用领域服务做知识库和文档归属检查。
- 参数和输出长度有上限。
- 相同工具与参数组合只执行一次。
- 工具结果在 Prompt 中被标记为不可信资料。
- 不保存隐式推理，只保存短动作摘要。
- 当前只开放读取类工具，不自动执行外部写操作。
- 接口使用 Redis 令牌桶控制每用户运行频率。

## 7. 配置

```yaml
studyflow:
  agent:
    enabled: true
    max-steps: 6
    max-history-messages: 8
    max-tool-result-chars: 12000
    max-sources: 12
    max-read-chunks: 8
```

环境变量：

```powershell
$env:STUDYFLOW_AGENT_ENABLED="true"
$env:STUDYFLOW_AGENT_MAX_STEPS="6"
```

## 8. API

```http
POST /api/agent/runs
POST /api/agent/runs/stream
GET  /api/agent/sessions?knowledgeBaseId={id}
GET  /api/agent/sessions/{sessionId}/runs
GET  /api/agent/runs/{runId}
```

创建运行请求：

```json
{
  "knowledgeBaseId": 1,
  "sessionId": null,
  "goal": "对比两份资料的核心观点，并给出按优先级排序的学习计划"
}
```

## 9. 扩展原则

新增只读工具时，应明确输入 schema、数据范围、最大返回量和来源提取方式。新增写工具时，还应增加：

- 用户确认或审批状态；
- 幂等键；
- 权限策略；
- 审计记录；
- 超时和重试边界；
- 可撤销或补偿机制。

后续可以增加生成测验、生成复习卡片、保存学习计划、读取学习进度等工具，并将 JSON 决策适配为原生 function calling。工具层和持久化层可以保持不变。
