# StudyFlow 技术栈知识点与亮点

这份文档按技术栈拆解 StudyFlow 项目在面试中可能涉及的知识点、项目亮点、常见追问和推荐回答。目标是帮助你把这个项目讲成一个完整的 Java 后端项目，而不是简单说成“调了一个 AI 接口”。

## 总览

StudyFlow 当前链路：

```text
用户登录
-> 创建知识库
-> 上传资料
-> MinIO 保存原文件
-> MySQL 保存文档元数据
-> 创建 parse_task
-> 异步解析文件
-> 文本清洗与分片
-> document_chunk 入库
-> Elasticsearch 建立分片索引
-> 用户提问
-> ES 召回相关分片
-> RagPromptBuilder 组装 Prompt
-> AiClient 调用 DeepSeek 或 Mock
-> qa_message 保存问答记录
-> qa_citation 保存引用来源
-> Vue3 前端展示回答、流式输出和引用原文
```

项目覆盖的后端方向：

```text
Spring Boot
MySQL 表设计
MyBatis-Plus
Redis
Redisson 分布式锁
接口限流
Elasticsearch
MinIO 对象存储
异步任务
文件解析
RAG
DeepSeek / OpenAI-compatible API
SSE 流式输出
Docker Compose
前后端联调
```

重要程度：

```text
S 级：必须掌握，简历上写了就很可能被问。
A 级：高频考点，会讲清楚很加分。
B 级：项目加分项，能体现工程意识。
C 级：了解即可，不建议主动讲太深。
```

## 1. Spring Boot 3

重要程度：S

### 项目涉及的知识点

```text
RESTful API
Controller / Service / Mapper 分层
依赖注入
application.yml 配置
配置属性绑定
全局异常处理
拦截器
过滤器
异步线程池
Multipart 文件上传
SSE 流式响应
Spring Boot 自动配置
```

### 对应模块

```text
auth        登录注册
kb          知识库
document    文档上传和查询
task        解析任务
search      搜索
qa          问答
ai          AI 抽象
rag         RAG 逻辑
config      配置类
```

### 项目亮点

#### 亮点 1：清晰分层

```text
Controller 只负责接收请求和返回响应。
Service 负责业务逻辑。
Mapper 负责数据库访问。
```

面试说法：

```text
项目采用 Controller、Service、Mapper 分层。Controller 保持轻量，
业务逻辑集中在 Service 层，数据库操作通过 MyBatis-Plus Mapper 完成。
```

#### 亮点 2：统一响应和异常处理

相关类：

```text
Result<T>
BusinessException
GlobalExceptionHandler
```

面试说法：

```text
我做了统一响应结构和全局异常处理。业务异常通过 BusinessException 抛出，
由 GlobalExceptionHandler 统一转换成前端可识别的 code、message、data。
```

#### 亮点 3：CORS 和预检处理

相关类：

```text
CorsConfig
CorsPreflightFilter
```

面试说法：

```text
前端接入后遇到过浏览器带 Authorization 请求头时的 CORS 预检问题。
因为浏览器会先发 OPTIONS 请求，如果后端不正确处理，前端会显示 Network Error。
我通过全局 CORS 配置和 OPTIONS 预检过滤器解决了这个问题。
```

#### 亮点 4：Long ID 序列化成 String

相关类：

```text
JacksonConfig
```

面试说法：

```text
MyBatis-Plus 的雪花 ID 是 19 位 Long，超过 JavaScript 安全整数范围。
前端拿到 JSON 后会出现精度丢失，导致 knowledgeBaseId 传回后端时查不到数据。
我通过 Jackson 全局配置把 Long 序列化成 String，解决前后端 ID 精度问题。
```

### 常见追问

**Q：Spring Boot 在项目里主要做了什么？**

答：

```text
主要负责 Web API、配置管理、依赖注入、文件上传、异常处理、拦截器、过滤器和异步线程池。
项目核心模块都通过 Spring Bean 管理，比如 DocumentService、QaService、SearchService、AiClient 等。
```

## 2. MyBatis-Plus

重要程度：A

### 项目涉及的知识点

