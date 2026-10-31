# 四层十二模块 Agent：验证记录

日期：2026-10-31。以下是发布本分支前完成的本地验证；不将本地通过结果视为 GitHub Actions 执行结果。

## 已验证结果

| 范围 | 结果 | 证据 |
|---|---|---|
| 后端完整验证 | 133 项，失败 0、错误 0、跳过 0 | [JUnit 汇总](experiments/harness-tests.json) |
| 前端测试/生产构建 | 13 项通过，Vite 构建通过 | SSE、范围、图谱、布局恢复；图谱构建块仍较大，没有首屏性能基准 |
| 知识工作区兼容性 | 文档上传、BGE/ES、Neo4j、Agent、报告笔记与回放通过 | [实际 API](experiments/harness-workspace-api.json) |
| 多 Agent | 5 个知识域分成 3 个不重叠子范围，22 次共享模型调用，主任务成功 | [协作 API](experiments/harness-agent-api.json) |
| 记忆 | 有来源记忆召回、版本失效、跨域范围过滤、主动忘记通过 | 协作 API 与真实 MySQL 测试 |
| 协作重试 | 重试保留协作模式；已失效或忘记的记忆不再次注入 | 协作 API 与真实 MySQL 测试 |
| 预算/取消 | MySQL 并发预留只能放行预算内请求；取消级联且旧租约不能提交 | 并发测试与协作 API |
| MCP | 官方 SDK 完成初始化/工具调用；真实登录授权、资源导入、URI/范围限制、撤销通过 | [MCP API](experiments/harness-mcp-api.json) 与协议测试 |
| Claude | 官方 SDK 原生 Messages、单工具 Tool Use、输出上限和用量往返通过 | `AnthropicProtocolTest` 的本地 HTTP 服务 |
| Context | Unicode 安全截断、Token 预算、稳定结果引用、超限请求拒绝通过 | `ContextBudgetTest`、实际调用清单 |
| Runtime | 实际 Docker 容器：普通用户、无宿主机文件/Socket、无网络、根目录不可写；输出/20 秒超时上限通过 | `DockerEnvironmentTest`，digest 固定 Python 镜像 |
| Telemetry | 未配置 Collector 时也有有效 Trace ID；调用审计和运行详情通过 | `TelemetryAndMockTest` 与协作 API |
| 浏览器兼容 | Markdown 编辑、Wiki 链接、G6、Agent 历史、报告保存、搜索、经典入口通过 | [工作区浏览器](experiments/harness-workspace-browser.json) |
| 新增界面 | 记忆保存/忘记、连接空状态、三个子任务、压缩清单、模型调用显示通过 | [Agent 浏览器](experiments/harness-agent-browser.json) |
| 移动布局 | 390px 无横向溢出，页面错误 0 | 两份浏览器报告 |
| 崩溃恢复 | 子任务分派提交后强制停止两个工作实例，保留同一 token 和运行记录，恢复并成功汇总 | [实际重启](experiments/harness-restart.json) |

失效租约恢复另有 [执行记录](experiments/harness-lease-recovery.json)：3 个子任务回收租约并成功，保留 25 次共享调用计数，未知用量保守计入 6,927 tokens、执行中预留归零，根任务明确标记用量不完整。分派检查点重启记录与租约失效记录分开列示，避免把两种场景混为一谈。

MCP 使用本地、回环地址上的协议测试服务；没有测试第三方服务的可用性。Claude 使用本地原生协议响应；没有配置真实模型密钥，也没有进行模型语义质量、供应商限流或费用评测。

模型为 Mock 的端到端流程明确输出 `EXTRACTIVE_ONLY`。Token 压缩目前是结构化、确定性的摘录压缩，没有测得通用的压缩百分比。最终计划是否完整完成，不等于模型主动记录的 `AGENT_COMPLETED` 状态；界面将未记录的步骤显示为“未单独确认”。

## 本轮发现并修复的问题

- **共享预算旧快照**：MySQL Repeatable Read 下，先前的非锁定快照可能让两个子任务都通过预算检查。改为根任务优先加锁并用当前加锁读取检查预留。
- **图自循环未让出线程**：框架的 `interruptAfter` 不能单独保证自循环暂停。持久化的等待/拒绝重试转移显式结束本次图调用，由调度器下次恢复。
- **压缩与 Mock 兼容**：Mock 不再假定旧工具摘录是完整 JSON；兼容压缩片段，并保留当前笔记引用。
- **空 Trace ID**：OpenTelemetry 1.67 对没有 Processor 的 Provider 有优化；保留本地审计所需的有效 Span Context。
- **HTTP 依赖冲突**：对齐 OkHttp JVM 与 Kotlin 运行库，避免同时打包 4.x/5.x 的同名实现类。
- **记忆类型挤占**：110 次相似经历不会挤掉有来源的知识或偏好；候选与召回按类型预留位置，管理界面也保留这些类型。
- **用量丢失**：崩溃/取消后的未知用量保守计入预留额度；根任务汇总子任务的 `usageIncomplete`，不把未知消耗显示为已完整报告。

## 复现

后端完整测试沿用真实依赖环境，凭据通过环境变量提供；请使用专用测试 schema：

```bash
# Set EVIDENCE_TEST_DB_PASSWORD and PIPELINE_TEST_DB_PASSWORD in your own environment.
docker pull python@sha256:2d44738ebf4ce5f63b9d9ea871e9c7cfdc0426f7b0c97cac915fd88ded1d86bb
export STUDYFLOW_SANDBOX_TEST_IMAGE=python@sha256:2d44738ebf4ce5f63b9d9ea871e9c7cfdc0426f7b0c97cac915fd88ded1d86bb
export STUDYFLOW_ES_INTEGRATION=true
mvn -B -f studyflow/pom.xml -Dpipeline.mysql.tests=true -Devidence.mysql.tests=true \
  -Dbrain.mysql.tests=true -Dbrain.neo4j.tests=true clean verify
npm --prefix studyflow-web test
npm --prefix studyflow-web run build
```

先按 [知识工作区验证](personal-workspace-verification.md) 准备专用演示用户的仓库外会话文件，然后运行：

```bash
python retrieval-lab/verify_agent_harness.py \
  --session-file /path/outside/checkout/browser-session.json \
  --api-url http://127.0.0.1:8082 --peer-url http://127.0.0.1:8083 \
  --report studyflow/docs/experiments/harness-agent-api.json

python retrieval-lab/capture_agent_harness.py \
  --session-file /path/outside/checkout/browser-session.json \
  --api-url http://127.0.0.1:8082 --web-url http://127.0.0.1:5173 \
  --output-dir /path/outside/checkout/screenshots \
  --report studyflow/docs/experiments/harness-agent-browser.json
```

MCP API 验证使用 `retrieval-lab/mcp_fixture.py --port 8766`。另起一个关闭 Agent 调度的测试实例，服务端配置 `fixture` 连接，URL 为 `http://127.0.0.1:8766/mcp`，允许工具 `lookup`、资源前缀 `kb://fixture/`，再执行 `verify_mcp_connection.py --api-url ... --session-file ... --report ...`。这类测试配置不要指向生产知识空间。

重启验证只强制停止本轮创建的隔离实例；报告记录实际 JAR SHA-256、分派检查点和恢复结果。凭据、token、实例元数据与运行日志放在仓库外，不进入截图和报告。

架构、配置及当前能力边界见 [四层十二模块设计](agent/harness-architecture.md)。
