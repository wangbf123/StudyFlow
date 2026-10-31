# 使用个人知识工作区

## 启动

沿用仓库根 README 的 JDK 21 / Maven / Node.js / MySQL / Redis / MinIO / Elasticsearch 配置。数据库通过 Flyway 增量升级到 V15；保留原有数据。

```bash
# 仓库根目录；首次安装或依赖变化后执行
npm --prefix studyflow-web ci
mvn -f studyflow/pom.xml verify

# 基础服务
cd studyflow
docker compose up -d mysql redis minio elasticsearch
STUDYFLOW_MQ_MODE=async STUDYFLOW_OCR_ENABLED=false mvn spring-boot:run
```

另开终端：

```bash
cd studyflow-web
npm run dev
```

前端默认将 `/api` 代理到 `http://127.0.0.1:8080`；自定义开发 API 地址使用 `STUDYFLOW_PROXY_TARGET`。部署时沿用同源 API 反向代理和 SSE 禁用缓冲设置。

## 可选 Neo4j 图查询

SQL 规范数据查询支持全部笔记与图谱基本流程。启用 Neo4j 时，启动投影器并对局部关系查询使用已验证投影：

```bash
cd studyflow
export STUDYFLOW_GRAPH_PASSWORD=studyflow-local-only
docker compose --profile graph up -d --wait neo4j
STUDYFLOW_GRAPH_ENABLED=true \
STUDYFLOW_GRAPH_USERNAME=neo4j \
STUDYFLOW_MQ_MODE=async \
STUDYFLOW_OCR_ENABLED=false \
mvn spring-boot:run
```

示例密码只供本地开发。已有 Neo4j 数据卷保留原密码，修改环境变量不等于重置数据库密码。默认图数据库端口绑定到本机。通过 `STUDYFLOW_GRAPH_URI` 指定其他 Bolt 地址。

启用图服务时，后端启动需要先连接到就绪的 Neo4j。图投影采用事务 outbox，正常运行后自动追赶。若需重建，可由维护者针对有权管理的笔记重新安排 outbox 事件；权威笔记与链接始终在 MySQL。投影中断时 API 回退到有界规范查询。

## 工作区

1. 注册并登录，创建任意命名的知识库。可选择上级、颜色和默认模板。
2. 点击笔记区 `+` 新建空白、概念、项目、决策或生活笔记。
3. Markdown 编辑器支持编辑、阅读、分屏与 `Ctrl/Cmd+S` 保存。
4. 输入 `[[` 打开链接补全；新建的目标笔记会解析同库待创建链接。
5. 在右侧查看反向链接、局部图谱、大纲、属性和版本。
6. 在属性面板切换归属知识库；先保存草稿，移动后保持稳定链接。
7. `Ctrl/Cmd+O` 跨库搜索；`Ctrl/Cmd+P` 打开命令面板。
8. 图谱可选择多个知识库；“个人全景”聚合知识库，点击库节点展开或折叠笔记。
9. “资料”入口上传原始 PDF / TXT / Markdown / 图片；原文阅读与文档处理沿用已有流水线。

多标签切换保留当前草稿。关闭有未保存修改的标签会提示保存；离开页面时浏览器提示。布局只在本机保存库 ID、笔记 ID 与面板宽度，正文仍通过认证接口读取。

## 双向链接

```markdown
[[概率统计]]
[[概率统计#条件概率|相关定义]]
[[概率统计#^example-block|例子]]
[[note:目标笔记ID|展示别名]]
[[kb:知识库ID/笔记标题|跨库笔记]]
```

普通标题只在当前知识库解析。补全会插入稳定 ID，用户仍看到可读标题。目标改名或移动后，已建立的链接保持绑定。

标题锚点由标题文本生成；段落末尾 `^example-block` 声明块锚点。点击带锚点的链接会切到阅读模式并滚动到对应内容。代码围栏和行内代码中的 `[[文本]]` 不生成关系。

历史版本“载入编辑器”只改变本地草稿，保存后才生成新版本。并发编辑发生冲突时保留草稿，用户可通过其他标签查看最新内容后合并。

## 使用 Agent

右侧 Agent 提供研究综述、主题对比、复习提纲、知识整理、代码研究五种 Skill。代码执行能力需要单独启用隔离环境。选择本次任务可以使用的知识库，最多 8 个。点击“引用当前笔记”附加明确的上下文卡片，可移除。

任务创建后，范围、Skill 和引用笔记固定在 run 中。切换笔记或页面不会改变后台任务。历史菜单可恢复状态与事件；“取消”改变任务状态，“新会话”只调整当前查看位置。

默认 `studyflow.ai.provider=mock`，执行真实工具查询并输出明确标记的原文摘录。真实模型支持现有 OpenAI-compatible 配置和 Harness 阶段路由；Claude 使用官方原生 SDK。工具阶段需要对应模型支持原生 Tool Calling，密钥通过环境配置提供，详见 [模型与连接配置](../agent/harness-architecture.md)。

