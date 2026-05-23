# StudyFlow

StudyFlow 是一个面向学习资料管理和复习问答场景的 Java 后端项目。V4 的目标是让项目既能写进简历，也能在面试中完整演示和被追问。

## 项目定位

```text
学习资料上传
-> 异步解析
-> 文本分片
-> Elasticsearch 检索
-> RAG 知识库问答
-> 问答历史
-> 引用来源追踪
-> Vue3 演示端
```

## 技术栈

- JDK 21
- Spring Boot 3.3
- MyBatis-Plus
- MySQL 8
- Redis 7
- Redisson
- Elasticsearch 8
- MinIO
- Sa-Token
- Flyway
- Knife4j
- PDFBox
- Docker Compose
- Vue 3 + Vite + Element Plus

## V4 功能

- 用户注册、登录、退出登录
- 知识库创建、查询、修改、删除
- PDF/TXT/Markdown 文件上传
- MinIO 原文件存储
- `parse_task` 异步解析任务
- 文本清洗、文档分片、分片入库
- 文档分片同步写入 Elasticsearch
- 知识库维度全文搜索
- 文件 MD5 秒传和解析结果复用
- Redisson 分布式锁避免相同文件并发重复解析
- Redis 固定窗口限流
- RAG 检索调试接口
- 普通 RAG 问答接口
- SSE 流式问答接口
- 问答会话、问答消息、引用来源落库
- 引用来源详情接口，可查看引用分片原文
- AI Client 抽象，支持 `mock` 和 `openai-compatible`
- Vue3 演示前端

## 启动后端

### 1. 启动依赖

默认使用本地异步解析模式，不需要启动 RocketMQ。

```powershell
cd "C:\Users\29272\Documents\New project\studyflow"
docker compose up -d mysql redis minio elasticsearch
```

端口：

```text
MySQL:          localhost:3307
Redis:          localhost:6379
Elasticsearch:  http://localhost:9200
MinIO:          http://localhost:9001
MinIO user:     studyflow
MinIO password: studyflow123
```

### 2. 启动 Spring Boot

```powershell
mvn spring-boot:run
```

接口文档：

```text
http://localhost:8080/doc.html
```

### 3. 打包

```powershell
mvn -q -DskipTests package
java -jar target\studyflow-0.0.1-SNAPSHOT.jar
```

## 启动前端

```powershell
cd "C:\Users\29272\Documents\New project\studyflow-web"
npm.cmd install --cache .\.npm-cache
npm.cmd run dev
```

访问：

```text
http://localhost:5173
```

## AI 配置

默认是 Mock AI，不需要 API Key，可以完整跑通 RAG 链路。

```yaml
studyflow:
  ai:
    provider: ${STUDYFLOW_AI_PROVIDER:mock}
    model: ${STUDYFLOW_AI_MODEL:mock-rag}
```

接真实大模型时使用 OpenAI-compatible：

```powershell
$env:STUDYFLOW_AI_PROVIDER="openai-compatible"
$env:STUDYFLOW_AI_BASE_URL="https://api.example.com/v1"
$env:STUDYFLOW_AI_API_KEY="your-api-key"
$env:STUDYFLOW_AI_MODEL="your-model"
mvn spring-boot:run
```

## 核心接口

注册：

```http
POST /api/auth/register
```

登录：

```http
POST /api/auth/login
```

创建知识库：

```http
POST /api/knowledge-bases
```

上传文档：

```http
POST /api/documents/upload?knowledgeBaseId={knowledgeBaseId}
```

知识库全文搜索：

```http
GET /api/search?knowledgeBaseId={knowledgeBaseId}&keyword=Redis&page=0&size=10
```

RAG 检索调试：

```http
POST /api/qa/retrieve
```

RAG 问答：

```http
POST /api/qa/ask
```

SSE 流式问答：

```http
POST /api/qa/ask/stream
```

查看引用来源：

