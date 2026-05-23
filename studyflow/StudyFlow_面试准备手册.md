# StudyFlow 面试准备手册

本文档用于准备 Java 后端实习面试。目标不是把项目吹得很大，而是让你能稳定讲清楚：项目解决什么问题、核心链路怎么设计、用了哪些中间件、为什么这么选、如果面试官继续追问应该怎么回答。

重要程度说明：

```text
S 级：必须掌握。简历上写了就很可能被问，不会会很危险。
A 级：高频考点。Java 后端实习面试很容易问。
B 级：加分项。能讲清楚会明显提升项目质感。
C 级：拓展项。知道方向即可，不建议主动讲太深。
```

## 0. 项目一句话介绍

### 面试开场版

```text
StudyFlow 是一个面向学习资料管理和复习问答场景的 RAG 知识库平台。
用户可以上传 PDF、TXT、Markdown 学习资料，后端会把原文件存到 MinIO，
异步解析文本并切分成文档分片，将分片写入 MySQL 和 Elasticsearch。
用户提问时，系统先在当前知识库中召回相关分片，再组装 Prompt 调用 AI Client
生成回答，并保存问答历史和引用来源，保证答案可追溯。
```

### 更短版本

```text
这是一个 Spring Boot 3 实现的学习资料 RAG 知识库问答平台，核心能力包括文件上传、
异步解析、文档分片、Elasticsearch 检索、RAG 问答、SSE 流式输出和引用溯源。
```

### 不要这样说

```text
这是一个 AI 平台。
这是一个大模型项目。
这是一个智能学习系统。
```

这样太虚，面试官会马上追问模型、向量库、训练、微调，你容易被带到不熟的方向。

## 1. 项目完整链路

重要程度：S

### 你必须能画出来的主流程

```text
注册 / 登录
-> 创建知识库
-> 上传 PDF/TXT/Markdown
-> 校验文件类型
-> 计算文件 MD5
-> Redisson 分布式锁防并发重复上传
-> MinIO 保存原始文件
-> MySQL 保存 document 元数据
-> 创建 parse_task
-> 本地线程池异步解析
-> PDFBox / 文本解析器提取文本
-> TextChunker 分片
-> document_chunk 入 MySQL
-> Elasticsearch 建立分片索引
-> Mock AI 生成文档摘要
-> 用户提问
-> ES 按知识库召回 topK 分片
-> RagPromptBuilder 组装上下文
-> AiClient 调用 mock 或 openai-compatible 模型
-> qa_message 保存问答
-> qa_citation 保存引用来源
-> 前端展示答案、引用、流式输出
```

### 面试官可能问

**Q：你这个项目的核心业务流程是什么？**

答：

```text
核心流程分两段。

第一段是资料入库：用户上传资料后，我会先做登录鉴权和知识库权限校验，然后校验文件类型，
计算文件 MD5，通过 Redisson 锁避免相同文件并发重复处理。原始文件存到 MinIO，
数据库保存 document 元数据，同时创建 parse_task，由异步线程池解析文档、清洗文本、
切分 chunk，最后把 chunk 存到 MySQL 并同步写入 Elasticsearch。

第二段是 RAG 问答：用户在某个知识库提问时，后端先用 Elasticsearch 从该知识库下召回相关
document_chunk，控制 topK 和上下文最大长度后组装 Prompt，再通过 AiClient 调模型生成回答。
回答会写入 qa_message，引用的 chunk 会写入 qa_citation，前端可以查看答案来源。
```

### 追问点

- 为什么要拆成上传解析和问答两段？
- 为什么解析要异步？
- 为什么要保存引用来源？
- 为什么要把文件、元数据、分片、索引分开存？

## 2. 技术栈和各自职责

重要程度：S

| 技术 | 在项目里的作用 | 面试关键词 |
|---|---|---|
| Spring Boot 3 | 后端主框架，提供 Web API、配置、依赖管理 | 自动配置、IOC、MVC |
| MyBatis-Plus | 操作 MySQL 表 | BaseMapper、实体映射 |
| MySQL | 存结构化业务数据 | 用户、知识库、文档、任务、分片、问答 |
| Redis | 限流计数、Redisson 依赖 | 固定窗口限流 |
| Redisson | 分布式锁 | 防重复上传、防重复解析 |
| Elasticsearch | 文档分片全文检索 | RAG 召回层 |
| MinIO | 对象存储 | 原始文件存储 |
| Sa-Token | 登录鉴权 | token、拦截器 |
| Flyway | 数据库迁移 | 自动建表、版本管理 |
| PDFBox | PDF 文本解析 | 文件解析 |
| SSE | 流式问答 | SseEmitter、用户体验 |
| Vue3 | 演示端 | 登录、上传、问答、引用展示 |
| Docker Compose | 本地依赖编排 | MySQL、Redis、MinIO、ES |