报告先核验证据，再发布。原文来源改变时显示失效提示。“存为笔记”写入本次范围中的主知识库，相同 run 重复保存返回同一笔记；来源笔记链接会进入图谱。不同任务同名报告自动添加可读序号。

## API 摘要

| 方法与路径 | 用途 |
|---|---|
| `POST /api/knowledge-bases` | 创建知识库，支持 parentId、templateKey、color |
| `PUT /api/knowledge-bases/{id}` | 调整名称、上级、模板与颜色 |
| `GET /api/notes?knowledgeBaseIds=...` | 知识库范围内笔记游标分页与文本搜索 |
| `POST /api/notes` / `PUT /api/notes/{id}` | 创建或携带 revision 更新笔记 |
| `POST /api/notes/{id}/move` | 携带 revision 调整所属知识库 |
| `GET /api/notes/{id}/backlinks` | 指定库范围中的反向链接 |
| `GET /api/notes/graph` | 范围内图谱，可指定 focus |
| `GET /api/notes/{id}/revisions` | 历史版本列表 |
| `GET /api/notes/{id}/revisions/{version}` | 历史内容快照 |
| `POST /api/agent/runs` | 幂等创建任务，返回 202 与 run |
| `GET /api/agent/runs/{id}` | 查看当前状态与报告 |
| `GET /api/agent/runs/{id}/events` | 按 after 游标读取事件 |
| `GET /api/agent/runs/{id}/events/stream` | SSE 回放及实时订阅 |
| `POST /api/agent/runs/{id}/cancel` | 取消任务 |
| `POST /api/agent/runs/{id}/retry` | 使用新 requestKey 创建关联重试任务 |
| `POST /api/agent/runs/{id}/save-note` | 幂等将成果保存为笔记 |
| `GET /api/agent/runs/{id}/artifact` | 下载 Markdown 报告 |

创建任务示例：

```json
{
  "knowledgeBaseId": "主知识库ID",
  "knowledgeBaseIds": ["主知识库ID", "另一个知识库ID"],
  "contextNoteId": "可选的当前笔记ID",
  "goal": "结合已有资料整理研究问题，并引用原文",
  "skill": "research",
  "requestKey": "客户端生成并在网络重试时复用的标识"
}
```

当前 API 沿用项目的 `code/message/data` 封装。业务错误应检查 code；部分历史错误仍使用 HTTP 200。Long ID 在 JSON 中以字符串传输。

## 复跑验证

默认 `mvn verify` 运行离线测试，真实服务测试需显式开启。对隔离测试数据库运行：

```bash
mvn -f studyflow/pom.xml \
  -Dpipeline.mysql.tests=true -Devidence.mysql.tests=true \
  -Dbrain.mysql.tests=true -Dbrain.neo4j.tests=true verify
npm --prefix studyflow-web test
npm --prefix studyflow-web run build
```

环境需提供 `EVIDENCE_TEST_DB_PASSWORD`、`PIPELINE_TEST_DB_PASSWORD`、`STUDYFLOW_ES_INTEGRATION=true`；真实服务按现有测试配置运行在 MySQL 3307、ES 9200、Neo4j 7687。本轮新增数据库用例创建并删除随机 `_test` schema。

API 与浏览器脚本会新建专用演示账号和样例内容，保留以供浏览器查看。请用于开发或测试实例：

```bash
python -m pip install -r retrieval-lab/requirements-personal-verification.txt
python retrieval-lab/verify_personal_workspace.py \
  --api-url http://127.0.0.1:8080 --require-neo4j \
  --work-dir /tmp/studyflow-personal-demo

python retrieval-lab/capture_personal_workspace.py \
  --session-file /tmp/studyflow-personal-demo/browser-session.json \
  --api-url http://127.0.0.1:8080 --web-url http://127.0.0.1:5173 \
  --output-dir /tmp/studyflow-personal-screenshots \
  --report /tmp/studyflow-personal-browser-results.json
```

可以使用 `--peer-url` 验证第二实例读取相同 token 与 run；未指定时报告明确记为 NOT_REQUESTED。Chromium 可通过 `--chromium-path` 指定，或事先用 Playwright 安装。会话文件权限为 0600，并要求保存在仓库之外。

## Agent 记忆、连接与协作

在知识助手中展开“记忆与连接”，可保存偏好，把当前笔记提炼成有出处的知识或方法记忆，查看来源更新后失效的记忆，并主动忘记。执行经历由根任务完成后自动保存。

“MCP 连接”只列出部署管理员配置的服务。授权当前知识库后可浏览、导入文本资源；未配置连接时会显示说明。连接的范围必须包含在本次研究选择内。

勾选“分领域协作研究”后，最多三个独立子任务分担选定领域，使用共享调用和 Token 预算。运行详情显示子任务状态、上下文清单、压缩数量、召回记忆、模型调用和 Trace ID。默认模型为 Mock 时，仍明确使用原文摘录模式；协作不会将模拟输出变成真实模型评测。

模型路由、MCP 和可选 Sandbox 的部署方法见 [Harness 架构](../agent/harness-architecture.md)。
