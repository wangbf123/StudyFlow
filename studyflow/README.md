# StudyFlow Backend

本目录包含 StudyFlow 的 Spring Boot 后端、数据库迁移、Docker Compose 基础设施、OCR 服务和验证脚本。系统负责账号与权限、知识库、文件上传、文档解析、搜索索引、RAG 问答、引用追踪、质量评估以及学习 Agent 编排。

总体设计见 [项目首页](../README.md)。当前能力以 [个人知识工作区](docs/guides/personal-workspace.md)、[知识图谱与一致性](docs/agent/personal-knowledge-architecture.md)、[四层十二模块 Agent](docs/agent/harness-architecture.md) 和 [验证报告](docs/harness-verification.md) 为准。数据库由 Flyway 增量升级到 V15。

## 运行组件

| 组件 | 默认端口 | 用途 |
| --- | ---: | --- |
| Spring Boot | 8080 | REST API、SSE、任务编排 |
| MySQL | 3307 | 业务数据、任务状态、消息、引用和 Agent 轨迹 |
| Redis | 6379 | 令牌桶限流、Redisson 分布式锁 |
| MinIO | 9000 / 9001 | 文档对象、上传分片、管理控制台 |
| Elasticsearch | 9200 | 文档分片 BM25 / 向量 / RRF 检索 |
| Neo4j（可选） | 7474 / 7687 | 带版本的知识关系投影；默认使用 MySQL 规范查询 |
| RocketMQ NameServer | 9876 | 解析任务路由 |
| RocketMQ Broker | 10909 / 10911 | 解析任务消息 |
| PaddleOCR | 8866 | 扫描页与图片识别 |

## 快速启动

### 1. 启动完整依赖

```powershell
docker compose up -d mysql redis minio elasticsearch rocketmq-namesrv rocketmq-broker
```

处理扫描 PDF 或图片时再启动 OCR：

```powershell
docker compose up -d --build paddleocr
Invoke-RestMethod http://localhost:8866/health
```

### 2. 启动后端

```powershell
mvn spring-boot:run
```

接口文档：`http://localhost:8080/doc.html`。

### 3. 使用本地异步模式

如果不需要 RocketMQ，可以只启动四个基础服务：

```powershell
$env:STUDYFLOW_MQ_MODE="async"
docker compose up -d mysql redis minio elasticsearch
mvn spring-boot:run
```

`rocketmq` 模式适合验证消息发布与消费链路，`async` 模式适合资源较少的本地环境。

## 配置分组

`src/main/resources/application.yml` 中的项目配置统一放在 `studyflow` 前缀下：

| 配置 | 作用 |
| --- | --- |
| `studyflow.mq` | 任务执行模式和解析 Topic |
| `studyflow.minio` | 对象存储地址、凭据和 Bucket |
| `studyflow.document` | 分片大小和重叠字符数 |
| `studyflow.ocr` | OCR 开关、地址、超时、触发阈值和最大页数 |
| `studyflow.ai` | AI 提供方、模型、超时和温度 |
| `studyflow.embedding` | 向量提供方、模型、维度和批量大小 |
| `studyflow.rag` | topK、上下文预算、候选规模和融合权重 |
| `studyflow.agent` | 持久化任务、调用预算、节点超时和历史上限 |
| `studyflow.harness` | Token 预算、模型路由、MCP、OTLP 与可选沙箱 |
| `studyflow.graph` | 可选 Neo4j 连接与关系投影 |
| `studyflow.rate-limit` | 不同接口的令牌桶容量和恢复窗口 |

真实 AI 服务配置示例：

```powershell
$env:STUDYFLOW_AI_PROVIDER="openai-compatible"
$env:STUDYFLOW_AI_BASE_URL="https://api.example.com/v1"
$env:STUDYFLOW_AI_API_KEY="your-api-key"
$env:STUDYFLOW_AI_MODEL="your-chat-model"

$env:STUDYFLOW_EMBEDDING_PROVIDER="openai-compatible"
$env:STUDYFLOW_EMBEDDING_BASE_URL="https://api.example.com/v1"
$env:STUDYFLOW_EMBEDDING_API_KEY="your-api-key"
$env:STUDYFLOW_EMBEDDING_MODEL="your-embedding-model"
$env:STUDYFLOW_EMBEDDING_DIMENSION="1024"
```

## 文档处理状态机

```text
document.parse_status: PENDING -> PROCESSING -> SUCCESS
                                      |          ^
                                      v          |
                                    FAILED <- RETRY

parse_task.status: PENDING -> PROCESSING -> SUCCESS
                                |
                                v
                            RETRYING -> PROCESSING
                                |
                                v
                              FAILED
```

上传完成后会创建 `document` 和 `parse_task`。任务执行器获取文件 MD5 对应的 Redisson 锁，再检查是否存在可复用结果。如果没有，则读取 MinIO 对象、解析文本、切分、生成向量、保存分片、生成摘要并建立 Elasticsearch 索引。

## 解析策略

| 文件类型 | 处理方式 |
| --- | --- |
| TXT | UTF-8 文本读取 |
| Markdown | UTF-8 文本读取并保留原始结构文本 |
| 文本型 PDF | PDFBox 按页抽取 |
| 扫描 PDF | 低文本页调用 PaddleOCR |
| 混合 PDF | 文本页使用 PDFBox，扫描页使用 PaddleOCR |
| JPG / JPEG / PNG / WebP | PaddleOCR |

PDF 不是简单地整份调用 OCR。解析器先按页抽取文本，计算有效字符数，并检测页面资源中是否存在栅格图像。只有达到低文本页条件时才调用 OCR，降低延迟和资源消耗。