### 面试官可能问

**Q：你项目里 Redis、ES、MinIO、MySQL 分别存什么？**

答：

```text
MySQL 存强结构化业务数据，比如用户、知识库、文档元数据、解析任务、文档分片、
问答记录和引用来源。

MinIO 存用户上传的原始文件，比如 PDF、Markdown、TXT。这样 MySQL 不需要保存大文件，
只保存 bucket 和 objectKey。

Elasticsearch 存文档分片索引，用于知识库内全文检索和 RAG 召回。

Redis 主要用于接口限流计数，另外 Redisson 依赖 Redis 实现分布式锁，用来控制相同 MD5 文件
的并发上传和解析。
```

## 3. 数据库设计

重要程度：S

### 核心表

```text
sys_user        用户表
knowledge_base  知识库表
document        文档元数据表
parse_task      解析任务表
document_chunk  文档分片表
ai_summary      文档摘要表
qa_session      问答会话表
qa_message      问答消息表
qa_citation     回答引用来源表
```

### 表之间关系

```text
sys_user 1 - N knowledge_base
knowledge_base 1 - N document
document 1 - N document_chunk
document 1 - 1 ai_summary
document 1 - N parse_task
knowledge_base 1 - N qa_session
qa_session 1 - N qa_message
qa_message 1 - N qa_citation
qa_citation N - 1 document_chunk
```

### 面试官可能问

**Q：为什么要有 parse_task 表？直接上传接口里解析不行吗？**

答：

```text
文档解析可能比较耗时，尤其是 PDF 文件或大文件。如果上传接口同步解析，会导致接口响应慢，
用户体验差，也容易超时。parse_task 表可以把上传和解析解耦：上传接口只负责保存文件和创建任务，
后续由异步线程池处理解析。

parse_task 还可以记录任务状态、错误信息、重试次数、开始时间和结束时间，方便前端展示解析状态，
也方便排查问题。
```

**Q：qa_session、qa_message、qa_citation 为什么拆三张表？**

答：

```text
qa_session 表示一次问答会话，比如用户在某个知识库里问一组相关问题。
qa_message 保存用户问题和 AI 回答，role 区分 USER 和 ASSISTANT。
qa_citation 保存某条 AI 回答引用了哪些 document_chunk。

这样拆的好处是职责清晰：会话、消息、引用来源分开管理。
后续如果要做多轮对话、查看历史、答案溯源、重新生成回答，都比较方便。
```

**Q：document_chunk 为什么单独一张表？**

答：

```text
因为 RAG 和全文检索都不是直接基于整篇文档做的，而是基于较小的文本分片。
分片后可以提高召回准确度，也能控制 Prompt 上下文长度。
document_chunk 保存原始分片内容，Elasticsearch 保存可检索索引，qa_citation 再引用具体 chunk。
```

## 4. 文件上传与 MinIO

重要程度：S

### 当前实现

```text
前端上传文件
-> 后端 MultipartFile 接收
-> 校验文件非空
-> 校验文件类型：pdf/txt/md/markdown
-> 读取文件字节
-> 计算 MD5
-> 判断是否有可复用解析结果
-> MinIO 保存原始文件
-> MySQL 保存 document
-> 创建 parse_task
```

### 面试官可能问

**Q：为什么文件不直接存 MySQL？**

答：

```text
原始文件可能比较大，直接存 MySQL 会增加数据库体积和 IO 压力，也不利于文件下载和横向扩展。
对象存储更适合保存大文件，MySQL 只保存文件元数据、bucket、objectKey。

这种设计也更接近真实业务：数据库负责结构化数据，对象存储负责非结构化文件。
```

**Q：MinIO 和阿里云 OSS、S3 有什么关系？**

答：

```text
MinIO 是一个兼容 S3 协议的对象存储，适合本地开发和私有化部署。
如果以后要上云，可以把 MinIO 的实现替换成 OSS 或 S3，对业务层影响比较小。
```

