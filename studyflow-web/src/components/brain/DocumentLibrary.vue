<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'
import { UploadCloud, RefreshCw, FileText } from 'lucide-vue-next'
import { api } from '../../api'
const props = defineProps({ knowledgeBaseId: [String, Number] })
const emit = defineEmits(['open-source'])
const documents = ref([]), busy = ref(false), error = ref(''), more = ref(false)
let cursor = null, epoch = 0, timer
async function load(append = false) {
  const kb = props.knowledgeBaseId, ticket = epoch
  if (!kb) return
  try {
    const result = await api.get(`/api/knowledge-bases/${kb}/document-page`, { params: { size: 30, beforeId: append ? cursor : undefined } })
    if (ticket !== epoch) return
    documents.value = append ? [...documents.value, ...result.items] : result.items; cursor = result.nextCursor; more.value = result.hasMore
    clearTimeout(timer)
    if (documents.value.some(d => ['PENDING', 'PROCESSING'].includes(d.parseStatus))) timer = setTimeout(() => load(), 3000)
  } catch (ex) { if (ticket === epoch) error.value = ex.message }
}
watch(() => props.knowledgeBaseId, () => { epoch++; documents.value = []; cursor = null; clearTimeout(timer); load() }, { immediate: true })
async function upload(event) {
  const file = event.target.files?.[0]; if (!file) return
  const kb = props.knowledgeBaseId, ticket = epoch; busy.value = true; error.value = ''
  try {
    const data = new FormData(); data.append('knowledgeBaseId', kb); data.append('file', file)
    await api.post('/api/documents/upload', data); if (ticket === epoch) await load()
  } catch (ex) { if (ticket === epoch) error.value = ex.message } finally { if (ticket === epoch) busy.value = false; event.target.value = '' }
}
onBeforeUnmount(() => { epoch++; clearTimeout(timer) })
</script>
<template><section class="brain-library"><div class="brain-section-heading"><div><h2>资料库</h2><p>让原始资料成为笔记与研究的依据。</p></div><button class="brain-icon" aria-label="刷新资料" @click="load()"><RefreshCw :size="16"/></button></div><label class="brain-upload"><UploadCloud :size="24"/><span>{{ busy ? '正在上传…' : '选择资料上传' }}</span><small>PDF · Markdown · TXT · 图片</small><input type="file" :disabled="busy || !knowledgeBaseId" accept=".pdf,.md,.txt,.markdown,.png,.jpg,.jpeg,.webp" @change="upload"/></label><p v-if="error" class="brain-error">{{ error }}</p><button v-for="doc in documents" :key="doc.id" class="brain-document" @click="emit('open-source', { documentId: doc.id })"><FileText :size="20"/><span><strong>{{ doc.originalFileName }}</strong><small>{{ doc.parseStatus }} · 摘要 {{ doc.summaryStatus }}</small></span></button><button v-if="more" @click="load(true)">加载更多</button></section></template>