```text
BaseMapper
实体类映射
@TableName
@TableId
@TableField
LambdaQueryWrapper
自动填充 createTime / updateTime
雪花 ID
```

相关类：

```text
UserMapper
KnowledgeBaseMapper
DocumentMapper
DocumentChunkMapper
ParseTaskMapper
QaSessionMapper
QaMessageMapper
QaCitationMapper
MybatisPlusMetaHandler
```

### 项目亮点

#### 亮点 1：雪花 ID

配置：

```yaml
mybatis-plus:
  global-config:
    db-config:
      id-type: assign_id
```

面试说法：

```text
项目使用 MyBatis-Plus 的 assign_id 生成全局唯一 ID，不依赖数据库自增，
更适合分布式扩展。但也带来了前端 JS 精度问题，所以后端统一把 Long ID 序列化为 String。
```

#### 亮点 2：自动填充时间

相关类：

```text
MybatisPlusMetaHandler
```

作用：

```text
insert 自动填充 createTime/updateTime
update 自动填充 updateTime
```

面试说法：

```text
公共字段不在业务代码里手动设置，而是通过 MyBatis-Plus MetaObjectHandler 统一填充，
减少重复代码，也避免漏填。
```

#### 亮点 3：Lambda 查询避免硬编码字段

示例：

```java
Wrappers.<DocumentEntity>lambdaQuery()
    .eq(DocumentEntity::getUserId, userId)
```

面试说法：

```text
我用 LambdaQueryWrapper 构造查询条件，避免直接写字符串字段名，
字段重构时更安全。
```

### 常见追问

**Q：MyBatis-Plus 和 MyBatis 有什么区别？**

答：

```text
MyBatis-Plus 是 MyBatis 的增强工具，提供 BaseMapper、条件构造器、分页、自动填充、ID 生成等能力。
项目里常规 CRUD 使用 BaseMapper，复杂业务逻辑仍然放在 Service 层控制。
```

## 3. MySQL

重要程度：S

### 项目涉及的知识点

```text
表结构设计
一对多关系
业务状态字段
索引设计
任务状态流转
问答历史建模
引用来源建模
Flyway 迁移
```

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

### 表关系

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

### 项目亮点

#### 亮点 1：业务表拆分合理

```text
document 存文档元数据
document_chunk 存解析后的文本分片
parse_task 存异步解析任务
qa_message 存问答消息
qa_citation 存回答引用来源
```

面试说法：

```text
我没有把所有内容塞进一张表，而是按业务职责拆表。
文件元数据、分片、解析任务、问答记录、引用来源分别建模，后续扩展和查询都更清晰。
```

#### 亮点 2：任务状态设计

`parse_task` 状态：

```text
PENDING
PROCESSING
SUCCESS
RETRYING
FAILED
```

关键字段：

```text
retry_count
max_retry_count
error_message
started_time
finished_time
```

面试说法：

```text
解析任务不是只存一个结果，而是完整记录任务生命周期。
这样前端可以展示进度，后端也能定位失败原因并支持重试。
```

#### 亮点 3：RAG 引用来源落库

`qa_citation` 保存：

```text
message_id
document_id
chunk_id
chunk_index
document_name
content_preview
score
```

面试说法：

```text
每条 AI 回答都会保存它引用了哪些文档分片。
这样用户可以查看答案依据，也方便排查模型幻觉和召回质量问题。
```

### 常见追问

**Q：为什么 qa_session、qa_message、qa_citation 要拆开？**

答：

```text
qa_session 表示一次会话，qa_message 表示会话里的用户问题和 AI 回答，
qa_citation 表示某条 AI 回答引用的文档分片。
拆开后可以支持多轮问答、历史记录、重新生成、引用溯源等功能。
```

## 4. Redis

重要程度：A

### 项目涉及的知识点

```text
固定窗口限流
Redis key 设计
计数器
过期时间
用户维度限流
IP 维度兜底
```

相关类：

```text
RateLimitInterceptor
RateLimitProperties
RateLimitConfig
```

### 项目亮点

#### 亮点 1：固定窗口限流

限流接口：

```text
/api/documents/upload
/api/search
/api/qa/ask
/api/qa/retrieve
```

