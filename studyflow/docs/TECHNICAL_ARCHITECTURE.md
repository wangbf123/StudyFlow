# StudyFlow 技术架构与关键设计

基础版本说明保留于此；当前独立摘要、分批恢复、工作台、Redis 会话与观测架构见[知识库架构 v2](architecture-v2.md)。

本文保留早期资料流水线的设计背景；Agent 的旧 JSON 决策协议已由原生 Tool Calling 和持久化 Harness 替代。当前知识工作区见 [个人知识架构](agent/personal-knowledge-architecture.md)，执行内核见 [四层十二模块 Harness](agent/harness-architecture.md)。下文涉及旧类名、旧处理顺序时，应以这些当前文档和源码为准。

本篇用于说明基础工程边界、关键数据流、核心算法和扩展方向。它重点解释各组件为什么存在、数据如何流动、失败发生时系统如何记录状态，以及规模增长后应怎样演进。

## 1. 设计目标

StudyFlow 的目标不是只完成一次 AI 调用，而是构建一套可以持续接入资料、重复索引、稳定检索并验证答案来源的知识处理系统。核心设计目标包括：

1. 原始数据可恢复：原文件保存在对象存储，解析产物可以重建。
2. 业务状态可追踪：文档、任务、消息、引用和 Agent 步骤都有明确记录。
3. 外部能力可替换：AI、embedding、消息发布和 OCR 通过接口或独立服务隔离。
4. 低成本本地运行：Mock 模型与本地异步模式保证没有外部服务时仍可验证主链路。
5. 回答必须有证据：RAG 上下文来自实际分片，引用必须通过服务端校验。
6. 多实例安全：关键并发路径使用 Redis 与 Redisson，不依赖单进程内存锁。

## 2. 分层架构

```text
表现层
  Vue 3 / Element Plus / Axios / Fetch SSE

接口层
  Spring MVC Controller / Validation / Sa-Token

应用层
  DocumentService / UploadService / TaskService / QaService / AgentService

领域与编排层
  Parser / Chunker / Retriever / PromptBuilder / AnswerValidator / AgentTool

基础设施层
  MyBatis-Plus / MySQL / Redis / Redisson / MinIO / Elasticsearch / RocketMQ

外部智能能力
  PaddleOCR / OpenAI-compatible Chat / OpenAI-compatible Embeddings
```

Controller 负责协议转换和登录用户提取，Service 负责权限、状态流转和事务边界。搜索、RAG、OCR、存储、消息和 embedding 通过独立包封装，避免把基础设施调用散落在接口代码中。

## 3. 数据所有权

### MySQL

MySQL 是业务事实来源，保存用户、知识库、文档、任务、分片、摘要、上传进度、问答记录、引用和 Agent 轨迹。即使 Elasticsearch 索引丢失，也可以从 MySQL 中的分片重建。

### MinIO

MinIO 保存原始文件和分片上传的临时对象。数据库只保存 Bucket、Object Key、文件大小、类型和 MD5，不把大二进制直接存入业务表。

### Elasticsearch

Elasticsearch 保存适合全文检索的 `DocumentChunkIndex`。它属于派生数据，目标是快速 BM25 召回，不承担文档归属和任务状态的最终判断。

### Redis

Redis 保存短生命周期的协调状态。令牌桶通过 Lua 保证原子更新；Redisson 锁用于同文件解析去重和上传合并互斥。

## 4. 文件上传设计

### 普通上传

小文件可以通过 `/api/documents/upload` 一次提交。服务端验证知识库归属、文件类型和大小后保存到 MinIO，并创建文档与解析任务。

### 分片上传

大文件使用 `/api/uploads` 协议：

```text
init
-> 返回 uploadId、chunkSize、totalParts、已上传分片
-> 客户端只上传缺失 part
-> 每个 part 保存到 MinIO 临时路径
-> 数据库记录 partNumber、size、md5、objectKey
-> complete 检查完整性
-> MinIO Compose 生成最终对象
-> 创建文档和解析任务
```

断点续传由客户端本地保存 `uploadId`，再次选择同一文件时查询服务端进度。服务端不信任客户端进度，而是以 `upload_part` 表为准。

### 幂等与并发

- `upload_session.upload_id` 唯一。
- `upload_part(upload_id, part_number)` 唯一。
- 重复上传同一分片会更新已有记录，不产生重复序号。
- 完成阶段使用 `studyflow:upload:complete:{uploadId}` 分布式锁。
- 会话已完成时再次调用 complete，可以返回已存在的最终结果。

## 5. 解析流水线

`DocumentParseService` 是解析任务的核心编排器。主要步骤如下：

1. 读取并校验 `parse_task` 状态。
2. 将任务和文档标记为处理中。
3. 获取 `studyflow:parse:md5:{fileMd5}` 分布式锁。
4. 查找同 MD5 的已成功文档，存在时复制分片、摘要和向量。
5. 从 MinIO 读取原始对象。
6. 使用 `DocumentTextParser` 生成 `ParsedDocument`。
7. 使用 `TextChunker` 生成带页码的 `ParsedChunk`。
8. 批量调用 `EmbeddingClient`。
9. 保存 `document_chunk`。
10. 调用 `AiClient.summarize` 保存摘要。
11. 将分片写入 Elasticsearch。
12. 更新文档和任务为成功。

