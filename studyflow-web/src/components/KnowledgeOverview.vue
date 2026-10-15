<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ArrowUpRight, BookOpen, CheckCheck, FileText, Layers3, RefreshCw, Sparkles } from 'lucide-vue-next'
import { api } from '../api'
const props = defineProps({ knowledgeBaseId: [String, Number], name: String, refreshKey: Number })
const emit = defineEmits(['navigate'])
const data = ref(null), loading = ref(false), error = ref('')
let request, timer, epoch = 0
const coverage = computed(() => data.value?.chunkCount > 0 ? Math.round(Number(data.value.embeddedChunkCount) / Number(data.value.chunkCount) * 100) : 0)
const readyRate = computed(() => data.value?.documentCount > 0 ? Math.round(Number(data.value.readyCount) / Number(data.value.documentCount) * 100) : 0)
const stages = { PARSE: '提取文字', EMBED: '语义编码', INDEX: '资料入库', SUMMARY: '生成摘要', CLEANUP: '清理资料' }
const statuses = { PENDING: '等待处理', PROCESSING: '正在处理', RETRYING: '等待重试', SUCCESS: '已完成', FAILED: '需要处理', CANCELLED: '已取消' }
async function load() {
  const current = ++epoch
  clearTimeout(timer); request?.abort(); request = new AbortController()
  if (!props.knowledgeBaseId) { data.value = null; return }
  loading.value = !data.value; error.value = ''
  try {
    const result = await api.get(`/api/knowledge-bases/${props.knowledgeBaseId}/overview`, { signal: request.signal })
    if (current !== epoch) return
    data.value = result
    if (result.recentTasks.some(t => ['PENDING', 'PROCESSING', 'RETRYING'].includes(t.status))) timer = setTimeout(load, 5000)
  } catch (err) { if (current === epoch) error.value = err.message }
  finally { if (current === epoch) loading.value = false }
}
watch(() => [props.knowledgeBaseId, props.refreshKey], () => { data.value = null; load() }, { immediate: true })
onBeforeUnmount(() => { epoch++; request?.abort(); clearTimeout(timer) })
</script>

<template>
  <section class="knowledge-overview" aria-label="知识库概览">
    <div class="knowledge-hero">
      <div>
        <span class="eyebrow"><span class="live-dot" /> YOUR KNOWLEDGE, CONNECTED</span>
        <h2>把资料，变成随手可查的知识。</h2>
        <p>整理 {{ name || '你的知识库' }} 的资料，搜索相关内容，再回到原文核对每条结论。</p>
        <div class="hero-actions">
          <el-button type="primary" @click="emit('navigate', 'documents')"><FileText :size="16" /> 管理资料</el-button>
          <el-button @click="emit('navigate', 'qa')"><Sparkles :size="16" /> 开始提问 <ArrowUpRight :size="15" /></el-button>
        </div>
      </div>
      <div class="library-illustration" aria-hidden="true">
        <div class="paper paper-back"><span /><span /><span /></div>
        <div class="paper paper-front"><BookOpen :size="26" /><b>Knowledge<br />Library</b><span /><span /></div>
        <div class="connection-badge"><CheckCheck :size="17" /> 原文可查</div>
      </div>
    </div>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <el-skeleton v-if="loading" :rows="5" animated />
    <template v-else-if="data">
      <div class="knowledge-stats">
        <article><FileText :size="20" /><span>资料文档</span><strong>{{ data.documentCount }}</strong><small>{{ data.readyCount }} 份可检索</small></article>
        <article><Layers3 :size="20" /><span>知识分片</span><strong>{{ data.chunkCount }}</strong><small>当前有效版本</small></article>
        <article><CheckCheck :size="20" /><span>原文证据</span><strong>{{ data.evidenceCount }}</strong><small>已保存的当前版本引用</small></article>
        <article><Sparkles :size="20" /><span>向量覆盖</span><strong>{{ coverage }}<em>%</em></strong><small>{{ data.embeddedChunkCount }} 个分片已编码</small></article>
      </div>
      <div class="knowledge-grid">
        <section class="panel library-health">
          <div class="panel-head"><h3>知识库状态</h3><el-button text :icon="RefreshCw" @click="load">刷新</el-button></div>
          <div class="health-value"><strong>{{ readyRate }}<small>%</small></strong><span>资料已就绪</span></div>
          <el-progress :percentage="readyRate" :show-text="false" :stroke-width="8" />
          <div class="health-breakdown"><span><i class="dot ready" /> 可检索 {{ data.readyCount }}</span><span><i class="dot pending" /> 处理中 {{ data.processingCount }}</span><span><i class="dot failed" /> 待处理 {{ data.failedCount }}</span></div>
          <div class="format-chips"><span v-for="format in data.formats" :key="format.type">{{ format.type.toUpperCase() }} <b>{{ format.count }}</b></span><span v-if="!data.formats.length">还没有资料</span></div>
          <p class="subtle">摘要单独生成，失败不会阻止资料检索。已有 {{ data.summaryReadyCount }} 份摘要。</p>
        </section>
        <section class="panel task-timeline">
          <div class="panel-head"><h3>最近处理</h3><span class="subtle">最新 12 个任务</span></div>
          <el-empty v-if="!data.recentTasks.length" description="上传资料后，在这里查看处理进度" :image-size="70" />
          <article v-for="task in data.recentTasks.slice(0, 6)" :key="task.id" class="timeline-item">
            <span class="timeline-icon" :class="task.status.toLowerCase()"><CheckCheck v-if="task.status === 'SUCCESS'" :size="16" /><RefreshCw v-else :size="16" /></span>
            <div><strong>{{ stages[task.stage] || task.stage }}</strong><small>版本 {{ task.generation }}<template v-if="Number(task.retryCount)"> · 第 {{ task.retryCount }} 次重试</template></small></div>
            <span class="task-state" :class="task.status.toLowerCase()">{{ statuses[task.status] || task.status }}</span>
          </article>
        </section>
      </div>
    </template>
    <el-empty v-else-if="!loading && !error" description="创建或选择一个知识库，开始积累资料" />
  </section>
</template>
