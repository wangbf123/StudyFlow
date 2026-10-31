# StudyFlow Web

Vue 3 个人知识工作区：可扩展知识库树、Markdown 笔记、双向链接、知识图谱和 Agent。旧资料处理、混合检索及问答工作台继续通过 `/#classic` 提供。

[完整使用指南](../studyflow/docs/guides/personal-workspace.md) · [Agent 架构](../studyflow/docs/agent/harness-architecture.md) · [验证记录](../studyflow/docs/harness-verification.md)

## 启动与构建

```bash
npm ci
npm run dev
npm test
npm run build
```

开发服务默认 `http://localhost:5173`，`/api` 由 Vite 代理到 `http://127.0.0.1:8080`；通过 `STUDYFLOW_PROXY_TARGET` 修改开发代理地址。生产环境推荐同源反向代理；分离部署时通过 `VITE_API_BASE_URL` 注入后端地址，并配置认证、跨域及 SSE 超时。不要将模型或 MCP 密钥写入前端环境变量。

## 工作区能力

- 任意命名的领域知识库树、笔记模板、多标签页和本地布局恢复。
- CodeMirror 6 Markdown 编辑、阅读/分屏、链接补全、版本冲突处理及历史载入。
- 稳定 Wiki 链接、反向链接、属性、大纲与 Markdown 导出。
- G6 局部、多库及个人全景图；跨域边来自明确笔记链接。
- Agent 任务范围、当前笔记引用、五种 Skill、可选领域协作、历史回放、取消和重试。
- 报告原文引用、来源失效提示、保存回知识库。
- 记忆保存/忘记、来源版本状态、MCP 连接授权与文本资源导入。
- 子任务、共享预算、上下文压缩清单、模型调用及 Trace 详情。

账号、资料上传、分片续传、检索解释、RAG 问答与引用原文仍在经典工作台可用。Token 保存在当前浏览器的 localStorage，请按正常登录凭据保护；服务端仍逐次检查所有权。

## 组件结构

| 入口/目录 | 用途 |
|---|---|
| `src/App.vue` | 登录与工作区入口，按需载入工作区或经典页面 |
| `src/components/brain/BrainWorkspace.vue` | 知识树、笔记标签及面板编排 |
| `MarkdownEditor.vue` / `MarkdownPreview.vue` | 编辑、链接补全与 DOMPurify 净化后的预览 |
| `KnowledgeGraph.vue` | G6 布局、领域聚合与选定范围图谱 |
| `AgentPanel.vue` | 创建任务、订阅事件、核验报告与保存成果 |
| `AgentWorkbench.vue` | 记忆、MCP、上下文与子任务管理 |
| `src/LegacyApp.vue` | 原有资料与问答工作台 |
| `src/lib/sse.js` / `workspace.js` | SSE 边界、回放合并、布局、范围与图谱聚合 |

## 流与恢复

Agent 先创建持久化 run，再通过带 Authorization 的 GET 请求订阅 `/api/agent/runs/{id}/events/stream?after=...`，按 seq 重放并去重。`run_end` 才表示本轮订阅完整结束。浏览器断开不取消后台任务；取消需显式调用任务取消 API。

问答继续使用 POST 流式请求；共享解析器处理 UTF-8 分段、CR/LF 边界、认证错误、超大帧、取消和缺失终止事件。模型草稿不会在核验前展示为最终结论。

## 验证与限制

13 项 Node 测试覆盖 SSE、回放、范围、布局与图谱；真实 Chromium 脚本位于 `retrieval-lab/capture_personal_workspace.py` 和 `capture_agent_harness.py`。截图使用实际演示用户和真实 API。

当前使用组件状态与独立工具模块，没有宣称已接入 Pinia 或完整路由系统。生产构建通过，图谱依赖块仍较大；尚无首屏性能基准。默认模型使用明确标注的原文摘录模式，不能代表真实模型研究质量。
