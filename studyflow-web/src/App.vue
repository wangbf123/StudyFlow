<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
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
import { api, clearToken, getToken, saveToken, streamAsk } from './api'

const authenticated = ref(Boolean(getToken()))
const currentUser = ref(null)
const authMode = ref('login')
const authLoading = ref(false)
const appLoading = ref(false)
const uploadLoading = ref(false)
const askLoading = ref(false)
const streamLoading = ref(false)

const authForm = reactive({
  username: 'test001',
  password: '123456',
  nickname: '测试用户'
})

const kbForm = reactive({
  name: '',
  description: ''
})

const searchForm = reactive({
  keyword: 'Redis'
})

const qaForm = reactive({
  question: 'Redis 为什么快？StudyFlow 里 Redis 用来做什么？'
})

const knowledgeBases = ref([])
const selectedKbId = ref(null)
const documents = ref([])
const searchResults = ref([])
const retrieveResult = ref(null)
const qaSessions = ref([])
const selectedSessionId = ref(null)
const messages = ref([])
const uploadFile = ref(null)
const activeTab = ref('documents')
const streamAnswer = ref('')
const streamMeta = ref(null)
const citationDrawerVisible = ref(false)
const citationDetail = ref(null)

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
  try {
    await api.post('/api/auth/logout')
  } catch {
  } finally {
    clearToken()
    authenticated.value = false
    currentUser.value = null
  }
}

async function loadKnowledgeBases() {
  knowledgeBases.value = await api.get('/api/knowledge-bases')
  if (!selectedKbId.value && knowledgeBases.value.length) {
    selectedKbId.value = knowledgeBases.value[0].id
  }
  if (selectedKbId.value) {
    await Promise.all([loadDocuments(), loadSessions()])
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
  searchResults.value = []
  retrieveResult.value = null
  messages.value = []
  selectedSessionId.value = null
  await Promise.all([loadDocuments(), loadSessions()])
}

async function loadDocuments() {
  if (!selectedKbId.value) return
  documents.value = await api.get(`/api/knowledge-bases/${selectedKbId.value}/documents`)
}

function onFileChange(file) {
  uploadFile.value = file.raw
}

async function uploadDocument() {
  if (!selectedKbId.value || !uploadFile.value) {
    ElMessage.warning('请选择知识库和文件')
    return
  }
  try {
    uploadLoading.value = true
    const formData = new FormData()
    formData.append('file', uploadFile.value)
    const result = await api.post(`/api/documents/upload?knowledgeBaseId=${selectedKbId.value}`, formData)
    await waitTask(result.taskId)
    await loadDocuments()
    ElMessage.success('解析完成')
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    uploadLoading.value = false
    uploadFile.value = null
  }
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

async function searchDocuments() {
  if (!canUseKb.value || !searchForm.keyword.trim()) return
  try {
    searchResults.value = await api.get('/api/search', {
      params: {
        knowledgeBaseId: selectedKbId.value,
        keyword: searchForm.keyword.trim(),
        page: 0,
        size: 10
      }
    })
  } catch (error) {
    ElMessage.error(error.message)
  }
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
      }
    )
    await Promise.all([loadSessions(), loadMessages(selectedSessionId.value)])
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    streamLoading.value = false
  }
}

async function loadSessions() {
  if (!selectedKbId.value) return
  qaSessions.value = await api.get('/api/qa/sessions', {
    params: { knowledgeBaseId: selectedKbId.value }
  })
}