**Q：上传大文件怎么办？**

答：

```text
当前项目设置了 multipart 最大文件大小，适合简历项目演示。
如果生产环境支持更大文件，可以做分片上传、断点续传、上传进度、文件类型白名单、
病毒扫描和异步解析队列。
```

## 5. MD5 秒传与 Redisson 分布式锁

重要程度：S

### 当前实现

```text
上传文件时计算 MD5
-> Redisson 加锁 studyflow:upload:md5:{md5}
-> 如果已有相同 MD5 且解析成功的 document
-> 复制 document_chunk、summary、ES 索引
-> 新文档直接返回 SUCCESS
```

解析时：

```text
Redisson 加锁 studyflow:parse:md5:{md5}
-> 防止多个相同文件同时解析
-> 可复用已解析成功的结果
```

### 面试官可能问

**Q：为什么要用 MD5？**

答：

```text
MD5 用来标识文件内容是否相同。即使文件名不同，只要内容一样，MD5 就一样。
这样可以避免同一个文件重复解析、重复分片、重复调用 AI 摘要、重复写 ES。
```

**Q：为什么还要 Redisson 锁？只查数据库不行吗？**

答：

```text
只查数据库在并发情况下有竞态问题。比如两个用户同时上传同一个文件，
两个请求都查到还没有解析结果，然后都开始上传和解析。

Redisson 锁可以把相同 MD5 的上传或解析串行化，保证同一时刻只有一个线程处理相同文件。
```

**Q：锁的 key 怎么设计？**

答：

```text
上传锁：studyflow:upload:md5:{md5}
解析锁：studyflow:parse:md5:{md5}

按文件 MD5 维度加锁，这样不同文件不会互相阻塞，相同文件才会串行处理。
```

**Q：如果服务宕机锁会不会不释放？**

答：

```text
Redisson 锁有 lease time，项目里获取锁时设置了释放时间。
即使服务异常退出，锁也会在租约过期后释放，避免死锁。
```

### 容易被追问

- MD5 有没有碰撞风险？
- 分布式锁加太粗会不会影响并发？
- 如果复制分片时失败怎么办？

回答方向：

```text
MD5 碰撞理论上存在，但在普通学习资料去重场景里可以接受。
如果是强安全场景，可以换 SHA-256。

锁按 MD5 粒度，不同文件互不影响，并发粒度还可以。

复制分片失败属于事务一致性问题，目前复制在事务里完成；ES 索引失败后可以进一步引入 outbox
或补偿任务来增强最终一致性。
```

## 6. 异步解析任务

重要程度：S

### 当前实现

```text
上传接口创建 parse_task
-> 事务提交后发布任务
-> 默认 async 本地线程池处理
-> RocketMQ 代码预留
-> 解析成功：task SUCCESS，document SUCCESS
-> 解析失败：记录 error_message，增加 retry_count
```

### 面试官可能问

**Q：为什么要在事务提交后再发布任务？**

答：

```text
如果在事务提交前就发布任务，异步线程可能先查 parse_task 或 document，但数据库事务还没提交，
就会出现查不到数据的问题。

所以项目使用 TransactionSynchronization，在事务 afterCommit 后再发布解析任务，保证任务消费时
相关数据已经落库。
```

**Q：解析失败怎么处理？**

答：

```text
parse_task 有 retry_count、max_retry_count 和 error_message。
解析失败时会记录错误信息，并根据重试次数把任务置为 RETRYING 或 FAILED。
前端可以看到状态，用户也可以对失败任务发起 retry。
```

**Q：为什么默认不用 RocketMQ？**

答：

```text
为了本地开发和演示简单，默认使用 async 线程池，不需要额外启动 RocketMQ。
但项目保留了 TaskPublisher 抽象，如果要切到消息队列模式，只需要启用 RocketMQ 实现。
```

### 回答要点

```text
上传和解析解耦
任务状态可观测
失败可重试
事务提交后发布任务
本地 async 适合开发演示
MQ 适合生产扩展
```

## 7. 文本解析与分片

重要程度：A

### 当前实现

支持：

```text
PDF
TXT
Markdown
```

核心流程：

```text
根据文件类型选择解析方式
-> 提取纯文本
-> 清洗空白字符
-> 按 chunk-size 和 overlap 分片
-> 保存 document_chunk
```

