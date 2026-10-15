<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import KnowledgeOverview from './components/KnowledgeOverview.vue'
import SearchWorkbench from './components/SearchWorkbench.vue'
import { ElMessage } from 'element-plus'
import {
  Bot,
  Database,
  FileSearch,
  FolderPlus,
  LogOut,
  RefreshCw,
  Search,
  Send,
  UploadCloud
} from 'lucide-vue-next'
import { api, clearToken, getToken, saveToken, streamAgent, streamAsk } from './api'

const authenticated = ref(Boolean(getToken()))
const currentUser = ref(null)
const authMode = ref('login')
const authLoading = ref(false)
const appLoading = ref(false)
const uploadLoading = ref(false)
const askLoading = ref(false)
const streamLoading = ref(false)
const agentLoading = ref(false)
const uploadProgress = ref(0)
const uploadStatus = ref('')

const authForm = reactive({
  username: '',
  password: '',
  nickname: ''
})

const kbForm = reactive({
  name: '',
  description: ''
})

const qaForm = reactive({
  question: ''
})

const agentForm = reactive({
  goal: '梳理这个知识库的核心知识，指出最值得优先复习的内容，并给出一份可执行的学习计划。'
})

const knowledgeBases = ref([])
const selectedKbId = ref(null)
const documents = ref([])
const overviewEpoch = ref(0)
const documentCursor = ref(null)
const documentHasMore = ref(false)
const documentFilter = reactive({ keyword: '', status: '' })
let streamController
let agentController
let messageEpoch = 0
onBeforeUnmount(() => { streamController?.abort(); agentController?.abort() })
const retrieveResult = ref(null)
const qaSessions = ref([])
const selectedSessionId = ref(null)
const messages = ref([])
const uploadFile = ref(null)
const activeTab = ref('overview')
const streamAnswer = ref('')
const streamMeta = ref(null)
const citationDrawerVisible = ref(false)
const citationDetail = ref(null)
const agentSessions = ref([])
const selectedAgentSessionId = ref(null)
const agentRuns = ref([])
const selectedAgentRun = ref(null)
const agentSteps = ref([])
const uploadChunkSize = 5 * 1024 * 1024

const selectedKb = computed(() => knowledgeBases.value.find((item) => item.id === selectedKbId.value))
const canUseKb = computed(() => Boolean(selectedKbId.value))

onMounted(async () => {
  if (authenticated.value) {
    try {
      await bootstrap()
    } catch (error) {
      ElMessage.error(error.message)
      clearToken()
      authenticated.value = false
    }
  }
})

async function bootstrap() {
  appLoading.value = true
  try {
    currentUser.value = await api.get('/api/auth/me')
    await loadKnowledgeBases()
  } finally {
    appLoading.value = false
  }
}

async function submitAuth() {
  try {
    authLoading.value = true
    if (authMode.value === 'register') {
      await api.post('/api/auth/register', {
        username: authForm.username,
        password: authForm.password,
        nickname: authForm.nickname
      })
    }
    const login = await api.post('/api/auth/login', {
      username: authForm.username,
      password: authForm.password
    })
    saveToken(login.tokenValue)
    authenticated.value = true
    await bootstrap()
    ElMessage.success('登录成功')
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    authLoading.value = false
  }
}

async function logout() {
  streamController?.abort()
  agentController?.abort()
  try {
    await api.post('/api/auth/logout')
  } catch {
  } finally {
    clearToken()
    authenticated.value = false
    currentUser.value = null
    selectedKbId.value = null
    selectedSessionId.value = null
    knowledgeBases.value = []
    documents.value = []
    messages.value = []
    agentRuns.value = []
  }
}

async function loadKnowledgeBases() {
  knowledgeBases.value = await api.get('/api/knowledge-bases')
  if (!knowledgeBases.value.some(kb => kb.id === selectedKbId.value)) {
    selectedKbId.value = knowledgeBases.value[0]?.id || null
  }
  if (selectedKbId.value) {
    await Promise.all([loadDocuments(), loadSessions(), loadAgentSessions()])
  }
}

