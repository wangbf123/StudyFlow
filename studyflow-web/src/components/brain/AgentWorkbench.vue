<script setup>
import { ref, watch } from 'vue'
import { api } from '../../api'
const props = defineProps({ knowledgeBaseId: [String, Number], run: Object, note: Object })
const emit = defineEmits(['saved-note'])
const tab = ref('memory'), memories = ref([]), connections = ref([]), architecture = ref(null), diagnostics = ref(null)
const busy = ref(false), error = ref(''), content = ref(''), title = ref(''), kind = ref('PREFERENCE'), resources = ref({})
let epoch = 0
async function load() {
  const ticket = ++epoch; error.value = ''
  if (!props.knowledgeBaseId) return
  try {
    const result = await Promise.all([
      api.get('/api/agent/memories', { params: { knowledgeBaseIds: String(props.knowledgeBaseId) } }),
      api.get('/api/agent/connections'), api.get('/api/agent/architecture'),
      props.run?.id ? api.get(`/api/agent/runs/${props.run.id}/diagnostics`) : Promise.resolve(null)
    ])
    if (ticket !== epoch) return
    ;[memories.value, connections.value, architecture.value, diagnostics.value] = result
  } catch (ex) { if (ticket === epoch) error.value = ex.message }
}
watch(() => [props.knowledgeBaseId, props.run?.id, props.run?.status], load, { immediate: true })
async function perform(action) { if (busy.value) return; busy.value = true; error.value = ''; try { await action(); await load() } catch (ex) { error.value = ex.message } finally { busy.value = false } }
async function remember() {
  await perform(async () => {
    await api.post('/api/agent/memories', { knowledgeBaseId: props.knowledgeBaseId, kind: kind.value, title: title.value.trim(), content: content.value.trim(), sourceNoteId: kind.value === 'PREFERENCE' ? null : props.note?.id, ttlDays: 365 })
    title.value = ''; content.value = ''
  })
}
function forget(id) { perform(() => api.delete(`/api/agent/memories/${id}`)) }
function bind(item) { perform(() => api.put(`/api/agent/connections/${item.key}`, { knowledgeBaseIds: [props.knowledgeBaseId], enabled: !item.binding.enabled })) }
async function listResources(item) { try { const data = await api.get(`/api/agent/connections/${item.key}/resources`); resources.value = { ...resources.value, [item.key]: data.resources || [] } } catch (ex) { error.value = ex.message } }
function importResource(item, resource) { perform(async () => { const note = await api.post(`/api/agent/connections/${item.key}/import`, { knowledgeBaseId: props.knowledgeBaseId, title: resource.name || 'MCP 资料', uri: resource.uri }); emit('saved-note', note) }) }
</script>
<template>
  <details class="brain-workbench" @toggle="$event.target.open && load()">
    <summary>记忆与连接 <span class="brain-pill">{{ memories.length }} 条记忆</span></summary>
    <div class="brain-workbench-tabs" role="tablist" aria-label="Agent 管理">
      <button v-for="item in [{ key: 'memory', label: '记忆' }, { key: 'connections', label: 'MCP 连接' }, { key: 'diagnostics', label: '运行详情' }]" :key="item.key" role="tab" :aria-selected="tab === item.key" :class="{ active: tab === item.key }" @click="tab = item.key">{{ item.label }}</button>
    </div>
    <p v-if="error" class="brain-error" role="alert">{{ error }}</p>
    <div v-if="tab === 'memory'" class="brain-workbench-body">
      <p class="brain-muted">保存偏好与可追溯的知识。来源更新后，相关记忆会停止召回。</p>
      <article v-for="item in memories" :key="item.id" class="brain-memory-card">
        <div><strong>{{ item.title }}</strong><span class="brain-pill">{{ item.current ? ({ PREFERENCE: '偏好', SEMANTIC: '知识', PROCEDURAL: '方法', EPISODIC: '经历' }[item.kind]) : '已失效' }}</span></div><p>{{ item.content }}</p>
        <small v-if="item.sourceNoteId">来源笔记 · 版本 {{ item.sourceRevision }}</small><small v-else-if="item.originRunId">来源：执行记录</small>
        <button class="brain-link-button" :disabled="busy" @click="forget(item.id)">忘记</button>
      </article>
      <p v-if="!memories.length" class="brain-muted">暂时没有记忆。任务完成后会保存执行经历，也可以手动记录。</p>
      <label class="brain-field">记忆类型<select v-model="kind"><option value="PREFERENCE">个人偏好</option><option value="SEMANTIC">知识要点</option><option value="PROCEDURAL">方法与经验</option></select></label>
      <p v-if="kind !== 'PREFERENCE'" class="brain-small">来源：{{ note?.title || '请先打开一篇当前知识库的笔记' }}</p>
      <input v-model="title" aria-label="记忆标题" placeholder="记忆标题" maxlength="160"/>
      <textarea v-model="content" aria-label="记忆内容" placeholder="例如：学习新概念时先给具体例子，再解释原理。" maxlength="4000" rows="3"/>
      <button class="brain-primary full" :disabled="busy || !title.trim() || !content.trim() || (kind !== 'PREFERENCE' && String(note?.knowledgeBaseId) !== String(knowledgeBaseId))" @click="remember">保存记忆</button>
    </div>
    <div v-else-if="tab === 'connections'" class="brain-workbench-body">
      <p class="brain-muted">授权后，助手可以读取连接提供的资料。导入知识库的文本才会成为可引用来源。</p>
      <p v-if="!connections.length" class="brain-muted">尚未配置 MCP 服务。请由部署管理员添加连接和允许使用的工具。</p>
      <article v-for="item in connections" :key="item.key" class="brain-memory-card"><strong>{{ item.key }}</strong><p>{{ item.allowedTools.join(' · ') || '仅资源读取' }}</p>
        <p v-if="item.binding.enabled" class="brain-small">已授权 {{ item.binding.scope?.length || 0 }} 个知识库；再次开启会绑定当前知识库。</p>
        <button :disabled="busy" @click="bind(item)">{{ item.binding.enabled ? '撤销授权' : '授权当前知识库' }}</button>
        <button v-if="item.binding.enabled" :disabled="busy" @click="listResources(item)">浏览资源</button>
        <div v-for="resource in resources[item.key] || []" :key="resource.uri" class="brain-resource-row"><span>{{ resource.name }}</span><button :disabled="busy" @click="importResource(item, resource)">导入</button></div>
      </article>
    </div>
    <div v-else class="brain-workbench-body">
      <p class="brain-small">输入窗口 {{ architecture?.context?.inputTokens }} tokens · 输出预留 {{ architecture?.context?.outputTokens }} tokens</p>
      <p class="brain-muted">Token 估算用于装配与预留，模型返回的用量单独记录。步骤完成状态由 Agent 判断。</p>
      <template v-if="diagnostics">
        <button class="brain-link-button" @click="load">刷新运行详情</button>
        <p class="brain-small">团队共享调用 {{ diagnostics.sharedBudget.calls }} 次 · 已计入额度 {{ diagnostics.sharedBudget.chargedTokens }} tokens · 执行中预留 {{ diagnostics.sharedBudget.reservedTokens }}</p>
        <ol class="brain-plan"><li v-for="step in diagnostics.plan" :key="step.stepNo">{{ step.title }} <span class="brain-pill">{{ step.status === 'AGENT_COMPLETED' ? 'Agent 已确认' : '未单独确认' }}</span></li></ol>
        <article v-for="child in diagnostics.children" :key="child.id" class="brain-memory-card"><strong>研究子任务 · {{ child.knowledgeBaseIds.length }} 个知识库</strong><p>{{ child.status }} · {{ child.modelCalls }} 次模型调用</p></article>
        <details v-for="ctx in diagnostics.contexts" :key="ctx.invocation" class="brain-context-card"><summary>{{ ctx.purpose }} · {{ ctx.manifest.estimatedInputTokens }} tokens</summary><p>压缩 {{ ctx.manifest.compressedResults }} 个结果 · 召回 {{ ctx.manifest.memoryIds.length }} 条记忆</p><p class="brain-small">{{ ctx.manifest.tools.join(' · ') }}</p></details>
        <article v-for="(call, i) in diagnostics.modelCalls" :key="i" class="brain-model-call"><strong>{{ call.purpose }} · {{ call.status }}</strong><small>{{ call.model }} · {{ call.latencyMs }} ms</small><code>{{ call.traceId }}</code></article>
      </template><p v-else class="brain-muted">开始任务后，可查看上下文压缩、子任务、模型调用和 Trace。</p>
    </div>
  </details>
</template>