配置：

```yaml
studyflow:
  document:
    chunk-size: 1000
    chunk-overlap: 120
```

### 面试官可能问

**Q：为什么要分片？**

答：

```text
一整篇文档太长，不适合直接放进 Prompt，也不适合精确检索。
分片后，ES 可以召回更细粒度的相关内容，RAG 只把相关 chunk 放进上下文，
既减少 token 消耗，也提高回答相关性。
```

**Q：chunk-overlap 是什么？为什么需要重叠？**

答：

```text
如果完全按固定长度切分，关键信息可能刚好被切到两个 chunk 的边界。
overlap 让相邻 chunk 有一部分重叠，可以降低上下文断裂的概率。
```

**Q：chunk-size 怎么选？**

答：

```text
chunk-size 太小，语义不完整，召回结果可能碎。
chunk-size 太大，召回不够精确，也会占用 Prompt 上下文。
当前项目默认 1000 字符，是为了演示和中文/英文资料都相对稳定。
生产环境可以结合文档类型、模型上下文长度和检索效果调参。
```

## 8. Elasticsearch 搜索

重要程度：S

### 当前实现

索引名：

```text
studyflow_document_chunk
```

索引字段：

```text
chunkId
documentId
knowledgeBaseId
userId
fileName
chunkIndex
content
```

查询条件：

```text
filter userId
filter knowledgeBaseId
must match content
```

### 面试官可能问

**Q：为什么不用 MySQL LIKE？**

答：

```text
MySQL LIKE 对长文本、多文档搜索不太适合，性能和相关性排序都有限。
Elasticsearch 天然适合全文检索，可以对 content 做倒排索引，返回相关度 score，
也方便后续作为 RAG 的召回层。
```

**Q：为什么查询时要过滤 userId 和 knowledgeBaseId？**

答：

```text
这是权限隔离和业务隔离。
用户只能搜索自己的知识库，不能通过 documentId 或 keyword 搜到别人的资料。
knowledgeBaseId 保证 RAG 只基于当前知识库回答，不串库。
```

**Q：ES 和 MySQL 数据不一致怎么办？**

答：

```text
当前项目在解析成功后同步写 ES，适合演示和小规模场景。
如果生产要求更高，可以引入 outbox 表或消息队列，把 ES 索引写入做成可重试的异步任务。
也可以提供重建索引接口，从 MySQL 的 document_chunk 重新同步到 ES。
```

**Q：ES score 是什么？**

答：

```text
score 是 ES 根据查询词和文档内容计算出的相关度分数。
在 RAG 里可以用 score 辅助判断哪些 chunk 更相关，也可以在前端展示检索调试结果。
```

## 9. RAG 问答设计

重要程度：S

### 当前链路

```text
用户提问
-> 校验登录态
-> 校验知识库归属
-> 保存 USER 消息
-> Elasticsearch 召回 topK 分片
-> RagPromptBuilder 组装 Prompt
-> AiClient.chat 生成回答
-> 保存 ASSISTANT 消息
-> 保存 qa_citation
-> 返回 answer + citations
```

配置：

```yaml
studyflow:
  rag:
    top-k: 6
    max-context-chars: 6000
    citation-preview-chars: 300
```

### 面试官可能问

**Q：什么是 RAG？**

答：

```text
RAG 是 Retrieval-Augmented Generation，检索增强生成。
不是直接让大模型回答，而是先从知识库中检索与问题相关的资料片段，
再把这些片段作为上下文交给大模型生成回答。

这样可以让回答更贴合用户上传的资料，也能降低模型胡编的概率。
```

**Q：你这个项目的 RAG 是怎么实现的？**

答：

```text
我先把用户上传的资料解析成 document_chunk，并把 chunk 写入 Elasticsearch。
用户提问时，系统按 userId 和 knowledgeBaseId 过滤，在 ES 中 match content 召回 topK chunk。
然后 RagPromptBuilder 根据 maxContextChars 控制上下文长度，把 chunk 内容、文档名、chunkIndex
组装成 Prompt，调用 AiClient 生成回答。
最后把回答保存到 qa_message，把引用的 chunk 保存到 qa_citation。
```

**Q：为什么答案要带引用来源？**

答：

```text
RAG 的一个核心问题是答案是否可信。只返回答案，用户不知道它依据了哪些资料。
保存 qa_citation 后，前端可以展示每个回答引用的文档名、chunkIndex、原文预览和完整原文。
这样可以提高可解释性，也方便排查召回错误或模型幻觉。
```