async function createKnowledgeBase() {
  if (!kbForm.name.trim()) {
    ElMessage.warning('请输入知识库名称')
    return
  }
  try {
    const kb = await api.post('/api/knowledge-bases', {
      name: kbForm.name.trim(),
      description: kbForm.description.trim()
    })
    kbForm.name = ''
    kbForm.description = ''
    selectedKbId.value = kb.id
    await loadKnowledgeBases()
    ElMessage.success('知识库已创建')
  } catch (error) {
    ElMessage.error(error.message)
  }
}

async function selectKnowledgeBase(id) {
  selectedKbId.value = id
  documentFilter.keyword = ''
  documentFilter.status = ''
  streamController?.abort()
  agentController?.abort()
  messageEpoch++
  retrieveResult.value = null
  messages.value = []
  selectedSessionId.value = null
  selectedAgentSessionId.value = null
  agentRuns.value = []
  selectedAgentRun.value = null
  agentSteps.value = []
  await Promise.all([loadDocuments(), loadSessions(), loadAgentSessions()])
}

async function loadDocuments(append = false) {
  append = append === true
  const kb = selectedKbId.value
  if (!kb) return
  const result = await api.get(`/api/knowledge-bases/${kb}/document-page`, { params: {
    beforeId: append ? documentCursor.value : undefined, size: 20,
    keyword: documentFilter.keyword || undefined, status: documentFilter.status || undefined
  } })
  if (selectedKbId.value !== kb) return
  documents.value = append ? [...new Map([...documents.value, ...result.items].map(doc => [doc.id, doc])).values()] : result.items
  documentCursor.value = result.nextCursor
  documentHasMore.value = result.hasMore
  overviewEpoch.value++
}

async function rebuildDocument(row) {
  try {
    const result = await api.post(`/api/documents/${row.id}/rebuild`, {})
    ElMessage.info('文档正在重建，可刷新查看进度')
    await loadDocuments()
    await waitTask(result.taskId)
    await loadDocuments()
    ElMessage.success('重建完成，旧引用已标记为历史版本')
  } catch (error) {
    ElMessage.error(error.message)
    await loadDocuments()
  }
}

async function deleteDocument(row) {
  try {
    await api.delete(`/api/documents/${row.id}`)
    await loadDocuments()
    streamController?.abort()
    ElMessage.success('文档已删除')
  } catch (error) {
    ElMessage.error(error.message)
  }
}

function onFileChange(file) {
  uploadFile.value = file.raw
  uploadProgress.value = 0
  uploadStatus.value = ''
}

async function uploadDocument() {
  if (!selectedKbId.value || !uploadFile.value) {
    ElMessage.warning('请选择知识库和文件')
    return
  }
  try {
    uploadLoading.value = true
    const result = await uploadDocumentByChunks(uploadFile.value)
    await waitTask(result.taskId)
    await loadDocuments()
    uploadProgress.value = 100
    ElMessage.success('解析完成')
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    uploadLoading.value = false
    uploadFile.value = null
  }
}

async function uploadDocumentByChunks(file) {
  const totalParts = Math.ceil(file.size / uploadChunkSize)
  uploadStatus.value = 'INIT'
  const resumeKey = uploadResumeKey(file)
  let init = await loadUploadProgress(resumeKey, file, totalParts)
  if (!init) {
    init = await api.post('/api/uploads/init', {
      knowledgeBaseId: selectedKbId.value,
      fileName: file.name,
      fileSize: file.size,
      chunkSize: uploadChunkSize,
      totalParts
    })
    if (init.uploadId) {
      localStorage.setItem(resumeKey, init.uploadId)
    }
  }

  if (init.instantUploaded && init.document) {
    uploadStatus.value = 'COMPLETED'
    uploadProgress.value = 100
    localStorage.removeItem(resumeKey)
    return init.document
  }

  const uploadId = init.uploadId
  const uploadedParts = new Set(init.uploadedParts || [])
  uploadProgress.value = init.progress || 0
  uploadStatus.value = init.status || 'UPLOADING'

  for (let partNumber = 1; partNumber <= init.totalParts; partNumber += 1) {
    if (uploadedParts.has(partNumber)) {
      continue
    }
    const start = (partNumber - 1) * init.chunkSize
    const end = Math.min(file.size, start + init.chunkSize)
    const chunk = file.slice(start, end)
    const formData = new FormData()
    formData.append('partNumber', String(partNumber))
    formData.append('file', chunk, file.name)
    const partResult = await api.post(`/api/uploads/${uploadId}/parts`, formData)
    ;(partResult.uploadedParts || []).forEach((item) => uploadedParts.add(item))
    uploadProgress.value = partResult.progress || Math.floor((uploadedParts.size * 100) / init.totalParts)
    uploadStatus.value = 'UPLOADING'
  }

  uploadStatus.value = 'MERGING'
  const completed = await api.post(`/api/uploads/${uploadId}/complete`)
  uploadProgress.value = 100
  uploadStatus.value = 'COMPLETED'
  localStorage.removeItem(resumeKey)
  return completed
}

