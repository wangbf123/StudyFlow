# 个人知识工作区与 Agent：本地验证

日期：2026-10-31，Asia/Shanghai。以下保留知识工作区阶段的本地验证记录；代码、截图和报告随本分支发布，不将本地结果视为远程 CI 执行结果。

本轮 Harness 升级的完整测试与新增验证见 [Harness 验证](harness-verification.md)；下表保留上一轮知识工作区版本的验证依据。

## 结果

| 验证 | 结果 | 依据 |
|---|---|---|
| 后端完整测试 | 115 项通过，失败 0，错误 0，跳过 0 | [JUnit 汇总](experiments/personal-workspace-tests.json) |
| 前端自动测试 | 13 项通过 | SSE 边界、回放、布局恢复、范围与个人图谱聚合 |
| Vue 生产构建 | 通过 | 图谱与编辑器在工作区按需加载；G6 构建块较大，尚无首屏性能基准 |
| 真实 API | 5 个知识库、9 条初始笔记、14 条明确链接 | [API 记录](experiments/personal-workspace-api.json) |
| Neo4j 投影 | 局部关系与 MySQL 规范数据一致 | API 返回 NEO4J_VERIFIED；含乱序并发版本测试 |
| 文档处理 | 实际 MinIO、CPU BGE、Elasticsearch 索引成功 | API 记录中的 documentPipeline |
| Agent 闭环 | 真实工具执行、原文摘录核验、报告保存为链接笔记 | API 记录中的 durableAgent / reportToLinkedNote |
| 跨实例 | 同一 Redis token 在两个后端读取同一 run | API 记录中的 crossInstanceSession |
| 重启恢复 | 两个实例在 PLAN 提交后强制终止，新进程续跑成功 | [重启记录](experiments/personal-workspace-restart.json) |
| 真实浏览器 | 编辑保存、链接跳转、G6、历史回放、报告写回、搜索通过 | [Chromium 记录](experiments/personal-workspace-browser.json) |
| 移动布局 | 390px 无横向溢出；页面错误 0 | Chromium 记录与 [截图](../../.github/assets/brain-mobile.png) |

## 有意义的新增用例

- 同标题待创建链接解析；目标改名和源笔记再次编辑后链接保持稳定。
- 跨知识库链接只在指定范围中展示；其他账号无法读取内容或操作任务。
- 删除目标、移动笔记后，图谱与证据范围正确更新。
- 六个并发编辑者只有一个成功提交相同 revision，冲突不会覆盖正文。
- 知识库循环、根目录移动、最大 12 层及外部账号父节点检查。
- 并发幂等创建、负载指纹冲突、旧租约不能提交、取消后晚结果丢弃。
- 模型调用预占预算、数据库时钟 deadline、报告/事件/检查点回滚。
- 笔记内容或版本改变后，来源核验失败。
- 相同报告重复保存不重复创建；不同报告同标题获得可读序号。
- 原生工具请求真实经过 HTTP 协议测试，包含 tools / schema、禁止并行工具、错误名称与严格 JSON 参数拒绝。
- Neo4j 并发初始化、乱序投影和最高 graph_revision 保留，查询限制 owner 和知识库。

## 实际运行模式

主后端 8082、第二实例 8083，管理端点分别 9092 / 9093，前端开发代理到 8082。两个进程使用隔离的演示 schema 和图谱服务；已有其他项目没有改动。

服务为 MySQL 8.4、Redis 7.4、Elasticsearch 8.15.3、Neo4j 5.26.32、已校验的本地 MinIO，以及固定 revision 的 BGE-small-zh-v1.5。模型使用 mock，输出明确标记为 EXTRACTIVE_ONLY。

浏览器使用真实 API 登录会话，只设置正常的本机登录和布局状态。页面内容未通过 DOM 注入伪造。专用演示内容不含用户私有资料，令牌仅保存在仓库外 0600 会话文件中。

后端以构建 JAR 的固定副本启动，避免重新打包改变运行中进程的类路径。重启测试记录实际 JAR SHA-256。租约回收是否发生以报告的 recoveredLease 字段为准；本次强制停止发生在已提交 PLAN 边界，过期租约隔离另由真实 MySQL 测试覆盖。

## 限制

- 真实大模型的工具选择质量、综合研究效果、成本和生产负载未在本轮测量。
- 图谱当前来自显式链接和知识库层级。自动实体抽取、推测关系审核与完整 GraphRAG 排序尚未启用。
- 原始文档支持混合检索；笔记使用有界文本搜索和图谱探索。
- 当前权限模型是账号私有知识空间，尚无多人共享 ACL。
- 文档 OCR、RocketMQ 模式和生产集群高可用未在本轮重新验证。

启动、API 和复跑脚本见[使用指南](guides/personal-workspace.md)；完整实现边界见[实现清单](agent/implementation-plan.md)。