面试说法：

```text
上传、搜索、问答都是高成本接口，所以我基于 Redis 做了固定窗口限流。
每个窗口内对用户请求次数计数，超过阈值返回 429。
```

#### 亮点 2：按用户限流

限流 key 类似：

```text
studyflow:rate:/api/qa/ask:u123:window
```

面试说法：

```text
如果用户已登录，就按 userId 限流；如果没登录，就按 IP 做兜底。
这样能避免单个用户高频调用上传或问答接口。
```

### 常见追问

**Q：固定窗口限流有什么缺点？**

答：

```text
固定窗口实现简单，但窗口边界可能出现流量突刺。
比如上一分钟最后一秒和下一分钟第一秒都打满请求，短时间内流量可能翻倍。
如果要更平滑，可以换滑动窗口或令牌桶。
```

## 5. Redisson 分布式锁

重要程度：S

### 项目涉及的知识点

```text
分布式锁
锁粒度设计
tryLock
leaseTime
防止重复上传
防止重复解析
```

锁 key：

```text
studyflow:upload:md5:{md5}
studyflow:parse:md5:{md5}
```

### 项目亮点

#### 亮点 1：MD5 维度锁

面试说法：

```text
我没有对整个上传接口加全局锁，而是按文件 MD5 加锁。
不同文件可以并发上传，只有相同文件才会互斥。
```

#### 亮点 2：解决并发重复解析

并发问题：

```text
两个用户同时上传同一个文件
两个请求都发现数据库还没有成功解析结果
两个请求都开始解析
```

Redisson 解决后：

```text
同一个 MD5 同一时间只有一个请求能进入上传或解析核心逻辑
```

面试说法：

```text
这个锁主要解决查库和写库之间的并发竞态，避免相同文件重复上传、重复分片、重复写 ES。
```

### 常见追问

**Q：如果服务宕机，锁会不会一直不释放？**

答：

```text
Redisson 支持 leaseTime，项目里 tryLock 设置了锁过期时间。
即使服务异常退出，锁也会在租约到期后释放。
```

## 6. MinIO

重要程度：A

### 项目涉及的知识点

```text
对象存储
bucket
objectKey
文件元数据
文件内容和业务数据分离
```

相关类：

```text
MinioStorageService
StorageService
MinioConfig
```

### 项目亮点

#### 亮点：文件和元数据分离

面试说法：

```text
原始文件存 MinIO，MySQL 只保存 bucket 和 objectKey。
这样避免大文件直接进入数据库，降低数据库压力，也更接近真实项目里的对象存储设计。
```

### 常见追问

**Q：为什么不用本地磁盘存？**

答：

```text
本地磁盘不利于多实例部署，文件也不方便统一管理。
MinIO 是对象存储，后续可以平滑替换成 S3 或 OSS。
```

## 7. Elasticsearch

重要程度：S

### 项目涉及的知识点

```text
全文检索
倒排索引
match 查询
filter 查询
相关度 score
知识库隔离
RAG 召回
```

相关类：

```text
SearchService
DocumentChunkIndex
SearchController
SearchResponse
```

索引名：

```text
studyflow_document_chunk
```

### 项目亮点

#### 亮点 1：ES 作为 RAG 召回层

面试说法：

```text
上传解析后，文档分片会同步写入 Elasticsearch。
用户提问时不是直接调用模型，而是先从 ES 召回当前知识库下相关 chunk，
再把 chunk 放进 Prompt。
```

#### 亮点 2：权限隔离

查询时 filter：

```text
userId
knowledgeBaseId
```

面试说法：

```text
ES 查询也必须做用户和知识库隔离，否则可能搜到别人的资料。
```

#### 亮点 3：score 展示

前端召回页展示：

```text
score
included
chunk 内容
```

面试说法：

```text
我把召回结果展示出来，是为了调试 RAG 质量。
不只是黑盒问答，而是能看到模型回答前到底召回了哪些资料。
```

### 常见追问

**Q：为什么不用 MySQL LIKE？**

答：