async function loadUploadProgress(resumeKey, file, totalParts) {
  const uploadId = localStorage.getItem(resumeKey)
  if (!uploadId) return null
  try {
    const progress = await api.get(`/api/uploads/${uploadId}/parts`)
    if (progress.fileSize !== file.size || progress.totalParts !== totalParts) {
      localStorage.removeItem(resumeKey)
      return null
    }
    return {
      uploadId,
      status: progress.status,
      chunkSize: progress.chunkSize,
      totalParts: progress.totalParts,
      uploadedParts: progress.uploadedParts || [],
      progress: progress.progress || 0,
      instantUploaded: false,
      document: progress.document
    }
  } catch {
    localStorage.removeItem(resumeKey)
    return null
  }
}

function uploadResumeKey(file) {
  return `studyflow_upload:${selectedKbId.value}:${file.name}:${file.size}:${file.lastModified || 0}`
}

async function waitTask(taskId) {
  for (let i = 0; i < 30; i += 1) {
    const task = await api.get(`/api/tasks/${taskId}`)
    if (task.status === 'SUCCESS') return task
    if (task.status === 'FAILED') throw new Error(task.errorMessage || '解析失败')
    await sleep(1500)
  }
  throw new Error('解析超时')
}

async function retrieveChunks() {
  if (!canUseKb.value || !qaForm.question.trim()) return
  try {
    retrieveResult.value = await api.post('/api/qa/retrieve', {
      knowledgeBaseId: selectedKbId.value,
      question: qaForm.question.trim()
    })
  } catch (error) {
    ElMessage.error(error.message)
  }
}

async function ask() {
  if (!canUseKb.value || !qaForm.question.trim()) return
  try {
    askLoading.value = true
    const result = await api.post('/api/qa/ask', {
      knowledgeBaseId: selectedKbId.value,
      sessionId: selectedSessionId.value,
      question: qaForm.question.trim()
    })
    selectedSessionId.value = result.sessionId
    await Promise.all([loadSessions(), loadMessages(result.sessionId)])
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    askLoading.value = false
  }
}

async function askStream() {
  if (!canUseKb.value || !qaForm.question.trim()) return
  try {
    streamLoading.value = true
    streamController?.abort()
    streamController = new AbortController()
    streamAnswer.value = ''
    streamMeta.value = null
    await streamAsk(
      {
        knowledgeBaseId: selectedKbId.value,
        sessionId: selectedSessionId.value,
        question: qaForm.question.trim()
      },
      (event) => {
        if (event.event === 'metadata') {
          streamMeta.value = event.data
          selectedSessionId.value = event.data.sessionId
        }
        if (event.event === 'delta') {
          streamAnswer.value += event.data.data || ''
        }
      },
      { signal: streamController.signal }
    )
    await Promise.all([loadSessions(), loadMessages(selectedSessionId.value)])
  } catch (error) {
    if (error.name !== 'AbortError') ElMessage.error(error.message)
  } finally {
    streamLoading.value = false
  }
}

async function loadSessions() {
  const kb = selectedKbId.value
  if (!kb) return
  const rows = await api.get('/api/qa/sessions', { params: { knowledgeBaseId: kb } })
  if (selectedKbId.value === kb) qaSessions.value = rows
}