**Q：如果没有召回到资料怎么办？**

答：

```text
项目会直接返回兜底回答，提示当前知识库没有找到相关资料，而不是让模型凭空回答。
这样可以减少无依据回答。
```

**Q：topK 和 maxContextChars 分别解决什么问题？**

答：

```text
topK 控制最多召回多少个分片，避免把太多无关内容交给模型。
maxContextChars 控制最终 Prompt 的上下文长度，避免超过模型上下文限制，也控制调用成本。
```

## 10. AiClient 抽象和真实 API Key

重要程度：A

### 当前实现

```text
AiClient
  summarize(...)
  chat(...)

MockAiClient
  本地模拟，不需要 API Key

OpenAiCompatibleAiClient
  调用 OpenAI-compatible /chat/completions
```

配置：

```powershell
$env:STUDYFLOW_AI_PROVIDER="openai-compatible"
$env:STUDYFLOW_AI_BASE_URL="https://api.example.com/v1"
$env:STUDYFLOW_AI_API_KEY="your-api-key"
$env:STUDYFLOW_AI_MODEL="your-model"
mvn spring-boot:run
```

### 面试官可能问

**Q：为什么要抽象 AiClient？**

答：

```text
为了让业务代码不依赖具体模型供应商。
QaService 只关心 AiClient.chat，不需要知道底层是 mock、OpenAI、DeepSeek 还是其他兼容服务。
这样本地开发可以用 mock，演示或生产可以切 openai-compatible。
```

**Q：为什么 API Key 不放前端？**

答：

```text
API Key 属于敏感信息，放前端会暴露给浏览器用户。
正确做法是前端请求自己的后端，后端完成检索、Prompt 组装，再由后端携带 API Key 调模型。
```

**Q：如果模型调用失败怎么办？**

答：

```text
项目会捕获异常，保存一条 FAILED 状态的 assistant message，并记录 error_message。
前端可以展示失败信息。后续可以增加重试、降级到 mock、或者切换备用模型。
```

## 11. SSE 流式问答

重要程度：A

### 当前实现

接口：

```http
POST /api/qa/ask/stream
```

事件：

```text
message_start
metadata
delta
citations
message_end
error
```

### 面试官可能问

**Q：为什么要做流式输出？**

答：

```text
大模型回答可能耗时较长。如果等完整回答生成后一次性返回，用户会感觉卡顿。
SSE 可以让服务端逐步推送回答片段，前端边接收边展示，体验更接近 ChatGPT。
```

**Q：为什么用 SSE，不用 WebSocket？**

答：

```text
这个场景主要是服务端向客户端单向推送回答片段，SSE 更轻量，基于 HTTP，前端实现也简单。
WebSocket 更适合双向实时通信，比如聊天房间、协同编辑。
```

**Q：你现在是真流式吗？**

答：

```text
目前项目的 SSE 接口是在后端拿到完整回答后进行分段推送，主要用于演示流式链路和前端交互。
如果接入支持 streaming 的大模型 API，可以把 OpenAiCompatibleAiClient 改成读取模型原生 stream，
再直接转发 delta 给前端。
```

这个回答要诚实，别说已经完全原生流式。

## 12. Redis 限流

重要程度：A

### 当前实现

固定窗口限流：

```text
key = studyflow:rate:{path}:{subject}:{window}
increment
expire
超过 capacity 返回 429
```

配置：

```yaml
studyflow:
  rate-limit:
    rules:
      - pattern: /api/documents/upload
        capacity: 5
        window-seconds: 60
      - pattern: /api/search
        capacity: 60
        window-seconds: 60
      - pattern: /api/qa/ask
        capacity: 20
        window-seconds: 60
```

### 面试官可能问

**Q：为什么要限流？**

答：

```text
上传、搜索、问答接口都可能消耗较多资源。
上传会占用存储和解析线程，搜索会访问 ES，问答会调用 AI。
限流可以防止单个用户高频请求拖垮系统。
```

**Q：固定窗口有什么问题？**

答：

```text
固定窗口实现简单，但窗口边界处可能出现流量突刺。
比如上一分钟最后一秒和下一分钟第一秒都打满请求，短时间内实际请求量会翻倍。
更平滑的方案可以用滑动窗口或令牌桶。
```

