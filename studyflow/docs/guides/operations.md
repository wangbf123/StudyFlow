# 运行、监控与升级

## 本地运行路径

基础依赖：MySQL 8、Redis 7、MinIO、Elasticsearch 8.15.3；JDK 21、Maven 3.9+、Node.js 20+。MinIO Compose 已固定 release 标签。首次部署的镜像与模型需要正常访问官方制品源。

```bash
cd studyflow
docker compose up -d mysql redis minio elasticsearch
STUDYFLOW_MQ_MODE=async STUDYFLOW_OCR_ENABLED=false mvn spring-boot:run
```

前端从仓库根目录另开终端：

```bash
cd studyflow-web
npm ci
npm run dev
```

开发代理默认为 `http://127.0.0.1:8080`，可设置 `STUDYFLOW_PROXY_TARGET`。生产构建默认同源 `/api`，可配置 `VITE_API_BASE_URL`；参考 [Nginx 配置](../../../studyflow-web/nginx.conf)，事件流关闭缓冲。示例上游 backend 是部署服务名，需要按实际环境调整。生产前配置 TLS、私有密码及对象存储访问边界。

真实向量服务按 [BGE 启动指南](../../../retrieval-lab/README.md) 设置 provider、模型完整 revision、512 维及独立索引。生成模型的密钥只从环境注入。mock embedding / mock AI 可验证运行协议，不验证语义质量。

## 监控端口

默认管理监听为 **127.0.0.1:9091**，与业务端口分离。需要远程抓取时通过私有网络或受保护代理开放；不要直接对公网绑定管理地址。

| 地址 | 含义 |
|---|---|
| `/actuator/health/liveness` | 应用是否存活，不依赖外部存储 |
| `/actuator/health/readiness` | 应用就绪状态及 MySQL、Redis、ES |
| `/actuator/prometheus` | HTTP、JVM、线程池与流水线指标 |

就绪检查未覆盖 MinIO、BGE、OCR 或生成模型；它们的故障通过具体任务状态、检索降级和接口错误反映，需要按部署需求增加独立探测。

流水线指标：

- `studyflow_pipeline_tasks{stage,status}`：缓存任务数，标签只有固定阶段与状态。
- `studyflow_pipeline_oldest_pending_seconds`：当前有效版本最老到期任务的等待时长（数据库 next_attempt_time，不使用 JVM 创建时间）。
- `studyflow_pipeline_snapshot_available`：最近聚合是否成功。
- `studyflow_pipeline_snapshot_epoch_seconds`：最后成功刷新时间，避免把旧快照当作实时数据。

默认 15 秒刷新；Prometheus 抓取不查询数据库。stage/status 计数包括历史任务，应结合当前版本待处理年龄观察。模型服务或外部存储中断时优先检查阶段错误、退避时间与租约，不执行全库清理。

async 执行器分为主解析（4–8 线程、队列 200）、摘要（1–2 线程、队列 32）和问答（4–8 线程、队列 200）。拒绝任务由持久 Outbox 退避重试。该隔离不等同于 RocketMQ topic/消费者资源隔离。

## 主要配置

| 配置 | 默认 | 作用 |
|---|---:|---|
| `STUDYFLOW_CHUNK_BATCH_SIZE` | 64，范围 1–100 | EMBED / INDEX 分页批次 |
| `STUDYFLOW_SUMMARY_MAX_CHARS` | 12000，范围 256–64000 | 摘要输入预算，截断可见 |
| `STUDYFLOW_PIPELINE_BATCH_SIZE` | 20 | 每轮恢复与补投递上限 |
| `STUDYFLOW_PIPELINE_LEASE_SECONDS` | 60，最小 15 | worker 租约及心跳 |
| `STUDYFLOW_MANAGEMENT_PORT` | 9091 | 独立管理端口 |
| `STUDYFLOW_MANAGEMENT_ADDRESS` | 127.0.0.1 | 管理端口绑定 |
| `studyflow.metrics.refresh-ms` | 15000 | 缓存观测刷新间隔 |

## 会话与应用重启

Sa-Token 通过官方 Redis Jackson 适配器共享会话。应用实例应连接同一受保护 Redis 数据库；token 在不同实例和应用重启后继续有效。Redis 数据丢失、故障转移及持久化策略不在本轮应用重启验证范围内。升级前的内存会话无法自动迁移，首次部署新适配器后需要重新登录。

## 数据升级

V11 增加摘要状态、输入预算元数据和工作台查询索引，旧摘要标记 SUCCESS、旧无摘要资料标记 NOT_REQUESTED。不会为全库自动批量调用模型；按需重建文档再生成摘要。

保持既有 V1–V10 文件不变，在备份/测试副本中先执行 Flyway 验证。应用外部调用不持有长数据库事务；多实例恢复和重试遵守“文档 → 任务”锁顺序。

索引 mapping 或模型改变时使用独立索引与文档新 generation。当前没有自动 alias 灰度切换或共享对象引用计数，不应直接删除旧对象或重用不同维度的索引。