async function loadMessages(sessionId) {
  if (!sessionId) return
  selectedSessionId.value = sessionId
  messages.value = await api.get(`/api/qa/sessions/${sessionId}/messages`)
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
          <p>RAG 知识库问答平台</p>
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
          <p>{{ currentUser?.nickname || currentUser?.username }}</p>
        </div>
      </div>
      <el-button :icon="LogOut" @click="logout">退出</el-button>
    </header>

    <aside class="sidebar">
      <div class="sidebar-header">
        <h2>知识库</h2>
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
        <el-tab-pane label="资料" name="documents">
          <section class="panel">
            <div class="panel-head">
              <h3>文档</h3>
              <el-button :icon="RefreshCw" @click="loadDocuments">刷新</el-button>
            </div>
            <div class="upload-row">
              <el-upload :auto-upload="false" :limit="1" :on-change="onFileChange">
                <el-button :icon="UploadCloud">选择文件</el-button>
              </el-upload>
              <el-button type="primary" :loading="uploadLoading" @click="uploadDocument">上传并解析</el-button>
            </div>
            <el-table :data="documents" height="420">
              <el-table-column prop="originalFileName" label="文件名" min-width="210" />
              <el-table-column prop="fileType" label="类型" width="90" />
              <el-table-column label="状态" width="120">
                <template #default="{ row }">
                  <el-tag :type="statusType(row.parseStatus)">{{ row.parseStatus }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="summary" label="摘要" min-width="260" show-overflow-tooltip />
            </el-table>
          </section>
        </el-tab-pane>

        <el-tab-pane label="搜索" name="search">
          <section class="panel">
            <div class="panel-head">
              <h3>全文检索</h3>
              <el-input v-model="searchForm.keyword" class="search-input" placeholder="关键词">
                <template #append>
                  <el-button :icon="Search" @click="searchDocuments" />
                </template>
              </el-input>
            </div>
            <div class="result-list">
              <article v-for="item in searchResults" :key="item.chunkId" class="result-item">
                <div class="result-meta">
                  <FileSearch :size="16" />
                  <strong>{{ item.fileName }}</strong>
                  <span>chunk {{ item.chunkIndex }}</span>
                  <span>score {{ Number(item.score || 0).toFixed(2) }}</span>
                </div>
                <p>{{ item.content }}</p>
              </article>
            </div>
          </section>
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
                <el-input v-model="qaForm.question" type="textarea" :rows="3" resize="none" />
                <div class="ask-actions">
                  <el-button :icon="Search" @click="retrieveChunks">召回</el-button>
                  <el-button type="primary" :icon="Send" :loading="askLoading" @click="ask">问答</el-button>
                  <el-button type="success" :icon="Send" :loading="streamLoading" @click="askStream">流式</el-button>
                </div>
              </div>

              <div v-if="streamAnswer" class="stream-answer">
                <div class="message-role">STREAM</div>
                <p>{{ streamAnswer }}</p>
              </div>

              <div class="messages">
                <article v-for="message in messages" :key="message.id" class="message" :class="message.role.toLowerCase()">
                  <div class="message-role">{{ message.role }}</div>
                  <p>{{ message.content }}</p>
                  <div v-if="message.citations?.length" class="citation-row">
                    <button
                      v-for="citation in message.citations"
                      :key="citation.citationId"
                      class="citation-chip"
                      @click="openCitation(citation.citationId)"
                    >
                      {{ citation.documentName }} / {{ citation.chunkIndex }}
                    </button>
                  </div>
                </article>
              </div>
            </div>

            <div class="panel retrieve">
              <div class="panel-head">
                <h3>召回</h3>
                <span v-if="retrieveResult">{{ retrieveResult.includedCount }}/{{ retrieveResult.hitCount }}</span>
              </div>
              <article v-for="chunk in retrieveResult?.chunks || []" :key="chunk.chunkId" class="chunk-item">
                <div class="result-meta">
                  <strong>{{ chunk.documentName }}</strong>
                  <el-tag size="small" :type="chunk.included ? 'success' : 'info'">
                    {{ chunk.included ? 'included' : 'skipped' }}
                  </el-tag>
                </div>
                <p>{{ chunk.content }}</p>
              </article>
            </div>
          </section>
        </el-tab-pane>
      </el-tabs>
    </section>

    <el-drawer v-model="citationDrawerVisible" title="引用原文" size="42%">
      <div v-if="citationDetail" class="citation-detail">
        <h3>{{ citationDetail.documentName }}</h3>
        <p>chunk {{ citationDetail.chunkIndex }} / score {{ Number(citationDetail.score || 0).toFixed(2) }}</p>
        <pre>{{ citationDetail.content }}</pre>
      </div>
    </el-drawer>
  </main>
</template>
