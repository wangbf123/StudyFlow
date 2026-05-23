# StudyFlow

StudyFlow 是一个面向 Java 后端实习面试准备的智能学习资料处理与 RAG 知识库问答平台。项目覆盖资料上传、异步解析、文本分片、全文检索、RAG 问答、引用溯源、限流、对象存储和前端演示，重点展示一个后端项目从数据入库到检索问答的完整链路。

## 项目结构

```text
StudyFlow-project
├── studyflow       # Spring Boot 后端
└── studyflow-web   # Vue 3 演示前端
```

## 核心功能

- 用户注册、登录、退出登录和当前用户查询
- 知识库创建、查询、修改和删除
- PDF、TXT、Markdown 学习资料上传
- MinIO 对象存储保存原文件
- 文档异步解析、文本清洗、分片入库
- 文档分片同步写入 Elasticsearch
- 按知识库维度进行全文检索
- 文件 MD5 秒传和解析结果复用
- Redisson 分布式锁避免并发重复解析
- Redis 固定窗口限流保护上传、搜索和问答接口
- RAG 检索增强问答，支持引用来源追踪
- SSE 流式问答输出
- Vue 3 前端演示知识库、上传、检索、问答和引用查看

## 技术栈

- 后端：JDK 21、Spring Boot 3.3、MyBatis-Plus、Sa-Token、Flyway、Knife4j
- 存储：MySQL 8、Redis 7、MinIO、Elasticsearch 8
- 并发与任务：Spring Async、Redisson、可切换 RocketMQ 配置
- 文档处理：PDFBox、Markdown/TXT 解析、文本分片
- AI 问答：Mock AI、OpenAI-compatible API Client、RAG Prompt Builder、SSE
- 前端：Vue 3、Vite、Element Plus、Axios、lucide-vue-next
- 部署依赖：Docker Compose

## 本地启动

### 1. 启动后端依赖

```powershell
cd studyflow
docker compose up -d mysql redis minio elasticsearch
```

### 2. 启动后端

```powershell
mvn spring-boot:run
```

后端接口文档：

```text
http://localhost:8080/doc.html
```

### 3. 启动前端

```powershell
cd ../studyflow-web
npm install
npm run dev
```

前端页面：

```text
http://localhost:5173
```

## AI 配置

默认使用 `mock` 模式，不需要 API Key，也可以完整演示 RAG 检索、问答、引用溯源和流式输出。

接入真实大模型时，通过环境变量配置，不要把 API Key 写进代码或配置文件：

```powershell
$env:STUDYFLOW_AI_PROVIDER="openai-compatible"
$env:STUDYFLOW_AI_BASE_URL="https://api.example.com"
$env:STUDYFLOW_AI_API_KEY="your-api-key"
$env:STUDYFLOW_AI_MODEL="your-model"
mvn spring-boot:run
```

## 面试讲法

这个项目不要只讲“我做了一个 AI 问答平台”，更适合讲成：

> 我做了一个学习资料知识库系统。用户上传资料后，后端会把文件存到 MinIO，同时异步解析文本、清洗分片并落库，再把分片同步到 Elasticsearch。问答时先按知识库检索相关分片，把上下文和用户问题组装成 RAG Prompt 调用大模型，最后保存问答记录和引用来源，前端通过普通接口和 SSE 流式接口进行演示。

面试官常追问的重点：

- 为什么上传后要异步解析
- 文件 MD5 秒传如何实现
- Redisson 锁解决了什么并发问题
- Redis 限流的 key 如何设计
- 文本分片大小和 overlap 为什么这样取
- Elasticsearch 检索结果如何进入 RAG Prompt
- 为什么必须保存 citation 引用来源
- SSE 和普通 HTTP 返回有什么区别
- Long 雪花 ID 为什么要序列化成字符串给前端
- 如果数据量变大，解析任务、索引同步和问答链路如何优化

## 说明

仓库不包含本地编译产物、依赖缓存、运行日志和任何真实 API Key。真实密钥请使用环境变量或本地私有配置管理。