```http
GET /api/qa/messages/{messageId}/citations
GET /api/qa/citations/{citationId}/chunk
```

## V4 烟测

启动依赖和后端后运行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\smoke-v4.ps1
```

脚本会自动完成：

```text
注册
登录
创建知识库
上传 samples/v4-rag-demo.md
等待解析成功
RAG 召回
RAG 问答
查看引用原文
SSE 流式问答
```

成功时重点看：

```text
retrieveHitCount > 0
includedCount > 0
citationCount > 0
citationChunkLength > 0
streamEvents 包含 message_start, metadata, delta, citations, message_end
```

## V4 RAG 设计

V4 仍然先使用 Elasticsearch 做召回，不急着上向量数据库。这样项目更稳，也更适合 Java 后端实习面试。

```text
用户问题
-> 校验登录态和知识库权限
-> 保存 USER 消息
-> Elasticsearch 召回 topK 文档分片
-> RagPromptBuilder 控制 maxContextChars
-> AiClient.chat 生成回答
-> 保存 ASSISTANT 消息
-> 保存 qa_citation 引用来源
-> 普通接口一次性返回，流式接口通过 SSE 分段返回
```

关键参数：

```yaml
studyflow:
  rag:
    top-k: 6
    max-context-chars: 6000
    citation-preview-chars: 300
```

## 数据表

核心表：

```text
sys_user        用户
knowledge_base  知识库
document        文档元数据
parse_task      解析任务
document_chunk  文档分片
ai_summary      文档摘要
qa_session      问答会话
qa_message      用户问题和 AI 回答
qa_citation     回答引用来源
```

V3/V4 新增表由 Flyway 自动创建：

```text
src/main/resources/db/migration/V3__qa_rag_tables.sql
```

## 项目结构

```text
com.studyflow
  common      统一响应、业务异常、全局异常处理
  config      Sa-Token、CORS、MyBatis-Plus、限流、线程池
  auth        注册登录
  user        用户实体和 Mapper
  kb          知识库
  document    文件上传、文档记录、文档分片
  task        解析任务、任务状态、任务重试
  mq          TaskPublisher 抽象、Async/RocketMQ 实现
  storage     MinIO 对象存储
  search      Elasticsearch 分片索引与全文搜索
  ai          AI Client 抽象、Mock、OpenAI-compatible
  rag         RAG 检索、上下文构造、Prompt 构建
  qa          问答会话、消息、引用来源、流式问答
```

前端：

```text
studyflow-web
  src/App.vue      页面主逻辑
  src/api.js       Axios 和 fetch stream
  src/styles.css   页面样式
```

## 面试讲法

可以这样讲：

```text
这个项目不是直接把用户问题转发给大模型，而是先把用户上传的资料解析成文档分片。
分片保存到 MySQL，并同步写入 Elasticsearch。用户提问时，系统先在当前知识库中
召回相关分片，再控制 topK 和 maxContextChars 组装 Prompt，最后通过 AiClient
调用模型生成回答。回答和引用来源都会落库，因此能查看每个答案引用了哪些
document_chunk，减少大模型幻觉带来的不可追溯问题。
```

可以被追问的点：

- 为什么文件存 MinIO，而不是直接存 MySQL？
- 为什么要做文档分片？
- MySQL 和 Elasticsearch 分别存什么？
- 为什么 RAG 要保存引用来源？
- 如果 ES 召回不到内容怎么办？
- 如果 AI 调用失败怎么办？
- 为什么抽象 AiClient？
- topK 和 maxContextChars 如何控制？
- SSE 流式输出解决了什么体验问题？
- Redis 限流和 Redisson 分布式锁分别解决什么问题？
- MySQL 和 ES 数据不一致时怎么处理？

## 后续可选增强

- 接真实大模型 API
- 向量检索或混合检索：BM25 + embedding
- Rerank 重排
- SSE 对接真实模型的原生流式输出
- JMeter 压测和限流报告