```text
MySQL LIKE 对长文本和大量文档检索不适合，性能和相关性排序都有限。
ES 基于倒排索引，更适合全文搜索，也能返回相关度 score。
```

## 8. RAG

重要程度：S

### 项目涉及的知识点

```text
Retrieval 检索
Augmented 上下文增强
Generation 生成
topK
maxContextChars
Prompt 构造
引用溯源
```

相关类：

```text
RagRetriever
RagPromptBuilder
QaService
QaCitationEntity
```

### 项目亮点

#### 亮点 1：不是直接问模型

面试说法：

```text
用户提问后，系统不是直接把问题发给模型，而是先从知识库中召回相关文档分片，
再把这些分片作为上下文交给模型回答。
```

#### 亮点 2：上下文控制

```text
topK = 最多召回多少分片
maxContextChars = 最多放多少上下文
```

面试说法：

```text
这样可以控制 Prompt 长度，减少无关内容干扰，也降低模型调用成本。
```

#### 亮点 3：引用溯源

```text
qa_citation 保存回答引用的 document_chunk
```

面试说法：

```text
回答生成后，我会保存这条回答引用的 chunk，前端可以点开查看原文。
这能提高答案可信度，也方便排查模型幻觉。
```

### 常见追问

**Q：你这个 RAG 和普通聊天机器人有什么区别？**

答：

```text
普通聊天机器人只根据模型已有知识回答。
这个项目的 RAG 是基于用户上传的资料回答，先检索知识库分片，再结合上下文生成答案。
而且每条回答都保存引用来源，能追溯到具体文档分片。
```

## 9. DeepSeek / OpenAI-compatible API

重要程度：A

### 项目涉及的知识点

```text
模型供应商抽象
OpenAI-compatible
API Key 环境变量
Chat Completions
后端代理调用
```

相关类：

```text
AiClient
MockAiClient
OpenAiCompatibleAiClient
AiProperties
```

### 项目亮点

#### 亮点 1：AiClient 抽象

面试说法：

```text
QaService 不直接依赖 DeepSeek，而是依赖 AiClient 接口。
本地开发默认用 MockAiClient，真实演示时切换成 OpenAiCompatibleAiClient。
```

#### 亮点 2：Key 不进前端

面试说法：

```text
API Key 只配置在后端环境变量里，不放前端，避免泄露。
前端只请求自己的后端，后端完成检索和 Prompt 组装后再调用模型。
```

#### 亮点 3：已接入真实 DeepSeek

当前可配置：

```text
provider=openai-compatible
base-url=https://api.deepseek.com
model=deepseek-v4-pro
```

面试说法：

```text
项目支持 mock 和真实 DeepSeek 切换，mock 用于本地测试，DeepSeek 用于真实问答演示。
```

### 常见追问

**Q：为什么不直接在前端调 DeepSeek？**

答：

```text
前端调会暴露 API Key，而且前端无法安全完成权限校验和知识库隔离。
后端调可以先做登录鉴权、ES 召回、Prompt 构造和引用落库，再请求模型。
```

## 10. SSE 流式输出

重要程度：A

### 项目涉及的知识点

```text
SseEmitter
服务端推送
text/event-stream
delta
message_start
message_end
前端 fetch stream
```

相关位置：

```text
QaController.askStream
QaService.askStream
api.js streamAsk
```

### 项目亮点

#### 亮点：改善模型等待体验

面试说法：

```text
大模型回答耗时较长，如果等完整答案再返回，用户会觉得卡。
SSE 可以让前端边接收边展示，提高交互体验。
```

### 注意说法

你要诚实说：

```text
当前版本是后端拿到完整回答后分段通过 SSE 推送。
如果接入模型原生 streaming，可以进一步把模型 delta 直接转发给前端。
```

不要说：

```text
已经完全实现真实模型原生流式输出。
```

### 常见追问

**Q：为什么用 SSE 而不是 WebSocket？**

答：

```text
这个场景主要是服务端向客户端单向推送回答片段，SSE 更轻量，基于 HTTP，
前端实现也简单。WebSocket 更适合双向实时通信。
```

## 11. Sa-Token 登录鉴权

重要程度：A

### 项目涉及的知识点