async function loadMessages(sessionId) {
  if (!sessionId) return
  const epoch = ++messageEpoch, kb = selectedKbId.value
  selectedSessionId.value = sessionId
  const rows = await api.get(`/api/qa/sessions/${sessionId}/messages`)
  if (epoch === messageEpoch && kb === selectedKbId.value) messages.value = rows
}

function evidenceParts(detail) {
  if (!detail || detail.charStart == null || detail.charEnd == null || detail.evidenceState !== 'CURRENT') return null
  const content = detail.content || ''
  return { before: content.slice(0, detail.charStart), quote: content.slice(detail.charStart, detail.charEnd), after: content.slice(detail.charEnd) }
}

async function openCitation(citationId) {
  if (!citationId) return
  try {
    citationDetail.value = await api.get(`/api/qa/citations/${citationId}/chunk`)
    citationDrawerVisible.value = true
  } catch (error) {
    ElMessage.error(error.message)
  }
}

async function loadAgentSessions() {
  if (!selectedKbId.value) return
  agentSessions.value = await api.get('/api/agent/sessions', {
    params: { knowledgeBaseId: selectedKbId.value }
  })
}

async function loadAgentRuns(sessionId) {
  if (!sessionId) return
  selectedAgentSessionId.value = sessionId
  agentRuns.value = await api.get(`/api/agent/sessions/${sessionId}/runs`)
  if (agentRuns.value.length) {
    selectAgentRun(agentRuns.value[0])
  } else {
    selectedAgentRun.value = null
    agentSteps.value = []
  }
}

function selectAgentRun(run) {
  selectedAgentRun.value = run
  agentSteps.value = run?.steps || []
}

async function runAgent() {
  if (!canUseKb.value || !agentForm.goal.trim()) return
  const kb = selectedKbId.value
  try {
    agentController?.abort()
    agentController = new AbortController()
    agentLoading.value = true
    selectedAgentRun.value = null
    agentSteps.value = []
    await streamAgent(
      {
        knowledgeBaseId: selectedKbId.value,
        sessionId: selectedAgentSessionId.value,
        goal: agentForm.goal.trim()
      },
      (event) => {
        if (kb !== selectedKbId.value) return
        if (event.event === 'run_start') {
          selectedAgentSessionId.value = event.data.sessionId
        }
        if (event.event === 'step') {
          agentSteps.value.push(event.data)
        }
        if (event.event === 'run_end') {
          selectedAgentRun.value = event.data
          agentSteps.value = event.data.steps || agentSteps.value
        }
        if (event.event === 'error') {
          ElMessage.error(event.data?.message || 'Agent 执行失败')
        }
      },
      { signal: agentController.signal }
    )
    if (kb !== selectedKbId.value) return
    await Promise.all([loadAgentSessions(), loadAgentRuns(selectedAgentSessionId.value)])
  } catch (error) {
    if (error.name !== 'AbortError') ElMessage.error(error.message)
  } finally {
    agentLoading.value = false
  }
}

function prettyJson(value) {
  if (!value) return ''
  try {
    return JSON.stringify(JSON.parse(value), null, 2)
  } catch {
    return value
  }
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function statusType(status) {
  if (status === 'SUCCESS') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'PROCESSING') return 'warning'
  return 'info'
}
</script>

