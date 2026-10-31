<script setup>
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { Play, Square, RotateCcw, Download, FilePlus2, Plus, Sparkles, CheckCircle2, LoaderCircle } from 'lucide-vue-next'
import { api, getToken } from '../../api'
import { consumeSse } from '../../lib/sse'
import { mergeEvents, terminalStatuses } from '../../lib/workspace'
import MarkdownPreview from './MarkdownPreview.vue'
import AgentWorkbench from './AgentWorkbench.vue'
const props = defineProps({ knowledgeBaseId: [String, Number], bases: Array, note: Object })
const emit = defineEmits(['open-note', 'saved-note', 'open-source'])
const contextNote = ref(null), collaboration = ref(false)
const goal = ref(''), skill = ref('research'), scopes = ref([]), run = ref(null), events = ref([]), sessions = ref([]), sessionId = ref(null), history = ref([])
const submitting = ref(false), error = ref(''), connection = ref(''), capabilities = ref(null), requestKey = ref(null)
let controller, epoch = 0, stopped = false
const running = computed(() => run.value && !terminalStatuses.has(run.value.status))
const labels = { QUEUED: '等待执行', RUNNING: '执行中', RETRY_WAIT: '等待恢复', SUCCESS: '已完成', ABSTAINED: '资料不足', FAILED: '执行失败', CANCELLED: '已取消', TIMED_OUT: '已超时', BUDGET_EXCEEDED: '预算已用尽' }
const nodeLabels = { PLAN: '制定研究计划', TEAM_DISPATCH: '分配领域研究', WAIT_CHILDREN: '等待子任务汇总', DECIDE: '选择下一步', TOOL: '读取知识与资料', SYNTHESIZE: '整理结论', VERIFY: '核验证据', DONE: '任务结束' }
const timeline = computed(() => events.value.filter(e => ['tool_selected', 'tool_completed', 'tool_rejected', 'node_failed', 'lease_recovered', 'evidence_checked', 'run_finished'].includes(e.type)))
const claims = computed(() => run.value?.artifact?.evidence?.claims || [])
function scopeName(id) { return props.bases?.find(k => String(k.id) === String(id))?.name || '已归档知识库' }
function changeScope(id, event) { const checked = event.target.checked; const ids = new Set(scopes.value.map(String)); checked ? ids.add(String(id)) : ids.delete(String(id)); if (ids.size > 8) { event.target.checked = false; error.value = '一次任务最多选择 8 个知识库'; return } scopes.value = [...ids]; requestKey.value = null }
async function loadSessions() {
  const kb = props.knowledgeBaseId, ticket = epoch
  if (!kb) return
  try { const result = await api.get('/api/agent/sessions', { params: { knowledgeBaseId: kb } }); if (ticket === epoch) sessions.value = result } catch (ex) { if (ticket === epoch) error.value = ex.message }
}
watch(() => props.knowledgeBaseId, () => {
  epoch++; controller?.abort(); run.value = null; events.value = []; sessionId.value = null; history.value = []; contextNote.value = null; scopes.value = props.knowledgeBaseId ? [String(props.knowledgeBaseId)] : []; requestKey.value = null; error.value = ''; loadSessions()
}, { immediate: true })
api.get('/api/agent/capabilities').then(value => { capabilities.value = value }).catch(() => {})
function newSession() { controller?.abort(); epoch++; run.value = null; events.value = []; sessionId.value = null; history.value = []; contextNote.value = null; error.value = ''; requestKey.value = null }
async function selectSession(id) {
  controller?.abort(); const ticket = ++epoch; sessionId.value = id
  try { const result = await api.get(`/api/agent/sessions/${id}/runs`); if (ticket !== epoch) return; history.value = result; if (result.length) await selectRun(result[0].id) } catch (ex) { error.value = ex.message }
}
async function selectRun(id) {
  controller?.abort(); const ticket = ++epoch; error.value = ''
  try {
    const result = await api.get(`/api/agent/runs/${id}`); if (ticket !== epoch) return
    run.value = result; events.value = []; sessionId.value = result.sessionId
    // Replay all durable history even for a finished run.
    let cursor = 0
    while (cursor < result.eventSeq) {
      const page = await api.get(`/api/agent/runs/${id}/events`, { params: { after: cursor } }); if (ticket !== epoch) return
      if (!page.length) break
      events.value = mergeEvents(events.value, page, id); cursor = page.at(-1).seq
    }
    if (!terminalStatuses.has(result.status)) follow(id, ticket)
  } catch (ex) { if (ticket === epoch) error.value = ex.message }
}
async function start() {
  if (!props.knowledgeBaseId || !goal.value.trim() || submitting.value) return
  submitting.value = true; error.value = ''; controller?.abort(); const ticket = ++epoch
  const payload = { knowledgeBaseId: props.knowledgeBaseId, knowledgeBaseIds: scopes.value, goal: goal.value.trim(), skill: skill.value,
    contextNoteId: contextNote.value?.id || null, collaboration: collaboration.value, sessionId: sessionId.value, requestKey: requestKey.value || crypto.randomUUID() }
  requestKey.value = payload.requestKey
  try {
    const created = await api.post('/api/agent/runs', payload); if (ticket !== epoch) return
    run.value = created; sessionId.value = created.sessionId; events.value = []; requestKey.value = null
    loadSessions(); follow(created.id, ticket)
  } catch (ex) { if (ticket === epoch) error.value = ex.message }
  finally { if (ticket === epoch) submitting.value = false }
}
async function follow(id, ticket) {
  controller = new AbortController(); const signal = controller.signal
  while (!signal.aborted && !stopped && ticket === epoch) {
    try {
      connection.value = '实时连接'
      const response = await fetch(`${(import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '')}/api/agent/runs/${id}/events/stream?after=${events.value.at(-1)?.seq || 0}`,
        { headers: { Authorization: getToken() || '' }, signal })
      await consumeSse(response, event => {
        if (ticket !== epoch) return
        if (event.event === 'agent_event') {
          events.value = mergeEvents(events.value, [event.data], id)
          if (event.data.type === 'plan_created') run.value = { ...run.value, plan: event.data.data?.result?.steps || [] }
          if (event.data.type === 'node_started') run.value = { ...run.value, status: 'RUNNING', stage: event.data.data.node }
        }
        if (event.event === 'run_end') run.value = event.data
      }, { signal, terminalEvent: 'run_end' })
      connection.value = '执行记录已保存'; loadSessions(); return
    } catch (ex) {
      if (signal.aborted || ticket !== epoch) return
      connection.value = '连接中断，正在恢复…'
      try {
        const snapshot = await api.get(`/api/agent/runs/${id}`); if (ticket !== epoch) return
        run.value = snapshot
        if (terminalStatuses.has(snapshot.status)) { await selectRun(id); return }
      } catch (failure) { if (ticket === epoch) error.value = failure.message; connection.value = '连接暂不可用，可从历史恢复' }
      await new Promise(resolve => { const timer = setTimeout(resolve, 1800); signal.addEventListener('abort', () => { clearTimeout(timer); resolve() }, { once: true }) })
    }
  }
}
async function cancel() { try { run.value = await api.post(`/api/agent/runs/${run.value.id}/cancel`); controller?.abort(); connection.value = '已取消' } catch (ex) { error.value = ex.message } }
async function retry() {
  try {
    controller?.abort(); const ticket = ++epoch
    const created = await api.post(`/api/agent/runs/${run.value.id}/retry`, { requestKey: crypto.randomUUID() })
    if (ticket !== epoch) return
    run.value = created; events.value = []; follow(created.id, ticket)
  } catch (ex) { error.value = ex.message }
}
async function saveNote() {
  try {
    const note = await api.post(`/api/agent/runs/${run.value.id}/save-note`, { knowledgeBaseId: run.value.knowledgeBaseId })
    emit('saved-note', note)
  } catch (ex) { error.value = ex.message }
}
async function download() {
  try {
    const blob = await api.get(`/api/agent/runs/${run.value.id}/artifact`, { responseType: 'blob' })
    const url = URL.createObjectURL(blob), anchor = document.createElement('a'); anchor.href = url; anchor.download = `studyflow-${run.value.id}.md`; anchor.click(); URL.revokeObjectURL(url)
  } catch (ex) { error.value = ex.message }
}
function source(span) { if (span.chunk?.source === 'NOTE') emit('open-note', span.chunk.chunkId); else emit('open-source', span.chunk) }
function useCurrentNote() { if (props.note) { contextNote.value = { id: props.note.id, title: props.note.title }; goal.value = `围绕「${props.note.title}」整理已有知识，查找关联笔记和资料，生成带引用的报告。`; requestKey.value = null } }
onBeforeUnmount(() => { stopped = true; epoch++; controller?.abort() })
</script>
<template>
  <section class="brain-agent">
    <div class="brain-agent-heading"><Sparkles :size="18"/><strong>知识助手</strong><span class="brain-pill">{{ capabilities?.provider === 'mock' ? '原文摘录模式' : capabilities?.model || 'Agent' }}</span></div>
    <p class="brain-muted">围绕你的知识，整理想法、建立联系、推进研究。</p>
    <div class="brain-agent-history"><select :value="sessionId || ''" aria-label="Agent 历史会话" @change="selectSession($event.target.value)"><option value="" disabled>查看历史任务</option><option v-for="item in sessions" :key="item.id" :value="item.id">{{ item.title }}</option></select><button class="brain-icon" aria-label="新会话" @click="newSession"><Plus :size="16"/></button></div>
    <div v-if="history.length > 1" class="brain-run-picker"><button v-for="item in history" :key="item.id" :class="{ active: item.id === run?.id }" @click="selectRun(item.id)">{{ labels[item.status] }} · {{ item.goal.slice(0, 18) }}</button></div>
    <template v-if="!running">
      <label class="brain-field">任务能力<select v-model="skill" @change="requestKey = null"><option value="research">研究综述</option><option value="compare">主题对比</option><option value="review">复习提纲</option><option value="organize">知识整理</option><option value="coding">代码研究</option></select></label>
      <details class="brain-scope"><summary>使用 {{ scopes.length }} 个知识库</summary><label v-for="kb in bases" :key="kb.id"><input type="checkbox" :checked="scopes.includes(String(kb.id))" :disabled="String(kb.id) === String(knowledgeBaseId)" @change="changeScope(kb.id, $event)">{{ kb.name }}</label></details>
      <label class="brain-team-toggle"><input v-model="collaboration" type="checkbox" @change="requestKey = null"/>分领域协作研究</label><p v-if="collaboration" class="brain-muted">最多 3 个研究子任务，使用所选范围的子集，并共享调用预算。会增加模型调用量。</p>
      <textarea v-model="goal" aria-label="Agent 目标" placeholder="你想研究或整理什么？" rows="4" maxlength="2000" @input="requestKey = null"/>
      <div v-if="contextNote" class="brain-scope-chips"><span>当前引用：{{ contextNote.title }} <button class="brain-icon tiny" aria-label="移除引用笔记" @click="contextNote = null; requestKey = null">×</button></span></div><button v-if="note" class="brain-link-button" @click="useCurrentNote">＋ 引用当前笔记</button>
      <button class="brain-primary full" :disabled="!goal.trim() || !knowledgeBaseId || submitting" @click="start"><Play :size="14"/>{{ submitting ? '正在创建…' : '开始研究' }}</button>
    </template>
    <p v-if="error" class="brain-error" role="alert">{{ error }}</p>
    <div v-if="run" class="brain-run">
      <div class="brain-run-state"><LoaderCircle v-if="running" :size="16" class="brain-spin"/><CheckCircle2 v-else :size="16"/><strong>{{ labels[run.status] || run.status }}</strong><button v-if="running" class="brain-icon" aria-label="取消 Agent 任务" @click="cancel"><Square :size="14"/></button></div>
      <p class="brain-small">{{ run.goal }}</p><div class="brain-scope-chips"><span v-for="id in run.knowledgeBaseIds" :key="id">{{ scopeName(id) }}</span></div>
      <p v-if="running" class="brain-muted">{{ nodeLabels[run.stage] || run.stage }} · {{ connection }}</p>
      <ol v-if="run.plan?.length" class="brain-plan"><li v-for="(step, i) in run.plan" :key="i">{{ step }}</li></ol>
      <details v-if="timeline.length" class="brain-trace"><summary>执行轨迹 · {{ timeline.length }}</summary><article v-for="event in timeline" :key="event.seq"><span class="brain-trace-dot"/><div><strong>{{ event.data?.result?.tool || nodeLabels[event.data?.node] || event.type }}</strong><p>{{ event.data?.result?.summary || event.data?.message || event.data?.result?.status || event.data?.status || '已保存执行检查点' }}</p><details v-if="event.data?.result?.arguments"><summary>调用参数</summary><pre>{{ JSON.stringify(event.data.result.arguments, null, 2) }}</pre></details></div></article></details>
      <p v-if="run.errorMessage" class="brain-error">{{ run.errorMessage }}</p>
      <template v-if="run.artifact">
        <div class="brain-artifact-head"><strong>研究成果</strong><span class="brain-pill">{{ run.artifact.verificationStatus }}</span></div>
        <p v-if="!run.artifact.evidenceCurrent" class="brain-error">来源资料已变化，请重新研究后使用。</p>
        <MarkdownPreview :body="run.artifact.markdown"/>
        <details v-if="claims.length" class="brain-citations"><summary>查看证据原文</summary><template v-for="claim in claims" :key="claim.index"><blockquote v-for="span in claim.evidence" :key="span.sourceId + span.quote"><button class="brain-link-button" @click="source(span)">{{ span.sourceId }} · {{ span.chunk.fileName }}</button><p>{{ span.quote }}</p></blockquote></template></details>
        <div class="brain-actions"><button @click="saveNote"><FilePlus2 :size="14"/>存为笔记</button><button @click="download"><Download :size="14"/>导出</button></div>
      </template>
      <button v-if="terminalStatuses.has(run.status) && run.status !== 'SUCCESS'" class="brain-link-button" @click="retry"><RotateCcw :size="13"/>创建重试任务</button>
    </div>
    <AgentWorkbench :knowledge-base-id="knowledgeBaseId" :run="run" :note="note" @saved-note="emit('saved-note', $event)"/>
  </section>
</template>
