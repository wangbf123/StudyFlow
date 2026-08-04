# StudyFlow Web

StudyFlow Web 是知识库平台的 Vue 3 前端，负责账号登录、知识库管理、大文件分片上传、文档解析状态展示、混合检索调试、RAG 问答、引用原文查看和学习 Agent 轨迹回放。

## 技术栈

- Vue 3 Composition API
- Vite 6
- Element Plus
- Axios
- Fetch ReadableStream
- Lucide Vue Next

## 启动

```powershell
npm install
npm run dev
```

页面地址：`http://localhost:5173`。

后端默认地址：`http://localhost:8080`。当前地址集中在 `src/api.js`，部署时可以进一步改造成 Vite 环境变量。

## 页面能力

### 账号

- 注册并自动登录
- 登录、退出
- token 保存到 localStorage
- Axios 请求拦截器自动附加 Authorization

### 知识库与文档

- 创建和切换知识库
- 查看文档解析状态、摘要、页数、解析器和 OCR 耗时
- 支持 PDF、TXT、Markdown 和常见图片格式
- 使用 5 MB 客户端分片上传
- 本地保存 uploadId，并从服务端恢复已上传分片
- 展示上传百分比和 INIT、UPLOADING、MERGING、COMPLETED 等状态

### 搜索与问答

- 展示混合检索结果
- 分别显示 final、BM25、vector 和 rerank 分数
- 展示页码、解析器和 OCR 置信度
- 创建问答会话并加载历史消息
- 支持普通问答和 SSE 流式问答
- 点击 citation 读取对应分片原文

### 学习 Agent

- 输入复合学习目标
- 通过 SSE 实时接收工具调用与执行步骤
- 查看 session 和 run 历史
- 展示最终答案、证据来源和步骤时间线

## SSE 客户端

浏览器端使用 Fetch 发送带 Authorization 请求头的 POST 请求，再读取 `response.body`：

```text
ReadableStream
-> TextDecoder
-> 按空行切分 SSE event
-> 解析 event 与 data
-> 回调更新 Vue 响应式状态
```

问答和 Agent 使用同一套事件解析思路，但对应不同后端路径和事件名称。

## 构建

```powershell
npm run build
npm run preview
```

生产部署建议通过 `VITE_API_BASE_URL` 注入后端地址，并由 Nginx 或网关统一处理 HTTPS、跨域和 SSE 超时。

## 可继续优化

- 将单文件 `App.vue` 拆分为页面、领域组件和 composables。
- 使用 Vue Router 管理登录页、知识库页和任务详情页。
- 使用 Pinia 管理用户、知识库、上传和会话状态。
- 增加分片并发上传、暂停、取消、失败重试和速度估算。
- 增加 Markdown 渲染、代码高亮和引用锚点。
- 为长 Agent 运行增加取消按钮和断线恢复。
- 增加 Vitest 与 Playwright 自动化测试。
