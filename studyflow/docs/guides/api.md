# 工作台 API 与响应契约

受保护接口使用原始 Sa-Token `Authorization` 值，前端通过同源 `/api` 调用。统一响应为 `{code,message,data}`。64 位 ID 序列化为字符串，客户端不得转为 JS Number。历史 BusinessException 保持业务 code 格式兼容；限流使用 HTTP 429，非预期服务错误使用 HTTP 500，参数窗口错误使用 HTTP 400。

## 知识库与资料

| 方法 | 路径 | 说明 |
|---|---|---|
| POST / GET | `/api/knowledge-bases` | 创建 / 列出当前用户知识库 |
| GET / PUT / DELETE | `/api/knowledge-bases/{id}` | 知识库信息及软删除 |
| GET | `/api/knowledge-bases/{id}/overview` | 同一只读事务内聚合文档、当前分片、引用及任务 |
| GET | `/api/knowledge-bases/{id}/document-page` | `size=1..100`、`beforeId`、`keyword`、`status` |
| GET | `/api/knowledge-bases/{id}/tasks` | 当前 generation 的最新 12 个任务 |
| POST | `/api/documents/upload` | multipart：knowledgeBaseId、file |
| GET | `/api/documents/{id}/chunks` | 当前版本分片 |
| POST | `/api/documents/{id}/rebuild` | 新 generation，返回主任务 ID |
| DELETE | `/api/documents/{id}` | tombstone + 持久清理任务 |
| GET / POST | `/api/tasks/{id}`、`/api/tasks/{id}/retry` | 状态 / 当前有效任务的有界手动重试 |

旧 `/api/knowledge-bases/{id}/documents` 列表仍保留兼容；工作台使用游标接口。游标按 ID 倒序，返回 `items`、`hasMore`、`nextCursor`，没有下一页时 cursor 为 null。并发上传产生的新记录在刷新第一页时出现；游标不提供跨请求事务快照。

文档增加 `generation`、`summaryStatus`、`summaryInputChars` 和 `summaryTruncated`。`parseStatus=SUCCESS` 表示主索引就绪，不保证摘要已生成。摘要状态包含 PENDING、PROCESSING、RETRYING、SUCCESS、FAILED、CANCELLED；历史未请求摘要的数据为 NOT_REQUESTED。

## 可解释检索

`GET /api/search/explain?knowledgeBaseId=...&keyword=...&mode=RRF&size=10`

| 字段 | 含义 |
|---|---|
| requestedMode / effectiveMode | 请求模式 / 本次有效模式 |
| embeddingProvider / embeddingModel | 开发 mock 或真实 provider，以及模型身份 |
| degradationReason | 无法使用 embedding 时为 EMBEDDING_UNAVAILABLE |
| lexicalCandidates / vectorCandidates | 两路召回数量 |
| discardedCandidates / verifiedCandidates | 被版本、内容、状态或权限复查过滤的数量 / 有效候选数 |
| rrfConstant / elapsedMs | 本次融合常数及服务端检索耗时 |
| hits[].result | 原有 SearchResponse 原文及分数 |
| hits[].bm25Rank / vectorRank | 原始召回列表排名，未命中为 null |
| hits[].bm25RrfContribution / vectorRrfContribution | `1/(constant+rank)`，未命中为 0 |

BM25 / VECTOR 单路模式返回该路排序；RRF 按两路贡献和排序；RRF_LEXICAL 另有词面覆盖重排。LEGACY 保留旧算法对照，未使用原生语义排名列表，不能把其说明当作 RRF 解释。候选窗口默认 200，深分页超出窗口返回参数错误；它不是资料总量限制。

## 问答与观测

问答、SSE、会话和引用接口见 [证据协议](../claim-evidence-qa.md)。SSE 终止事件为 message_end；Agent 为 run_end。没有终止事件的连接不能视为成功。

HTTP 返回 `X-Request-Id`，服务端只接受安全字符且长度不超过 64 的传入值，否则生成 UUID。请求 ID 传播到异步线程日志。诊断时提供该 ID，不提交 token、密码或完整私有文档内容。

所有新知识库端点先检查所属用户和 ACTIVE 状态，聚合 SQL 再带 user_id / knowledge_base_id 过滤。当前版本统计排除旧 generation 与已删除文档。

## 个人知识工作区与笔记

知识库创建/更新增加 `parentId`、`templateKey`、`color`；目录最多十二层，拒绝循环。父子层级用于组织，不自动扩大任何任务的权限。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET / POST | `/api/notes` | 列表按 knowledgeBaseIds 查询，支持 before、size、query；创建 revision=0 |
| GET / PUT | `/api/notes/{id}` | 读取/保存；更新必须提供当前 revision，冲突保留原数据 |
| DELETE | `/api/notes/{id}?revision=...` | 版本校验后删除 |
| POST | `/api/notes/{id}/move` | knowledgeBaseId、revision，稳定链接 ID 保留 |
| GET | `/api/notes/{id}/backlinks` | 在指定 knowledgeBaseIds 范围查询反向链接 |
| GET | `/api/notes/graph` | knowledgeBaseIds，可选 focus；有界图与投影状态 |
| GET | `/api/notes/{id}/revisions`、`/revisions/{version}` | 历史目录与历史正文 |

笔记写入包含 knowledgeBaseId、title、body、revision、tags、properties、kind。kind 支持 NOTE、CONCEPT、PROJECT、DECISION、JOURNAL、REPORT；Wiki 链接解析和版本快照与笔记原子提交。Markdown 导出由前端生成，不存在单独的笔记导出 API。

## 持久化 Agent 与 Harness 管理

当前任务通过 `POST /api/agent/runs` 创建，再按 `GET /api/agent/runs/{id}/events/stream?after=...` 订阅。任务状态与 HTTP 连接分离；取消必须显式调用 `/cancel`。SSE 中的 `agent_event` 带有序 seq，`run_end` 是终止事件。

创建字段包括 knowledgeBaseId、knowledgeBaseIds（最多八个）、sessionId、goal、skill、contextNoteId、collaboration、requestKey。主库会纳入任务范围，Skill 支持 research、compare、review、organize、coding；collaboration 默认关闭。相同 requestKey 和相同请求返回同一 run，不同内容复用同一标识返回冲突。

状态包括 QUEUED、RUNNING、RETRY_WAIT、SUCCESS、ABSTAINED、FAILED、CANCELLED、TIMED_OUT、BUDGET_EXCEEDED。终态后重试创建新 run，保留旧轨迹；成功报告可下载，或幂等保存回选定知识库。

完整路由见 [Agent API](../LEARNING_AGENT.md#api-与事件)。`GET /api/agent/architecture` 返回脱敏模块目录；记忆、MCP 绑定、运行诊断和评估字段见 [Harness 管理 API](../agent/harness-architecture.md#10-api-与验证)。模型/MCP 地址、凭据和工具白名单由服务端配置，不从创建任务请求中接受。