异常会进入 `markFailed`：增加重试次数，记录错误消息，根据最大重试次数进入 `RETRYING` 或 `FAILED`，并同步文档解析状态。

## 6. PDF 与 OCR 路由

`DocumentTextParser` 通过内容特征决定是否使用 OCR，而不是只看文件扩展名。

### PDFBox 快速路径

每一页通过 `PDFTextStripper` 单独提取，并启用位置排序。系统统计字母和数字组成的有效字符数，以避免空格、换行或控制字符误判为有效内容。

### OCR 触发条件

页面同时满足以下条件时进入 OCR 候选：

- 有效字符数低于 `min-text-chars-per-page`；
- PDF 页面资源中存在栅格图像。

资源检测会递归检查 `PDFormXObject`，并限制递归深度，防止异常 PDF 造成无界遍历。

### 合并策略

OCR 服务返回页码、文本和置信度。服务端按页码替换对应低文本页面，其余页面保留 PDFBox 结果。最终文档根据 OCR 页数标记为 `PDFBOX`、`PADDLE_OCR` 或 `HYBRID`。

这种策略可以让数字文本 PDF 保持较低延迟，同时让扫描页具备可搜索文本，并且不会丢失来源页码。

## 7. 文本切分

固定长度切分实现简单，但需要解决语义跨边界问题。StudyFlow 使用可配置窗口和 overlap：

```text
chunk 0: [0, 1000)
chunk 1: [880, 1880)
chunk 2: [1760, 2760)
```

默认重叠 120 个字符。切分时保留页面来源、解析器和 OCR 置信度，并过滤没有有效内容的分片。后续演进可以根据 Markdown 标题、段落、表格、代码块和句子边界做结构化切分。

## 8. Embedding 设计

`EmbeddingClient` 提供单文本和批量向量生成。当前实现包括：

- `MockEmbeddingClient`：使用确定性的本地 token 哈希生成固定维度向量，便于测试和离线运行。
- `OpenAiCompatibleEmbeddingClient`：调用兼容 `/embeddings` 的外部服务。

向量通过 `EmbeddingVectorCodec` 编码后保存在 `document_chunk.embedding`。分片同时保存 `embedding_model` 和 `embedding_dim`。查询阶段只有模型名、维度和实际向量长度兼容时才计算相似度。

Mock 向量用于保证工程链路可运行，不等同于生产语义模型。真实检索质量应通过领域数据评测决定模型、维度和切分策略。

## 9. 混合检索算法

`SearchService` 的候选生成与排序步骤：

1. Elasticsearch 按用户、知识库和查询文本执行 BM25 搜索。
2. 对 BM25 候选补充数据库中的向量数据。
3. 对问题生成 embedding。
4. 在配置的扫描上限内读取当前知识库分片，计算余弦相似度并选取向量候选。
5. 按 chunkId 合并两路候选。
6. 将 BM25 原始分数除以当前候选最大值进行归一化。
7. 计算轻量 rerank 分数。
8. 对存在的信号做加权平均并排序。

候选来源会标记为 BM25、VECTOR 或 HYBRID，响应同时返回各子分数、最终分数、embedding 模型与维度，便于分析排序结果。

当前向量扫描复杂度会随分片数增长，因此配置了 `vector-scan-limit`。生产级扩展应把向量放入支持近似最近邻索引的存储中。

## 10. RAG Prompt 与上下文预算

`RagPromptBuilder` 不会把所有候选无条件拼入 Prompt。它按排序结果依次加入分片，并同时受以下限制：

- `top-k`：最大分片数量；
- `max-context-chars`：总上下文字符预算；
- 单个来源的内容和预览长度限制。

每个分片在 Prompt 中使用稳定的 `[C{chunkId}]` 编号，并附带文档名、页码和内容。模型被要求只基于提供的上下文回答，并在陈述后引用来源编号。

## 11. 答案校验与引用

模型返回文本后，`RagAnswerValidator` 提取来源编号，并与本次 Prompt 实际包含的分片比较：

- 合法编号进入 `qa_citation`；
- 重复编号去重；
- 未提供给模型的编号被拒绝；
- 引用数量受 `max-validated-citations` 限制。

系统同时统计有效与拒绝引用数量。引用详情接口会再次检查消息和分片属于当前用户，再返回完整原文，防止用户通过 citationId 越权读取其他资料。

## 12. SSE 语义

SSE 适合服务端单向持续推送，浏览器不需要维护 WebSocket 双向协议。StudyFlow 的问答事件有明确语义：

- `message_start`：服务端接受问题并开始处理；
- `delta`：新增答案内容；
- `metadata`：完整响应元数据；
- `citations`：有效来源列表；
- `message_end`：本次消息完成；
- `error`：可展示的失败信息。

