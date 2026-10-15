# 知识库工作台升级验收

日期：2026-10-15。以下均为本轮实际执行结果；历史实验保留原日期，未混为本次数据。

## 已完成的技术升级

| 领域 | 变化 | 验证依据 |
|---|---|---|
| 文档处理 | INDEX 就绪后独立 SUMMARY，模型故障不阻塞检索 | 真 MySQL 原子创建、摘要失败/租约耗尽保持主索引 SUCCESS |
| 批次与恢复 | EMBED 分批检查点、有效向量复用、INDEX 分页、有界恢复扫描 | worker 批次测试、旧 generation fencing、恢复 20/23 批次实验 |
| 知识库工作台 | 当前版本统计、任务视图、游标资料与筛选 | 真 MySQL 聚合与游标测试；24 资料真实 API 和浏览器验证 |
| 检索解释 | 原始排名、RRF 贡献、过滤数与降级原因 | 真 ES 协议与模型离线测试，正文漂移拒绝 |
| 共享会话 | 官方 Redis Jackson 适配器 | 同 token 跨两个运行实例；应用 SIGKILL 后不重新登录 |
| 观测与诊断 | Actuator、缓存流水线指标、请求 ID、异步 MDC | 实际 liveness/readiness UP、Prometheus 固定标签输出与请求过滤测试 |
| 前端可靠性 | 同源 API、按需组件/CSS、SSE 取消和严格终止检查 | 8 项协议测试，生产构建与 Chromium 实际页面检查 |
| 仓库交付 | CI、使用/API/架构/运维/ADR、实际界面与知识图书馆封面 | 文档链接检查及图片格式/帧验证 |

## 自动化测试

后端启用真实 MySQL 和 ES 的 `mvn verify`：**85 项通过，0 失败、0 错误、0 跳过**。

前端 `npm test`：**8 项通过**。覆盖逐字符 CRLF/Unicode、多行 data、半帧 EOF、超限输入、UTF-8 字节拆分、服务错误、JSON 认证错误、AbortSignal、终止事件后关闭上游及忽略迟到帧。

前端生产构建通过。Element Plus 按需导入后，本次构建约为主 JS 459 KB、Vue 80 KB、图标 8 KB，最大 JS 块低于 Vite 默认 500 KB 提示阈值；这属于构建产物大小，不等于浏览器加载性能基准。

默认 CI 验证单元测试和前端构建，真实依赖工作流手动触发。本文数据来自本地隔离实依赖运行，GitHub 工作流实际状态以 Actions 为准。

## 真实 API 与浏览器

隔离测试库与索引，两个后端实例共享同一 Redis，会话 token 没有写入仓库。使用真实 MySQL、Redis、MinIO、ES 8.15.3、固定 revision 的 512 维 CPU BGE；async 唤醒、extractive mock AI，OCR 关闭，仅测试实例关闭限流。

24 个公开样例文档完成主流水线和独立摘要，当前概览为 24 份就绪文档、24 个当前分片和 24 个已编码分片。测试了游标页无交集、有效摘要计数、两路原始召回排名、结论原文引用，以及外部用户无法读取概览、资料分页和任务。

最终发布 JAR 另以批次 2 处理多分片资料，确认独立摘要线程池注册并成功执行，保留原 token 在两个重启实例可用。

真实 Chromium 检查了认证后概览、搜索排名详情、引用抽屉高亮、资料名筛选及 390px 手机宽度无页面横向溢出。截图来自这些页面，样例数据并未通过 DOM 注入伪造。

- [自动化测试原始结果](experiments/workspace-test-results.json)
- [最终发布 JAR 验证](experiments/workspace-release-smoke.json)
- [API 原始结果](experiments/workspace-api-results.json)
- [浏览器原始结果](experiments/workspace-browser-results.json)
- [运行观测结果](experiments/workspace-operations-results.json)
- [本轮 Java API 逐题检索报告](../../retrieval-lab/reports/workspace-java-evaluation.json)

## 检索样例结果

相同 24 分片、22 道 test 问题，K=5，固定数据摘要和模型 revision。RRF Recall@5 为 1.0000、MRR@5 为 0.8977、nDCG@5 为 0.9238；BM25 Recall@5 为 0.8864。本轮 RRF 顺序查询 p95 为 89.73 ms。

问题与标签由实现者编写，没有独立标注；未运行高并发。与旧机器/旧环境的延迟不能直接对比，不能据此声称线上准确率提升或容量保证。

## 复现入口

准备独立 `studyflow_pipeline_test` 与 `studyflow_pipeline_upgrade_test` 并授权测试用户。测试会重置这些测试表；证据测试创建并删除自己的随机测试库，账号需相应权限。

```bash
# 密码由当前终端环境安全注入，不写入命令或提交文件。
STUDYFLOW_ES_INTEGRATION=true mvn -B -f studyflow/pom.xml \
  -Dpipeline.mysql.tests=true -Devidence.mysql.tests=true verify
```

密码环境名为 `PIPELINE_TEST_DB_PASSWORD` 与 `EVIDENCE_TEST_DB_PASSWORD`，连接参数见对应测试源文件。真实 ES 使用 `STUDYFLOW_ES_TEST_URL`，默认本机 9200。

在独立测试实例运行 API 实验：

```bash
python3 retrieval-lab/verify_workspace.py \
  --backend-url http://127.0.0.1:8082 \
  --second-url http://127.0.0.1:8083
```

该命令创建自己的测试账号和知识库，成功后删除自己的知识库；`--retain-fixture` 可为截图保留样例。默认不会停止任何进程。`--after-login-command` 可显式指定隔离实例的重启脚本，进程控制责任由调用者限定，本文实验只中断自己启动的 8082 实例。

生成的本地 `browser-session.json` 供截图验证使用，包含 token、权限模式 0600，位于工作目录而非仓库。不要将工作目录选在仓库内；测试后清理会话文件。

## 仍需继续建设

整文件 PARSE 的内存、完整 parser/chunker 版本缓存、共享对象引用计数、索引 alias 灰度切换、真实生成模型语义评测及 Redis/RocketMQ 集群故障尚未完成。当前代码和报告不承诺这些能力。

可用于简历的表述：将资料处理改造为主索引与派生摘要隔离的持久任务，基于分批向量检查点、租约和 generation fencing 恢复中断；实现可解释混合检索、知识库状态工作台及 Redis 跨实例会话，通过真实依赖、双实例和浏览器实验验证边界行为。


截图与浏览器校验可选安装 `retrieval-lab/requirements-browser.txt`，并使用 Playwright 安装 Chromium 或指定已有浏览器路径。示例：

```bash
python3 retrieval-lab/capture_workspace.py \
  --session-file /tmp/studyflow-workspace-verification/browser-session.json \
  --web-url http://127.0.0.1:5173
```

截图只使用公开测试资料；私有知识库不要运行会将页面写入仓库的截图命令。