## 13. 登录鉴权和权限隔离

重要程度：A

### 当前实现

```text
Sa-Token 登录
Authorization token
SaInterceptor 拦截 /api/**
排除注册、登录、文档接口
业务层校验 userId + resourceId
```

### 面试官可能问

**Q：只登录就够了吗？为什么业务层还要校验 userId？**

答：

```text
登录只能证明请求来自某个用户，但不能证明这个用户有权限访问某个知识库或文档。
所以业务层查询 knowledge_base、document、qa_session 时都要带 userId 条件，
避免用户通过猜 ID 访问别人的数据。
```

**Q：ES 查询为什么也要带 userId？**

答：

```text
因为 ES 里也有不同用户的分片索引。
如果只按 keyword 查，可能搜到别人的资料。
所以 ES 查询要 filter userId 和 knowledgeBaseId。
```

## 14. Flyway 数据库迁移

重要程度：B

### 当前实现

```text
src/main/resources/db/migration/V3__qa_rag_tables.sql
```

### 面试官可能问

**Q：为什么要用 Flyway？**

答：

```text
项目迭代时数据库表结构会变化。只靠 docker init SQL 对已有数据库卷不生效。
Flyway 可以在应用启动时自动执行新版本迁移脚本，保证已有环境也能补齐新表。
```

**Q：docker/mysql/init 和 Flyway 有什么区别？**

答：

```text
docker/mysql/init 主要用于第一次初始化数据库容器。
Flyway 用于应用启动后的持续版本迁移，适合项目升级。
```

## 15. 前端演示端

重要程度：B

### 当前功能

```text
登录 / 注册
知识库创建与切换
文档上传
解析状态展示
全文搜索
RAG 召回调试
普通问答
流式问答
问答历史
引用来源原文查看
```

### 面试官可能问

**Q：你是后端岗位，为什么做前端？**

答：

```text
前端主要是为了项目演示和调试，不是项目重点。
后端接口可以通过 Knife4j 调，但面试展示时前端更直观，可以完整展示上传、解析、召回、问答、
引用溯源这条链路。
```

## 16. Docker Compose

重要程度：B

### 当前依赖

```text
mysql
redis
minio
elasticsearch
rocketmq 可选
```

### 面试官可能问

**Q：为什么用 Docker Compose？**

答：

```text
项目依赖 MySQL、Redis、MinIO、Elasticsearch，手动安装成本高，而且不同机器环境容易不一致。
Docker Compose 可以一条命令拉起依赖服务，方便本地开发、演示和复现。
```

**Q：为什么 MySQL 映射到 3307？**

答：

```text
因为本机可能已经有 MySQL 占用 3306。映射到 3307 可以避免端口冲突。
```

## 17. 项目亮点总结

重要程度：S

简历上可以写：

```text
- 基于 Spring Boot 3 实现学习资料知识库平台，支持资料上传、异步解析、文本分片、
  全文检索和 RAG 问答。
- 使用 MinIO 存储原始文件，MySQL 保存文件元数据和文档分片，Elasticsearch 构建分片索引。
- 基于文件 MD5 + Redisson 分布式锁实现重复上传识别和解析结果复用。
- 设计 parse_task 异步解析任务，支持任务状态追踪、失败信息记录和重试。
- 设计 RAG 问答链路，通过 topK 和 maxContextChars 控制上下文规模，并保存 qa_citation
  实现答案引用溯源。
- 抽象 AiClient，支持 mock 与 OpenAI-compatible 模型切换。
- 基于 Redis 实现固定窗口限流，保护上传、搜索、问答等高成本接口。
- 基于 SSE 实现问答流式输出，并配套 Vue3 演示端展示完整业务闭环。
```

## 18. 面试高频问题清单

重要程度：S

### 项目整体

1. 这个项目是做什么的？
2. 核心业务流程是什么？
3. 你负责了哪些模块？
4. 项目里最有技术含量的点是什么？
5. 项目遇到过什么问题？怎么解决？

### 文件处理

1. 为什么文件存 MinIO？
2. 文件上传怎么防重复？
3. MD5 有什么用？
4. 大文件怎么处理？
5. PDF 怎么解析？

### 异步任务

1. 为什么解析要异步？
2. parse_task 表怎么设计？
3. 任务失败怎么重试？
4. 为什么事务提交后再发布任务？
5. async 和 RocketMQ 怎么取舍？

