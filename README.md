<div align="center">

<img src=".github/assets/knowledge-cover.png" alt="StudyFlow：资料进入向量检索，答案关联原文证据的概念封面" width="100%" />

# StudyFlow

**你的知识空间：资料有序入库，检索过程可解释，回答可以回到原文。**

[![Quality](https://github.com/wangbf123/StudyFlow/actions/workflows/quality.yml/badge.svg)](https://github.com/wangbf123/StudyFlow/actions/workflows/quality.yml)
[![Java](https://img.shields.io/badge/Java-21-f59e0b?style=flat-square)](studyflow/pom.xml)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.3.7-6db33f?style=flat-square)](studyflow/pom.xml)
[![Vue](https://img.shields.io/badge/Vue-3-42b883?style=flat-square)](studyflow-web/package.json)
[![Elasticsearch](https://img.shields.io/badge/Elasticsearch-8.15.3-005571?style=flat-square)](retrieval-lab/README.md)
[![BGE](https://img.shields.io/badge/BGE-512d-8b5cf6?style=flat-square)](retrieval-lab/model-lock.json)
[![Verification](https://img.shields.io/badge/Verified_tests-85-14b8a6?style=flat-square)](studyflow/docs/highlights-verification.md)

[界面预览](#界面预览) · [项目亮点](#项目亮点) · [链路与架构](#链路与架构) · [快速开始](#快速开始) · [验证结果](#验证结果) · [文档导航](#文档导航)

</div>

---

StudyFlow 是面向个人学习与私有资料研究的知识库。PDF、TXT、Markdown 和图片经持久任务处理后进入检索；问答返回结论、引用原文与位置，用户能直接核对来源。Vue 前端支持资料管理、检索、问答、历史会话和只读学习 Agent。

## 界面预览

从“我的知识空间”开始，查看资料就绪情况、语义索引覆盖及独立摘要任务，再进入检索与问答。

<img src=".github/assets/knowledge-workspace.png" alt="实际知识库工作台：24 份资料、有效分片、原文证据与最近处理任务" width="100%" />

<details>
<summary><strong>知识检索：查看原文片段与两路召回排名</strong></summary>

<img src=".github/assets/knowledge-search.png" alt="真实 BGE 与 ES 混合检索结果，展开关键词和语义排名" width="100%" />

</details>

<details>
<summary><strong>证据问答：结论绑定引用，打开原文并高亮</strong></summary>

<img src=".github/assets/knowledge-evidence.png" alt="实际问答界面与原文引用抽屉，标记为原文摘录模式" width="100%" />

</details>

截图来自浏览器运行的真实界面，使用仓库内 24 份公开样例笔记、真实 MinIO / BGE / ES；问答为 extractive mock。封面为知识库概念插画，架构 GIF 为流程示意。

## 项目亮点

| 能力 | 关键技术 | 可以解释的工程问题 |
| :--- | :--- | :--- |
| **可评测混合检索** | ES BM25 / HNSW、CPU BGE、固定 revision、Java RRF | 语义与关键词互补召回；用同一语料比较五种检索模式 |
| **结论与证据绑定** | 严格 JSON、来源白名单、连续引文、UTF-16 位置、独立核验请求 | 伪造引用拒绝发布；重建后的历史证据明确标记版本 |
| **可恢复文档流水线** | 事务 Outbox、分批检查点、心跳与租约 fencing、generation | 崩溃后复用已生成向量；旧 worker 无法覆盖新版本 |
| **独立摘要支线** | INDEX 完成后事务创建 SUMMARY、独立状态与输入预算 | 摘要失败不阻塞检索；截断范围在界面明确显示 |
| **知识库工作台** | 所有权聚合、当前版本统计、游标分页、任务视图 | 查看真实资料状态，避免文档逐条统计和深 OFFSET |
| **多实例运行与观测** | Redis 共享会话、Actuator、缓存 Prometheus 指标、请求追踪 | token 跨实例 / 应用重启可用；检查积压和运行状态 |
| **前端工程化** | 按需组件、同源 API、SSE 增量解析与取消、组件分离 | 支持反向代理部署；断流、中文边界和过期响应有明确行为 |

同时提供分片上传、PDFBox / PaddleOCR 分流、上下文预算、SSE 状态推送和只读 Agent 工具。完整模块与接口说明见[详细项目指南](studyflow/docs/project-guide.md)。

## 链路与架构

<img src=".github/assets/pipeline.gif" alt="循环架构动图：资料上传、持久任务、混合检索、证据检查与带来源答案" width="100%" />

*动图为资料与问答链路示意；问答验证结果见下方实测说明。*

<details>
<summary><strong>展开静态架构图</strong></summary>

```mermaid
flowchart LR
    UI[知识库工作台] --> API[Spring Boot API]
    API --> DB[MySQL 文档与任务]
    API --> SESSION[Redis 共享会话]
    DB --> LEASE[Outbox 与租约领取]
    LEASE --> PARSE[PARSE 提取与切分]
    PARSE --> EMBED[EMBED 分批检查点]
    EMBED --> INDEX[INDEX 幂等写入 ES]
    INDEX --> READY[资料可检索]
    READY --> SUMMARY[独立 SUMMARY 任务]
    SUMMARY --> DERIVED[摘要与输入范围]
    READY --> RETRIEVE[BM25 / HNSW / Java RRF]
    RETRIEVE --> CHECK[原文与权限版本核验]
    CHECK --> ANSWER[结论与证据 / 拒答]
```

</details>

## 快速开始

需要 **JDK 21、Maven 3.9+、Node.js 20+、Docker Compose**。以下 Bash 命令从仓库根目录执行。

### 后端与基础设施

```bash
cd studyflow
docker compose up -d mysql redis minio elasticsearch
STUDYFLOW_MQ_MODE=async STUDYFLOW_OCR_ENABLED=false mvn spring-boot:run
```

此模式使用本地异步执行器、mock AI 和 mock embedding，可体验 TXT / Markdown / 文本型 PDF 链路。mock 问答只输出原文摘录，并在页面标记；真实语义检索需配置 BGE。

### 前端

在另一个终端，从仓库根目录执行：

```bash
cd studyflow-web
npm ci
npm run dev
```

| 入口 | 地址 |
| :--- | :--- |
| 前端 | `http://localhost:5173` |
| API / Knife4j | `http://localhost:8080` / `http://localhost:8080/doc.html` |
| MinIO 控制台 | `http://localhost:9001` |
| Elasticsearch | `http://localhost:9200` |

真实 BGE 侧车、模型身份与新索引配置见[检索实验指南](retrieval-lab/README.md)。RocketMQ、PaddleOCR 和外部模型接入见[详细项目指南](studyflow/docs/project-guide.md)。

### 构建与验证

```bash
cd studyflow
mvn verify
```

```bash
cd studyflow-web
npm ci
npm run build
```

真实 MySQL / ES 测试使用隔离库和索引，启用方法见[完整验收记录](studyflow/docs/highlights-verification.md)。

## 验证结果

**2026-10-15：开启真实 MySQL / ES 的 85 项后端测试全部通过，前端 8 项协议测试与生产构建通过。** 徽章记录本次实测，不代表持续集成状态。

| 实验 | 实测结果 |
| :--- | :--- |
| 24 份资料完整 API 链路 | 真实 MinIO、CPU BGE 与 ES 完成主流水线和独立摘要 |
| 共享会话 | 同 token 跨两个实例使用，应用 SIGKILL 后无需重新登录 |
| 工作台与权限 | 当前版本统计、无重复游标页、跨用户概览/任务访问拒绝 |
| 浏览器检查 | 概览、排名详情、引用高亮、文件筛选和 390px 手机布局 |
| Java API 检索对照 | 22 道 test 问题：BM25 Recall@5 0.8864；RRF 1.0000 |
| 引用与 SSE | 原文定位可核对；核验完成后才发送答案 delta |
| 重建、删除与跨用户访问 | 旧引用标记版本；删除及越权访问拦截 |
| 历史文档 SIGKILL 实验 | 2026-10-09 同任务约 66 秒恢复，45 个分片无重复；见保留报告 |

当前检索集由实现者编写并标注，规模较小；问答端到端为 **extractive mock**。这些数据不代表真实大模型准确率或生产容量。参数、逐题排名和复现步骤均保留在仓库。

<details>
<summary><strong>查看 Java API 检索样例指标</strong></summary>

| 模式 | Recall@5 | MRR@5 | nDCG@5 | 顺序查询 p95 |
| :--- | ---: | ---: | ---: | ---: |
| BM25 | 0.8864 | 0.8788 | 0.8688 | 34.51 ms |
| VECTOR | 0.9091 | 0.7652 | 0.8025 | 76.01 ms |
| RRF | 1.0000 | 0.8977 | 0.9238 | 89.73 ms |
| RRF_LEXICAL | 1.0000 | 0.9205 | 0.9406 | 88.51 ms |
| LEGACY | 0.8864 | 0.8788 | 0.8651 | 65.65 ms |

24 分片、22 道 test 问题，单进程顺序测量并预热，未测高并发。完整报告见[Java API 评测](retrieval-lab/reports/workspace-java-evaluation.md)。

</details>

## 知识库如何工作

### 资料与派生摘要各自推进

文档、任务和唤醒事件在 MySQL 事务内保存；文件读取、OCR、向量及模型请求在事务外执行。主任务只有完成 INDEX 才发布可检索状态，随后创建新的 SUMMARY 任务。摘要的失败、重试与租约回收不改变主索引状态。

向量生成每次只读取一批分片并保存检查点，重启后扫描同一 generation，复用模型和维度兼容的有效向量。索引通过稳定 ID 幂等写入。恢复扫描有批次上限，并与重建、删除保持统一锁顺序。

### 排名和引用都能核对

关键词与语义召回分别保留原始排名，Java RRF 按 `1 / (60 + rank)` 合并；过滤旧版本候选不会抬高剩余片段的排名。检索说明接口公开候选数量、原始排名、贡献及降级原因，分数不表示准确率。

问答结论绑定本次检索来源与连续引文，保存 generation、正文 SHA-256 和 UTF-16 位置。发布前后复查权限和版本，真实模型模式执行独立核验请求。历史引用在重建后标记旧版本，删除后引用详情不可读取。

### 工作台与运行状态有真实数据

知识概览只统计所属知识库的当前有效资料，资料列表采用游标分页。登录会话由 Redis 共享，操作端点返回请求 ID，Prometheus 抓取读取缓存快照。默认管理端口为 `127.0.0.1:9091`；详情见运行指南。

## 工程验证与复现

```bash
# 前端协议测试
cd studyflow-web
npm test
npm run build
```

真实 MySQL / ES 测试和双实例 API 验证有独立入口，避免测试清理应用数据。完整命令、隔离条件、验证范围及实际报告见[本轮升级验收](studyflow/docs/workspace-upgrade-verification.md)。

默认质量工作流运行后端单元验证、前端协议测试与生产构建；真实依赖工作流支持手动触发。工作流状态见页首实时徽章，静态测试数量记录本次本地实测。

## 文档导航

| 阅读目的 | 文档 |
| :--- | :--- |
| 使用知识库 | [知识空间与资料指南](studyflow/docs/guides/knowledge-base.md) |
| 了解本轮技术选择 | [ADR：知识库工作台](studyflow/docs/adr/001-knowledge-workspace.md) · [当前架构](studyflow/docs/architecture-v2.md) |
| 调用新增接口 | [工作台与检索 API](studyflow/docs/guides/api.md) |
| 配置部署与监控 | [运行、监控及升级指南](studyflow/docs/guides/operations.md) |
| 核对最新实测 | [升级验收](studyflow/docs/workspace-upgrade-verification.md) · [API 报告](studyflow/docs/experiments/workspace-api-results.json) |
| 理解业务、模块与接口 | [详细指南](studyflow/docs/project-guide.md) · [技术架构](studyflow/docs/TECHNICAL_ARCHITECTURE.md) |
| 运行真实向量检索 | [BGE 侧车与五路评测](retrieval-lab/README.md) · [固定模型版本](retrieval-lab/model-lock.json) |
| 理解问答证据边界 | [结论与原文证据](studyflow/docs/claim-evidence-qa.md) |
| 理解故障恢复 | [持久任务设计](studyflow/docs/durable-document-pipeline.md) · [SIGKILL 实测](studyflow/docs/experiments/pipeline-process-recovery-results.json) |
| 核对质量与验收 | [Java API 检索报告](retrieval-lab/reports/workspace-java-evaluation.md) · [完整 API 结果](studyflow/docs/experiments/highlights-e2e-results.json) |
| 准备技术面试 | [亮点验收与简历表述](studyflow/docs/highlights-verification.md) · [只读 Agent](studyflow/docs/LEARNING_AGENT.md) |

<details>
<summary><strong>实现边界与后续增强</strong></summary>

- 真实生成模型语义准确率、RocketMQ 集群故障与生产容量尚未验证。
- 摘要已独立，向量和索引已分批；PARSE 仍采用整文件 / 全部分片内存处理，流式解析有待继续实现。
- 共享对象保留策略、parser / chunker 完整版本缓存和索引 alias 灰度切换仍待建设。
- 应用会话已通过 Redis 共享；Redis 数据丢失、持久化与集群故障不在本轮应用重启验证范围。

</details>

---

同作者项目：[CampusTrade — 交易一致性与可靠通知](https://github.com/wangbf123/CampusTrade-Backend)