## 混合检索

搜索服务先从 Elasticsearch 获取 BM25 候选，再根据配置生成问题向量。向量候选通过数据库中带 embedding 的分片计算归一化余弦相似度，最后与轻量重排分数组合。

```text
finalScore = weightedAverage(
  bm25Weight  * normalizedBm25,
  vectorWeight * cosineSimilarity,
  rerankWeight * lexicalRerank
)
```

某个候选缺少兼容向量时，不会把空信号直接记为零分，而是按实际存在的信号重新归一化。embedding 的模型名和维度也参与兼容性判断，避免模型升级后错误比较不同向量空间。

## RAG 可信链路

`QaService` 负责会话、消息、检索和生成的事务边界：

1. 校验知识库归属并创建或加载会话。
2. 保存用户消息。
3. 使用 `RagQueryRewriter` 生成检索查询。
4. 使用 `RagRetriever` 取得候选分片。
5. 使用 `RagPromptBuilder` 按 `topK` 和 `max-context-chars` 选择上下文。
6. 调用 `AiClient.chat` 或 `AiClient.streamChat`。
7. 使用 `RagAnswerValidator` 校验模型引用。
8. 保存模型消息与有效 `qa_citation`。
9. 返回答案、模型信息、耗时、token、检索查询和引用统计。

SSE 接口事件：

```text
message_start
delta
metadata
citations
message_end
error
```

## 分片上传协议

### 初始化

```http
POST /api/uploads/init
Content-Type: application/json

{
  "knowledgeBaseId": 1,
  "fileName": "example.pdf",
  "fileType": "pdf",
  "fileSize": 10485760,
  "fileMd5": "optional-full-file-md5",
  "chunkSize": 5242880,
  "totalParts": 2
}
```

### 上传分片

```http
POST /api/uploads/{uploadId}/parts?partNumber=1&partMd5={md5}
Content-Type: multipart/form-data
```

### 查询进度

```http
GET /api/uploads/{uploadId}/parts
```

### 完成合并

```http
POST /api/uploads/{uploadId}/complete
```

服务端会检查分片序号、数量、大小和 MD5，再通过 MinIO Compose 合并。完成接口使用分布式锁，可安全应对重复点击和并发请求。

## Agent 执行模型

Agent 每轮调用模型得到一个结构化决策：

```json
{
  "action": "tool",
  "tool": "search_knowledge",
  "arguments": {
    "query": "Redis 持久化",
    "topK": 5
  },
  "summary": "检索相关证据"
}
```

或者：

```json
{
  "action": "final",
  "finalAnswer": "根据证据整理的答案 [C123]",
  "summary": "完成资料归纳"
}
```

工具执行发生在服务端，模型不会获得数据库、MinIO 或 Elasticsearch 的直接连接。每个工具都接收由服务端创建的 `userId` 和 `knowledgeBaseId` 上下文，并再次调用领域服务完成权限验证。

## API 清单

```text
auth       /api/auth/*
knowledge  /api/knowledge-bases/*
document   /api/documents/*
upload     /api/uploads/*
task       /api/tasks/*
search     /api/search
qa         /api/qa/*
agent      /api/agent/*
```

请求和响应的完整字段、校验约束以及示例请以 Knife4j 页面和源码 record 定义为准。

## 数据库迁移

```text
src/main/resources/db/migration/
├── V3__qa_rag_tables.sql
├── V4__chunk_upload_tables.sql
├── V5__rag_embedding_columns.sql
├── V6__learning_agent_tables.sql
└── V7__ocr_document_metadata.sql
```

Flyway 在应用启动时自动执行缺失版本。迁移脚本负责问答引用、分片上传、embedding、Agent 轨迹以及 OCR 元数据的增量演进。

## 验证命令

```powershell
mvn test
mvn -DskipTests package
powershell -ExecutionPolicy Bypass -File scripts\smoke-v4.ps1
```

OCR 独立验证：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\ocr-service-demo.ps1 -File "C:\path\to\scan.png"
```

JMeter 场景：

```text
scripts/jmeter-studyflow-basic.jmx
```

## 包结构

```text
com.studyflow
├── agent       Agent API、服务、工具注册、执行轨迹
├── ai          摘要与聊天 Client、Mock、兼容协议、流式事件
├── auth        注册登录与 BCrypt 校验
├── common      统一响应、业务异常、全局异常处理
├── config      Sa-Token、CORS、Redis 令牌桶、线程池、Jackson
├── document    文档元数据、分片、上传入口和解析器
├── embedding   向量生成、序列化和余弦计算
├── kb          知识库领域逻辑
├── mq          RocketMQ 与 Spring Async 发布器
├── ocr         OCR HTTP 客户端和配置
├── qa          会话、消息、问答、引用和评估
├── rag         查询改写、召回、Prompt、重排和答案校验
├── search      Elasticsearch 索引与混合排序
├── storage     MinIO 对象读写、复制和合并
├── task        解析任务状态机、重试和任务执行
├── upload      分片上传会话与分片记录
└── user        用户实体与数据访问
```

## 部署注意事项

- Docker Compose 中的口令只用于本地环境。
- 外部 AI 和 embedding 密钥必须通过环境变量或密钥管理系统注入。
- 公网部署前应开启 Elasticsearch 安全认证、MinIO TLS、数据库最小权限和反向代理 HTTPS。
- 多实例部署时需要共享 Redis、MySQL、MinIO、Elasticsearch 和消息系统。
- OCR 建议作为独立可伸缩服务部署，并根据 CPU/GPU 资源设置并发限制。
- 向量规模扩大后应替换当前数据库扫描方案，避免查询延迟随分片数量线性增长。
