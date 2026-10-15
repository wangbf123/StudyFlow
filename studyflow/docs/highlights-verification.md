# 知识库项目亮点与验收记录（2026-10-09）

本页保留第一轮验收快照；最新实现与数据见[2026-10-15 升级验收](workspace-upgrade-verification.md)。

日期：2026-10-09。Java 21、MySQL 8.4.11、Redis 7.4.11、Elasticsearch 8.15.3、MinIO、固定 revision 的 512 维 CPU BGE。测试使用隔离数据库与索引。

| 亮点 | 已实现机制 | 验证 |
|---|---|---|
| 混合检索 | CJK BM25、HNSW、Java RRF、查询前缀、模型身份检查、所有权与 generation 复查 | 真 ES 协议测试；24 分片、22 道 test 问题的原生 ES 和 Java API 五路对照 |
| 结论证据绑定 | 严格 JSON、来源白名单、连续引文和位置、核验失败拒答、事务保存引用 | 安全协议测试、真实 InnoDB 锁竞争与回滚；API 引用定位、SSE、版本变化与越权验证 |
| 可恢复文档任务 | 事务 Outbox、PARSE/EMBED/INDEX、租约心跳与 token fencing、generation、重建/删除补偿 | 真 MySQL 阶段故障与历史迁移；24 个真实上传；独立 Java 进程 SIGKILL 恢复 |

最终 `mvn verify` 启用真实 MySQL 和 ES：**66 项测试通过，0 失败、0 错误、0 跳过**；前端 `npm run build` 通过。端到端测试使用真实 MinIO、BGE 与 ES，MQ 为 async，OCR 关闭，问答为 extractive mock；测试实例仅为批量构造语料关闭限流。

完整 API 样例评测：BM25 Recall@5 **0.8864**、RRF Recall@5 **1.0000**，RRF MRR@5 **0.8977**、nDCG@5 **0.9238**、顺序查询 p95 **63.53 ms**。这组资料、问题和标签由实现者编写，未经过独立标注，不是生产质量或容量证明。参数与逐题排名见[原始 Java API 报告](../../retrieval-lab/reports/initial-java-evaluation.json)。

复现和原始材料：

- [BGE 侧车与检索评测](../../retrieval-lab/README.md)
- [结论证据问答](claim-evidence-qa.md)
- [持久任务设计及测试启动方式](durable-document-pipeline.md)
- [完整 API 结果](experiments/highlights-e2e-results.json)
- [进程中断恢复结果](experiments/pipeline-process-recovery-results.json)

简历可写：

- 实现 Elasticsearch BM25/HNSW 与 Java RRF 混合检索，固定 BGE 模型版本，建立可复现五路对照评测并保存逐题排名、质量指标与查询延迟。
- 实现结论与原文证据绑定的 RAG 问答，设计严格引用协议、字符定位、权限和版本复查、独立核验请求与失败拒答，并验证删除/重建过程中的引用一致性。
- 将文档解析、向量生成与索引改为持久阶段任务，通过事务 Outbox、租约续期和 generation fencing 恢复丢失唤醒及迟到写入，在真实 Java 进程中断后无需重新上传完成处理。

以下为当时边界，独立摘要与共享会话已在后续升级实现：尚未验证真实生成模型的语义准确率、RocketMQ 集群故障和生产容量。摘要当时仍属于 PARSE，大文档仍有整文件/全部分片内存占用；共享对象保留策略、独立摘要支线及索引 alias 灰度切换需继续建设。默认登录会话存储在进程内，后端重启后实验重新登录；多实例会话持久化需另行配置。