### Redis / Redisson

1. Redis 在项目里做什么？
2. Redisson 分布式锁解决什么问题？
3. 锁粒度怎么设计？
4. 锁过期怎么办？
5. 固定窗口限流有什么缺点？

### Elasticsearch

1. 为什么不用 MySQL LIKE？
2. ES 索引里存什么？
3. 怎么保证用户数据隔离？
4. ES 和 MySQL 不一致怎么办？
5. ES score 怎么用？

### RAG / AI

1. 什么是 RAG？
2. 你的 RAG 流程怎么实现？
3. 为什么要文档分片？
4. topK 和 maxContextChars 是什么？
5. 为什么要保存引用来源？
6. AI 调用失败怎么办？
7. 为什么不把 API Key 放前端？
8. AiClient 抽象有什么好处？

### SSE

1. 为什么做流式输出？
2. SSE 和 WebSocket 区别？
3. 你现在是真流式还是模拟流式？
4. 如果用户中途断开怎么办？

### 数据库

1. 表结构怎么设计？
2. qa_session、qa_message、qa_citation 为什么拆开？
3. document 和 document_chunk 什么关系？
4. 为什么用 Flyway？

## 19. 项目不足与改进方向

重要程度：A

面试官问“不足”时，不要说“没有不足”。可以这样答：

```text
目前项目为了实习面试和本地演示，优先实现了完整主链路，但还有一些可以增强的地方。

第一，RAG 召回目前主要基于 Elasticsearch BM25 全文检索，还没有接 embedding 向量检索。
后续可以做 BM25 + 向量检索的混合召回，再加 rerank。

第二，ES 索引写入目前和解析流程耦合较紧。如果生产环境要求更高，可以引入 outbox 表或消息队列，
把索引写入做成可重试的异步补偿任务。

第三，SSE 目前是拿到完整模型回答后再分段推送。如果接入支持原生 streaming 的模型，
可以把模型 delta 直接转发给前端。

第四，文档解析目前支持 PDF/TXT/Markdown，后续可以扩展 Word、PPT、Excel，并做 OCR。
```

这段很重要。它能体现你知道项目边界，没有乱吹。

## 20. 面试时建议的演示顺序

重要程度：A

### 页面演示

```text
1. 打开 http://localhost:5173
2. 注册 / 登录
3. 创建知识库：Java 面试资料
4. 上传 samples/v4-rag-demo.md
5. 等解析状态 SUCCESS
6. 搜索 Redis
7. 切到问答页
8. 输入：Redis 为什么快？StudyFlow 里 Redis 用来做什么？
9. 点“召回”，展示 chunks、score、included
10. 点“问答”，展示回答和引用
11. 点“流式”，展示 SSE 输出
12. 点引用来源，展示原文 chunk
```

### 讲解顺序

```text
先讲业务闭环，再讲技术点。

不要一上来就说 Redis、ES、MinIO。
先说用户上传资料后怎么变成可问答的知识库。
然后再解释每个中间件在链路中的职责。
```

## 21. 如果被问到真实 API Key

重要程度：A

回答：

```text
项目支持直接接 OpenAI-compatible API，但 Key 不放前端，而是通过后端环境变量配置。
前端只请求自己的后端，后端完成检索和 Prompt 组装后再调用模型。

本地开发默认使用 mock，保证没有 Key 时也能跑通流程；演示真实模型时把 provider 切到
openai-compatible 即可。
```

配置：

```powershell
$env:STUDYFLOW_AI_PROVIDER="openai-compatible"
$env:STUDYFLOW_AI_BASE_URL="https://api.example.com/v1"
$env:STUDYFLOW_AI_API_KEY="your-api-key"
$env:STUDYFLOW_AI_MODEL="your-model"
mvn spring-boot:run
```

## 22. 最后总述

你要把这个项目讲成：

```text
一个围绕“学习资料变成可检索、可问答知识库”的后端项目。
```

不要讲成：

```text
我做了一个 AI 聊天机器人。
```

项目真正的后端价值在于：

```text
文件上传
对象存储
异步任务
文档解析
分片建模
ES 检索
Redis 限流
Redisson 锁
RAG 链路
引用溯源
SSE 输出
AI Provider 抽象
Docker 编排
```

这些点都比“我调了一个大模型 API”更适合 Java 后端实习面试。