```text
登录
token
接口拦截
StpUtil
用户态获取
权限隔离
```

相关类：

```text
AuthController
AuthService
SaTokenConfig
```

### 项目亮点

#### 亮点：登录和资源权限分开

面试说法：

```text
Sa-Token 负责判断用户是否登录。
具体资源权限，比如知识库、文档、问答会话是否属于当前用户，在 Service 层通过 userId 条件校验。
```

### 常见追问

**Q：只校验登录够吗？**

答：

```text
不够。登录只能说明用户是谁，不能说明他能访问哪个资源。
所以查询知识库、文档、问答会话时都要带 userId 条件，防止越权访问。
```

## 12. Flyway

重要程度：B

### 项目涉及的知识点

```text
数据库版本迁移
已有数据卷升级
migration 脚本
baseline
```

相关文件：

```text
V3__qa_rag_tables.sql
application.yml flyway 配置
```

### 项目亮点

面试说法：

```text
Docker init SQL 只在数据库第一次初始化时执行。
项目迭代到 V3 后新增 qa_session、qa_message、qa_citation，如果已有数据卷，init SQL 不会再执行。
所以我引入 Flyway，让应用启动时自动执行迁移脚本，保证老环境也能升级表结构。
```

## 13. Docker Compose

重要程度：B

### 项目涉及的知识点

```text
服务编排
端口映射
数据卷
本地开发环境
MySQL / Redis / MinIO / Elasticsearch
```

相关文件：

```text
docker-compose.yml
docker/mysql/init/001_schema.sql
```

### 项目亮点

面试说法：

```text
项目依赖多个中间件，如果手动安装成本高。
用 Docker Compose 可以一条命令启动 MySQL、Redis、MinIO、Elasticsearch，
方便本地开发和面试演示。
```

### 常见追问

**Q：为什么 MySQL 用 3307？**

答：

```text
为了避免和本机已有 MySQL 的 3306 冲突，所以 Docker MySQL 映射到宿主机 3307。
```

## 14. Vue3 前端

重要程度：B

### 项目涉及的知识点

```text
Vue3
Vite
Element Plus
Axios
fetch stream
文件上传
前后端跨域
localStorage token
```

### 项目亮点

面试说法：

```text
前端主要用于项目演示，支持登录、知识库管理、资料上传、搜索、RAG 问答、
流式输出和引用来源查看。核心业务逻辑还是在后端，前端只是把完整链路可视化。
```

### 常见追问

**Q：你是后端岗位，为什么做前端？**

答：

```text
为了方便演示和调试。后端接口可以通过 Knife4j 调用，但前端能更直观展示上传、解析、检索、
问答和引用溯源的完整业务闭环。
```

## 15. 文件解析 PDFBox

重要程度：B

### 项目涉及的知识点

```text
PDF 文本提取
TXT / Markdown 文本读取
文件类型判断
文本清洗
```

### 项目亮点

面试说法：

```text
项目支持 PDF、TXT 和 Markdown。解析后统一转成纯文本，再走相同的分片和索引流程。
```

### 常见追问

**Q：如果 PDF 是扫描版怎么办？**

答：

```text
当前 PDFBox 主要处理文本型 PDF。
如果是扫描版图片 PDF，需要接 OCR，比如 PaddleOCR 或云 OCR 服务。
```

## 16. Knife4j / Swagger

重要程度：C

### 项目涉及的知识点

```text
接口文档
在线调试
后端 API 展示
```

面试说法：

```text
项目集成 Knife4j，方便在没有前端时调试接口，也方便面试时展示后端 API。
```

## 17. Maven / JDK 21

重要程度：B

### 项目涉及的知识点

```text
Maven 依赖管理
Spring Boot Maven Plugin
JDK 21
打包 jar
```

面试说法：

```text
项目使用 Maven 管理依赖，通过 spring-boot-maven-plugin 打包成可运行 jar。
本地使用 JDK 21。
```

## 18. 最值得强调的 8 个亮点

### 亮点 1：完整业务闭环

```text
上传资料 -> 异步解析 -> 文档分片 -> ES 检索 -> RAG 问答 -> 引用溯源
```