<template>
  <main v-if="!authenticated" class="auth-screen">
    <section class="auth-panel">
      <div class="brand-row">
        <div class="brand-mark">
          <Bot :size="26" />
        </div>
        <div>
          <h1>StudyFlow</h1>
          <p>你的资料与原文证据，连接在一起</p>
        </div>
      </div>

      <el-tabs v-model="authMode" stretch>
        <el-tab-pane label="登录" name="login" />
        <el-tab-pane label="注册" name="register" />
      </el-tabs>

      <el-form label-position="top" @submit.prevent="submitAuth">
        <el-form-item label="用户名">
          <el-input v-model="authForm.username" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="authForm.password" type="password" autocomplete="current-password" show-password />
        </el-form-item>
        <el-form-item v-if="authMode === 'register'" label="昵称">
          <el-input v-model="authForm.nickname" />
        </el-form-item>
        <el-button type="primary" class="full-button" :loading="authLoading" @click="submitAuth">
          {{ authMode === 'login' ? '登录' : '注册并登录' }}
        </el-button>
      </el-form>
    </section>
  </main>

  <main v-else v-loading="appLoading" class="app-shell">
    <header class="topbar">
      <div class="brand-row compact">
        <div class="brand-mark small">
          <Bot :size="20" />
        </div>
        <div>
          <h1>StudyFlow</h1>
          <p>KNOWLEDGE WORKSPACE</p>
        </div>
      </div>
      <div class="user-chip"><span>{{ currentUser?.nickname || currentUser?.username }}</span><el-button text :icon="LogOut" @click="logout">退出</el-button></div>
    </header>

    <aside class="sidebar">
      <div class="sidebar-header">
        <h2>我的知识空间</h2>
        <el-button :icon="RefreshCw" circle @click="loadKnowledgeBases" />
      </div>
      <div class="kb-list">
        <button
          v-for="kb in knowledgeBases"
          :key="kb.id"
          class="kb-item"
          :class="{ active: kb.id === selectedKbId }"
          @click="selectKnowledgeBase(kb.id)"
        >
          <Database :size="17" />
          <span>{{ kb.name }}</span>
        </button>
      </div>
      <div class="create-kb">
        <el-input v-model="kbForm.name" placeholder="新知识库名称" />
        <el-input v-model="kbForm.description" placeholder="描述" />
        <el-button type="primary" :icon="FolderPlus" @click="createKnowledgeBase">创建</el-button>
      </div>
    </aside>

    <section class="workspace">
      <div class="workspace-title">
        <div>
          <h2>{{ selectedKb?.name || '未选择知识库' }}</h2>
          <p>{{ selectedKb?.description || '创建或选择一个知识库' }}</p>
        </div>
      </div>

      <el-tabs v-model="activeTab" class="work-tabs">
        <el-tab-pane label="知识概览" name="overview">
          <KnowledgeOverview :knowledge-base-id="selectedKbId" :name="selectedKb?.name" :refresh-key="overviewEpoch" @navigate="activeTab = $event" />
        </el-tab-pane>
        <el-tab-pane label="资料" name="documents">
          <section class="panel">
            <div class="panel-head">
              <h3>文档</h3>
              <el-button :icon="RefreshCw" @click="loadDocuments">刷新</el-button>
            </div>
            <div class="ocr-note">
              <strong>让资料成为知识</strong>
              <span>支持 PDF、Markdown、TXT 和图片。扫描件与图片需配置 OCR；摘要单独生成，不影响资料检索。</span>
            </div>
            <div class="upload-row">
              <el-upload
                :auto-upload="false"
                :limit="1"
                accept=".pdf,.txt,.md,.markdown,.jpg,.jpeg,.png,.webp"
                :on-change="onFileChange"
              >
                <el-button :icon="UploadCloud">选择文件</el-button>
              </el-upload>
              <el-button type="primary" :loading="uploadLoading" @click="uploadDocument">上传并解析</el-button>
            </div>
            <div v-if="uploadLoading || uploadProgress > 0" class="upload-progress">
              <el-progress :percentage="uploadProgress" />
              <span>{{ uploadStatus || 'READY' }}</span>
            </div>
            <form class="document-filters" @submit.prevent="loadDocuments()">
              <el-input v-model="documentFilter.keyword" placeholder="按文件名筛选资料" clearable maxlength="200" />
              <el-select v-model="documentFilter.status" placeholder="全部状态" clearable @change="loadDocuments()">
                <el-option label="可检索" value="SUCCESS" /><el-option label="处理中" value="PROCESSING" /><el-option label="等待处理" value="PENDING" /><el-option label="需要处理" value="FAILED" />
              </el-select><el-button native-type="submit">筛选</el-button>
            </form>
            <el-table :data="documents" height="420">
              <el-table-column prop="originalFileName" label="文件名" min-width="210" />
              <el-table-column prop="fileType" label="类型" width="90" />
              <el-table-column label="状态" width="120">
                <template #default="{ row }">
                  <el-tag :type="statusType(row.parseStatus)">{{ row.parseStatus }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="解析方式" width="150">
                <template #default="{ row }">
                  <el-tag v-if="row.parserType" :type="row.ocrPageCount > 0 ? 'warning' : 'info'">
                    {{ row.parserType }}
                  </el-tag>
                  <span v-else>-</span>
                </template>
              </el-table-column>
              <el-table-column label="页数 / OCR" width="130">
                <template #default="{ row }">
                  {{ row.pageCount ?? '-' }} / {{ row.ocrPageCount ?? 0 }}
                  <small v-if="row.ocrElapsedMs"> · {{ row.ocrElapsedMs }}ms</small>
                </template>
              </el-table-column>
              <el-table-column label="摘要" min-width="260">
                <template #default="{ row }"><span v-if="row.summaryStatus === 'SUCCESS'">{{ row.summary }}</span><el-tag v-else-if="['FAILED', 'RETRYING'].includes(row.summaryStatus)" type="warning">摘要暂不可用，资料可继续检索</el-tag><span v-else class="subtle">{{ row.summaryStatus === 'NOT_REQUESTED' ? '重建后可生成摘要' : '正在独立生成摘要' }}</span><small v-if="row.summaryTruncated" class="subtle">（基于前 {{ row.summaryInputChars }} 字符）</small></template>
              </el-table-column>
              <el-table-column label="操作" width="160">
                <template #default="{ row }">
                  <el-button size="small" :disabled="['PENDING', 'PROCESSING'].includes(row.parseStatus)" @click="rebuildDocument(row)">重建</el-button>
                  <el-popconfirm title="删除这份文档及其检索内容？" @confirm="deleteDocument(row)">
                    <template #reference><el-button size="small" type="danger">删除</el-button></template>
                  </el-popconfirm>
                </template>
              </el-table-column>
            </el-table>
            <el-button v-if="documentHasMore" class="load-more" @click="loadDocuments(true)">加载更多资料</el-button>
          </section>
        </el-tab-pane>

        <el-tab-pane label="知识检索" name="search">
          <SearchWorkbench :knowledge-base-id="selectedKbId" />
        </el-tab-pane>

        <el-tab-pane label="问答" name="qa">
          <section class="qa-layout">
            <div class="panel sessions">
              <div class="panel-head">
                <h3>会话</h3>
                <el-button :icon="RefreshCw" circle @click="loadSessions" />
              </div>
              <button
                v-for="session in qaSessions"
                :key="session.id"
                class="session-item"
                :class="{ active: session.id === selectedSessionId }"
                @click="loadMessages(session.id)"
              >
                {{ session.title }}
              </button>
            </div>

            <div class="panel qa-main">
              <div class="ask-box">
                <el-input v-model="qaForm.question" type="textarea" :rows="3" resize="none" aria-label="知识库问题" placeholder="基于当前知识库提问，例如：这些资料如何解释数据库事务？" />
                <div class="ask-actions">
                  <el-button :icon="Search" @click="retrieveChunks">召回</el-button>
                  <el-button type="primary" :icon="Send" :loading="askLoading" @click="ask">问答</el-button>
                  <el-button type="success" :icon="Send" :loading="streamLoading" @click="askStream">流式</el-button>
                  <el-button v-if="streamLoading" @click="streamController?.abort()">停止接收</el-button>
                </div>
              </div>

              <div v-if="streamAnswer" class="stream-answer">
                <div class="message-role">STREAM</div>
                <p>{{ streamAnswer }}</p>
              </div>

              <div class="messages">
                <article v-for="message in messages" :key="message.id" class="message" :class="message.role.toLowerCase()">
                  <div class="message-role">{{ message.role }}</div>
                  <p v-if="!message.claims?.length">{{ message.content }}</p>
                  <small v-if="message.verificationMode === 'EXTRACTIVE_ONLY'">原文摘录</small>
                  <small v-else-if="message.answerStatus === 'SUPPORTED'">结论已核验</small>
                  <small v-else-if="message.answerStatus === 'VERIFICATION_UNAVAILABLE'">核验服务暂不可用</small>
                  <small v-else-if="message.answerStatus === 'ABSTAINED'">证据不足</small>
                  <div v-if="message.claims?.length" class="claim-evidence">
                    <article v-for="claim in message.claims" :key="claim.claimId">
                      <p>{{ claim.text }}</p>
                      <button v-for="evidence in claim.evidence" :key="evidence.citationId" class="citation-chip" @click="openCitation(evidence.citationId)">
                        [{{ evidence.sourceId }}] {{ evidence.documentName }} · {{ evidence.pageNumber ? `第 ${evidence.pageNumber} 页` : `分片 ${evidence.chunkIndex}` }}
                      </button>
                    </article>
                  </div>
                  <div v-if="message.citations?.length && !message.claims?.length" class="citation-row">
                    <button
                      v-for="citation in message.citations"
                      :key="citation.citationId"
                      class="citation-chip"
                      @click="openCitation(citation.citationId)"
                    >
                      {{ citation.documentName }} / {{ citation.pageNumber ? `第 ${citation.pageNumber} 页` : `chunk ${citation.chunkIndex}` }}
                    </button>
                  </div>
                </article>
              </div>
            </div>

            <div class="panel retrieve">
              <div class="panel-head">
                <h3>召回</h3>
                <span v-if="retrieveResult">{{ retrieveResult.strategy }} · {{ retrieveResult.includedCount }}/{{ retrieveResult.hitCount }}</span>
              </div>
              <article v-for="chunk in retrieveResult?.chunks || []" :key="chunk.chunkId" class="chunk-item">
                <div class="result-meta">
                  <strong>{{ chunk.documentName }}</strong>
                  <span v-if="chunk.pageNumber">第 {{ chunk.pageNumber }} 页</span>
                  <span v-if="chunk.parserType">{{ chunk.parserType }}</span>
                  <span v-if="chunk.ocrConfidence != null">OCR {{ Number(chunk.ocrConfidence).toFixed(2) }}</span>
                  <el-tag size="small" :type="chunk.included ? 'success' : 'info'">
                    {{ chunk.included ? 'included' : 'skipped' }}
                  </el-tag>
                  <span>{{ chunk.source || 'BM25' }}</span>
                </div>
                <div class="score-row">
                  <span>Final {{ Number(chunk.score || 0).toFixed(2) }}</span>
                  <span>BM25 {{ Number(chunk.bm25Score || 0).toFixed(2) }}</span>
                  <span>Vector {{ chunk.vectorScore == null ? '-' : Number(chunk.vectorScore).toFixed(2) }}</span>
                  <span>Rerank {{ Number(chunk.rerankScore || 0).toFixed(2) }}</span>
                </div>
                <p>{{ chunk.content }}</p>
              </article>
            </div>
          </section>
        </el-tab-pane>
        <el-tab-pane label="学习 Agent" name="agent">
          <section class="agent-layout">
            <div class="panel agent-sessions">
              <div class="panel-head">
                <h3>Agent 会话</h3>
                <el-button :icon="RefreshCw" circle @click="loadAgentSessions" />
              </div>
              <button v-for="session in agentSessions" :key="session.id" class="session-item"
                :class="{ active: session.id === selectedAgentSessionId }" @click="loadAgentRuns(session.id)">
                {{ session.title }}
              </button>
              <el-divider v-if="agentRuns.length" content-position="left">运行记录</el-divider>
              <button v-for="run in agentRuns" :key="run.id" class="agent-run-item"
                :class="{ active: run.id === selectedAgentRun?.id }" @click="selectAgentRun(run)">
                <span>#{{ run.id }}</span>
                <el-tag size="small" :type="statusType(run.status)">{{ run.status }}</el-tag>
              </button>
            </div>

            <div class="panel agent-main">
              <div class="agent-hero">
                <div class="brand-mark small"><Bot :size="20" /></div>
                <div>
                  <h3>StudyFlow Learning Agent</h3>
                  <p>自主选择检索、资料列表和文档读取工具，并保留完整执行轨迹。</p>
                </div>
              </div>
              <div class="ask-box">
                <el-input v-model="agentForm.goal" type="textarea" :rows="4" resize="vertical"
                  placeholder="描述学习目标，而不只是提出一个事实问题" />
                <div class="ask-actions">
                  <el-button type="primary" :icon="Send" :loading="agentLoading" @click="runAgent">
                    {{ agentLoading ? 'Agent 执行中' : '运行 Agent' }}
                  </el-button>
                  <span class="agent-hint">最多 6 步 · 工具白名单 · 全程可追踪</span>
                </div>
              </div>

              <div v-if="selectedAgentRun" class="agent-answer">
                <div class="agent-answer-head">
                  <span class="message-role">FINAL</span>
                  <span>{{ selectedAgentRun.modelName }} · {{ selectedAgentRun.latencyMs }} ms · {{ selectedAgentRun.totalSteps }} steps</span>
                </div>
                <p>{{ selectedAgentRun.finalAnswer }}</p>
                <div v-if="selectedAgentRun.sources?.length" class="agent-sources">
                  <article v-for="source in selectedAgentRun.sources" :key="source.chunkId" class="agent-source-card">
                    <strong>[C{{ source.chunkId }}] {{ source.documentName }}</strong>
                    <span>chunk {{ source.chunkIndex }} · score {{ Number(source.score || 0).toFixed(2) }}</span>
                    <p>{{ source.contentPreview }}</p>
                  </article>
                </div>
              </div>
              <el-empty v-else-if="!agentLoading" description="输入学习目标，让 Agent 开始工作" />
            </div>

            <div class="panel agent-trace">
              <div class="panel-head">
                <h3>执行轨迹</h3>
                <span>{{ agentSteps.length }} steps</span>
              </div>
              <el-timeline v-if="agentSteps.length">
                <el-timeline-item v-for="step in agentSteps" :key="step.id || step.stepNo"
                  :type="statusType(step.status)" :timestamp="`${step.latencyMs || 0} ms`">
                  <div class="trace-step">
                    <div>
                      <strong>Step {{ step.stepNo }} · {{ step.toolName || step.stepType }}</strong>
                      <el-tag size="small" :type="statusType(step.status)">{{ step.status }}</el-tag>
                    </div>
                    <p>{{ step.reasoningSummary || step.errorMessage }}</p>
                    <el-collapse v-if="step.inputJson || step.outputJson">
                      <el-collapse-item title="查看参数与结果">
                        <pre v-if="step.inputJson">{{ prettyJson(step.inputJson) }}</pre>
                        <pre v-if="step.outputJson">{{ prettyJson(step.outputJson) }}</pre>
                      </el-collapse-item>
                    </el-collapse>
                  </div>
                </el-timeline-item>
              </el-timeline>
              <el-empty v-else description="等待 Agent 步骤" :image-size="70" />
            </div>
          </section>
        </el-tab-pane>
      </el-tabs>
    </section>

    <el-drawer v-model="citationDrawerVisible" title="引用原文" size="42%">
      <div v-if="citationDetail" class="citation-detail">
        <h3>{{ citationDetail.documentName }}</h3>
        <p>
          {{ citationDetail.pageNumber ? `第 ${citationDetail.pageNumber} 页` : `chunk ${citationDetail.chunkIndex}` }}
          / {{ citationDetail.parserType || 'TEXT' }}
          <span v-if="citationDetail.ocrConfidence != null">
            / OCR {{ Number(citationDetail.ocrConfidence).toFixed(2) }}
          </span>
          / score {{ Number(citationDetail.score || 0).toFixed(2) }}
        </p>
        <p v-if="citationDetail.evidenceState === 'STALE_VERSION'">文档已更新，以下为回答时保存的证据摘录。</p>
        <blockquote v-if="citationDetail.quote">{{ citationDetail.quote }}</blockquote>
        <pre v-if="evidenceParts(citationDetail)"><span>{{ evidenceParts(citationDetail).before }}</span><mark>{{ evidenceParts(citationDetail).quote }}</mark><span>{{ evidenceParts(citationDetail).after }}</span></pre>
        <pre v-else>{{ citationDetail.content }}</pre>
      </div>
    </el-drawer>
  </main>
</template>