前端使用 Fetch 读取流，按空行切分事件并解析 JSON。登录 token 放在 Authorization 请求头中，不依赖 EventSource 无法自定义请求头的限制。

## 13. 令牌桶限流

`RedisTokenBucketRateLimiter` 使用两个 key 保存剩余令牌和上次刷新时间。Lua 脚本在 Redis 内完成：

1. 读取当前令牌与时间戳；
2. 按经过时间计算补充量；
3. 限制到容量上限；
4. 判断请求所需令牌；
5. 扣减并写回；
6. 设置过期时间。

整个过程是一条原子脚本，不会出现多个应用实例先后读取同一旧值并重复放行的问题。限流规则按路径前缀匹配，不同业务接口可以独立设置突发容量和恢复周期。

## 14. 任务模式

### RocketMQ

上传创建任务后，生产者发送 taskId，消费者收到消息后调用 `DocumentParseService.parse`。该模式将解析工作从 API 实例中分离，适合独立扩容消费者。

### Spring Async

本地模式把 taskId 提交给受控线程池，降低基础设施要求。它保留相同的任务表和状态机，但应用退出时未完成任务需要通过状态和重试接口恢复。

两种模式共享 `TaskPublisher` 抽象，业务服务不直接依赖具体消息实现。

## 15. AI Client 抽象

`AiClient` 封装摘要、普通聊天和流式聊天。Mock 实现提供确定性响应，兼容实现负责：

- 请求体构造；
- Authorization 头；
- 超时控制；
- 普通 JSON 响应解析；
- SSE 数据行解析；
- delta 聚合；
- prompt/completion token 读取；
- 外部错误转换为业务异常。

这种抽象让 QA、摘要和 Agent 不需要了解具体模型供应商，同时便于在测试中替换为可预测实现。

## 16. Agent 安全边界

Agent 并不直接执行模型生成的任意代码，也不会把数据库连接交给模型。它使用工具注册表和结构化参数：

- 未注册工具拒绝执行；
- 参数由工具再次校验和截断；
- 用户与知识库标识由服务端上下文注入；
- 相同工具与参数组合禁止重复调用；
- 最大步骤数硬限制为 12，默认 6；
- 工具输出被视为不可信数据，Prompt 明确禁止执行资料中的指令；
- 每个步骤保存状态、耗时、输入、输出和简短摘要；
- 只读工具失败后可以把错误作为 observation 交给下一轮决策。

更完整的 Agent 数据模型与 API 见 `LEARNING_AGENT.md`。

## 17. 测试策略

当前单元测试优先覆盖容易独立验证且对系统正确性影响较大的纯逻辑：

- Agent JSON 决策解析和非法输出处理；
- OCR 分流与页面合并；
- embedding 编解码和向量兼容性；
- 余弦计算与 mock embedding 确定性；
- RAG 引用校验；
- rerank 排序特征；
- 限流拦截器的路径与用户 key；
- 全局异常到 HTTP 状态的映射。

端到端 smoke 脚本覆盖真实 HTTP、数据库、MinIO、Elasticsearch 和 SSE 组合链路。JMeter 文件用于负载与限流行为观察。

## 18. 可观测性与治理方向

业务表已保存任务耗时、AI latency、token、Agent 步骤、错误消息和引用数量，但完整生产监控还应增加：

- HTTP 请求数量、P95/P99 延迟和错误率；
- 队列堆积、任务处理时间、重试和死信数量；
- OCR 页数、识别耗时和低置信度比例；
- Elasticsearch 查询延迟和零结果比例；
- RAG 召回率、引用有效率和无上下文回答率；
- 模型 token、费用、超时和供应商错误；
- Agent 平均步骤、无效工具率、任务完成率和单位运行成本。

建议使用 OpenTelemetry 统一 traceId，将上传、任务、解析、索引、检索、模型调用和 SSE 响应串成可追踪链路。

## 19. 规模化演进

### 数据规模扩大

- embedding 迁移到原生向量索引；
- Elasticsearch 索引按租户或时间分片；
- 文档分片批量写入与批量重建；
- 热门查询缓存和结果缓存；
- 大会话归档与冷热分层。

### 并发提高

- OCR、embedding、摘要和索引拆成不同消费者组；
- 每类任务独立限流和伸缩；
- 引入超时、熔断、隔离舱和供应商降级；
- SSE 连接数监控与网关超时配置。

### 一致性增强

- 使用 Outbox 保证业务事务与消息发布的一致性；
- 为索引操作增加版本号和幂等键；
- 定期对账 MySQL 分片与 Elasticsearch 文档；
- 删除流程采用软删除、异步清理和失败补偿。

### 安全增强

- 组织、角色与文档级 ACL；
- 敏感信息检测和脱敏；
- 加密存储与 TLS；
- 工具审批和写操作审计；
- 文件病毒扫描、MIME 检查和压缩炸弹防护。