### 亮点 2：不是简单调 AI API

```text
不是直接把用户问题丢给模型，而是先从知识库召回资料分片，再组装 Prompt。
```

### 亮点 3：引用来源可追溯

```text
qa_citation 保存回答引用的 document_chunk。
```

### 亮点 4：Redisson + MD5 防重复解析

```text
相同文件并发上传时，通过 MD5 维度锁避免重复解析和重复写索引。
```

### 亮点 5：MinIO + MySQL 文件存储分离

```text
大文件进对象存储，MySQL 只存元数据。
```

### 亮点 6：Redis 限流保护高成本接口

```text
上传、搜索、问答接口都有限流。
```

### 亮点 7：AiClient 抽象

```text
mock 和 DeepSeek 可以通过配置切换，业务代码不依赖具体模型。
```

### 亮点 8：真实工程问题修复

```text
CORS 预检 OPTIONS 失败
Long ID 在前端 JS 精度丢失
```

这两个可以作为“项目中遇到的问题”来讲。

## 19. 可以主动讲的踩坑

### 踩坑 1：Long ID 精度丢失

讲法：

```text
MyBatis-Plus 雪花 ID 是 19 位 Long，超过 JS 安全整数范围。
前端拿到知识库 ID 后会丢精度，导致上传文档时传回来的 knowledgeBaseId 变了。
我通过 Jackson 全局配置把 Long 序列化成 String 解决。
```

### 踩坑 2：CORS 预检失败

讲法：

```text
前端带 Authorization 请求头访问后端时，浏览器会先发 OPTIONS 预检请求。
一开始预检没有正确放行，导致前端显示 Network Error。
后来通过 CORS 配置和 Preflight Filter 统一处理 OPTIONS 请求。
```

### 踩坑 3：Docker MySQL 端口冲突

讲法：

```text
本机已有 MySQL 占用 3306，所以 Docker MySQL 映射到 3307，避免端口冲突。
```

## 20. 面试回答组织方式

面试官问项目时，不要按技术栈乱讲。

建议顺序：

```text
1. 先讲业务背景
2. 再讲完整链路
3. 再讲核心技术点
4. 再讲遇到的问题
5. 最后讲不足和优化方向
```

标准回答：

```text
这个项目是一个学习资料 RAG 知识库问答平台。
用户上传学习资料后，后端会把原文件存到 MinIO，然后创建解析任务异步解析文件，
解析出的文本会按一定长度切成 document_chunk，保存到 MySQL 并写入 Elasticsearch。
用户提问时，系统先在当前知识库中召回相关 chunk，控制 topK 和上下文长度后构造 Prompt，
再通过 AiClient 调用 DeepSeek 生成回答。回答会保存到 qa_message，引用来源保存到 qa_citation，
前端可以展示答案、流式输出和引用原文。
```

然后补技术亮点：

```text
这里面我重点做了 MD5 秒传、Redisson 分布式锁、Redis 限流、ES 检索召回、
RAG 引用溯源、SSE 流式输出和 AiClient 模型抽象。
```

## 21. 最短背诵版

```text
StudyFlow 是一个学习资料处理与 RAG 知识库问答平台。
用户上传 PDF、TXT、Markdown 后，系统会把原文件保存到 MinIO，
在 MySQL 中记录文档元数据并创建解析任务。解析任务异步执行，
提取文本后按 chunk-size 和 overlap 进行分片，分片保存到 MySQL，
同时写入 Elasticsearch 建立全文索引。

用户提问时，系统不会直接调用大模型，而是先基于 userId 和 knowledgeBaseId
从 Elasticsearch 召回相关文档分片，再根据 topK 和 maxContextChars 组装 Prompt，
通过 AiClient 调用 DeepSeek 生成回答。回答保存到 qa_message，
引用来源保存到 qa_citation，前端可以查看每条回答对应的原文分片。

项目中还使用 Redis 做接口限流，Redisson 基于文件 MD5 做分布式锁，
避免相同文件并发重复上传和解析。前端用 Vue3 做演示端，支持资料上传、
检索调试、普通问答、SSE 流式问答和引用原文查看。
```
