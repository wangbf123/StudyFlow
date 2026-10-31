<script setup>
import { ref, reactive, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { BookOpen, Network, FileText, Search, Plus, Settings2, PanelLeftClose, PanelRightClose, PanelLeft, PanelRight, Save, X, Hash, Link2, Sparkles, Clock3, Download, Trash2, LogOut, ChevronRight, Files, Command, CircleHelp } from 'lucide-vue-next'
import { api } from '../../api'
import { knowledgeTree, safeLayout, scopeQuery } from '../../lib/workspace'
import KnowledgeTree from './KnowledgeTree.vue'
import MarkdownEditor from './MarkdownEditor.vue'
import MarkdownPreview from './MarkdownPreview.vue'
import KnowledgeGraph from './KnowledgeGraph.vue'
import AgentPanel from './AgentPanel.vue'
import DocumentLibrary from './DocumentLibrary.vue'
const props = defineProps({ user: Object })
const emit = defineEmits(['logout'])
const storageKey = `studyflow-workspace-${(props.user.userId || props.user.id)}`
const layout = reactive(safeLayout(localStorage.getItem(storageKey)))
const bases = ref([]), selectedKb = ref(layout.selectedKb), notes = ref([]), noteCursor = ref(null), noteMore = ref(false), noteSearch = ref('')
const drafts = reactive(new Map()), tabs = ref([]), activeId = ref(null), view = ref('notes'), mode = ref('split'), panel = ref('links')
const graphLevel = ref('notes')
const leftVisible = ref(window.innerWidth > 800), rightVisible = ref(window.innerWidth > 1100), graph = ref(null), localGraph = ref(null), graphIds = ref([])
const backlinks = ref([]), versions = ref([]), notification = ref(''), loading = ref(false), graphLoading = ref(false)
const kbDialog = ref(false), noteDialog = ref(false), palette = ref(null), paletteQuery = ref(''), searchResults = ref([]), source = ref(null)
const kbForm = reactive({ id: null, name: '', description: '', parentId: '', templateKey: 'blank', color: '#a78bfa' })
const newForm = reactive({ title: '', kind: 'NOTE' })
let noteEpoch = 0, contextEpoch = 0, openEpoch = 0, searchEpoch = 0, graphEpoch = 0, disposed = false, resizeCleanup, notificationTimer
const active = computed(() => drafts.get(String(activeId.value)))
const currentKb = computed(() => bases.value.find(k => String(k.id) === String(selectedKb.value)))
const tree = computed(() => knowledgeTree(bases.value))
const candidates = computed(() => [...new Map([...notes.value, ...[...drafts.values()]].map(n => [String(n.id), { ...n, knowledgeBaseName: bases.value.find(k => k.id === n.knowledgeBaseId)?.name }])).values()])
const outline = computed(() => (active.value?.body || '').split('\n').filter(line => /^#{1,6}\s/.test(line)).map(line => ({ depth: line.match(/^#+/)[0].length, title: line.replace(/^#+\s/, '') })))
const wordCount = computed(() => active.value?.body?.length || 0)
const dirtyCount = computed(() => [...drafts.values()].filter(d => dirty(d)).length)
const commands = computed(() => [{ label: '新建笔记', hint: '记录一个想法', action: () => showNewNote() }, { label: '打开知识图谱', hint: '探索知识之间的联系', action: () => { view.value = 'graph'; loadGraph() } }, { label: '新建知识库', hint: '扩展一个领域', action: () => editKb() }, { label: '打开 Agent', hint: '让知识参与研究', action: () => { rightVisible.value = true; panel.value = 'agent' } }, { label: '打开资料库', hint: '管理原始文档', action: () => { view.value = 'documents' } }].filter(c => c.label.includes(paletteQuery.value)))
function notify(message) { notification.value = message; clearTimeout(notificationTimer); notificationTimer = setTimeout(() => { notification.value = '' }, 5500) }
function signature(n) { return JSON.stringify([n.title, n.body, n._tagsText ?? n.tags?.join(', '), n._propertiesText ?? JSON.stringify(n.properties || {}, null, 2), n.kind]) }
function dirty(n) { return n && signature(n) !== n._saved }
function wrap(note) { const wrapped = { ...note, _tagsText: (note.tags || []).join(', '), _propertiesText: JSON.stringify(note.properties || {}, null, 2), _saving: false }; wrapped._saved = signature(wrapped); return wrapped }
function remember() { if (!disposed) localStorage.setItem(storageKey, JSON.stringify({ ...layout, selectedKb: String(selectedKb.value || ''), tabs: tabs.value.slice(-12) })) }
watch([tabs, selectedKb, () => layout.left, () => layout.right], remember, { deep: true })
onMounted(async () => {
  loading.value = true
  try {
    bases.value = await api.get('/api/knowledge-bases')
    if (!bases.value.some(k => String(k.id) === String(selectedKb.value))) selectedKb.value = bases.value[0]?.id || null
    graphIds.value = selectedKb.value ? [String(selectedKb.value)] : []
    await loadNotes()
    for (const id of layout.tabs.slice(0, 12)) { try { const note = await api.get(`/api/notes/${id}`); drafts.set(String(note.id), wrap(note)); tabs.value.push(String(note.id)) } catch { /* Discard inaccessible saved tabs. */ } }
    if (tabs.value.length) { activeId.value = tabs.value[0]; await loadContext() }
    loadGraph()
  } catch (ex) { notify(ex.message) } finally { loading.value = false }
  window.addEventListener('keydown', keyboard); window.addEventListener('beforeunload', beforeUnload)
})
onBeforeUnmount(() => { disposed = true; noteEpoch++; contextEpoch++; openEpoch++; graphEpoch++; clearTimeout(notificationTimer); resizeCleanup?.(); window.removeEventListener('keydown', keyboard); window.removeEventListener('beforeunload', beforeUnload) })
function beforeUnload(event) { if (dirtyCount.value) { event.preventDefault(); event.returnValue = '' } }
function keyboard(event) {
  if (event.defaultPrevented || !(event.metaKey || event.ctrlKey)) return
  if (event.key.toLowerCase() === 's') { event.preventDefault(); saveNote() }
  if (event.key.toLowerCase() === 'o') { event.preventDefault(); showPalette('search') }
  if (event.key.toLowerCase() === 'p') { event.preventDefault(); showPalette('commands') }
}
async function loadNotes(append = false) {
  const ticket = ++noteEpoch, kb = selectedKb.value
  if (!kb) { notes.value = []; return }
  try {
    const page = await api.get('/api/notes', { params: { knowledgeBaseIds: String(kb), query: noteSearch.value || undefined, size: 50, before: append ? noteCursor.value : undefined } })
    if (disposed || ticket !== noteEpoch) return
    notes.value = append ? [...notes.value, ...page.items] : page.items; noteCursor.value = page.nextCursor; noteMore.value = page.hasMore
  } catch (ex) { if (ticket === noteEpoch) notify(ex.message) }
}
async function selectKb(id) { selectedKb.value = id; noteSearch.value = ''; graphIds.value = [String(id)]; await loadNotes(); loadGraph(); loadContext(); if (window.innerWidth < 800) leftVisible.value = false }
async function openNote(id, anchor) {
  const key = String(id), ticket = ++openEpoch
  try {
    if (!drafts.has(key)) { const note = await api.get(`/api/notes/${key}`); if (disposed || ticket !== openEpoch) return; drafts.set(key, wrap(note)) }
    if (!tabs.value.includes(key)) tabs.value.push(key)
    activeId.value = key; view.value = 'notes'
    const note = drafts.get(key)
    if (String(selectedKb.value) !== String(note.knowledgeBaseId)) { selectedKb.value = note.knowledgeBaseId; graphIds.value = [String(note.knowledgeBaseId)]; noteSearch.value = ''; loadNotes(); loadGraph() }
    loadContext()
    if (anchor) {
      mode.value = 'read'; await nextTick()
      const key = anchor.replace(/^\^/, '').trim().toLowerCase().replace(/\s+/g, '-')
      const target = [...document.querySelectorAll('.brain-note-content .brain-prose [id]')].find(el => el.id === key)
      if (target) target.scrollIntoView({ block: 'start', behavior: 'auto' })
      else notify(`笔记已打开，引用位置可能已变化：${anchor}`)
    }
  } catch (ex) { if (ticket === openEpoch) notify(ex.message) }
}
function closeTab(id) { if (dirty(drafts.get(id))) { notify('这条笔记还有未保存的修改，请先保存。'); return } tabs.value = tabs.value.filter(tab => tab !== id); drafts.delete(id); if (activeId.value === id) { activeId.value = tabs.value.at(-1) || null; loadContext() } }
function showNewNote(title = '') { if (!selectedKb.value) { editKb(); return } newForm.title = title; newForm.kind = ({ project: 'PROJECT', life: 'JOURNAL' })[currentKb.value?.templateKey] || 'NOTE'; noteDialog.value = true }
async function createNote() {
  if (!newForm.title.trim()) return
  try {
    const heading = `# ${newForm.title.trim()}\n\n`
    const templates = { CONCEPT: '## 定义\n\n## 关联知识\n\n## 来源\n', PROJECT: '## 目标\n\n## 下一步\n\n- [ ] \n\n## 决策与成果\n', DECISION: '## 情境\n\n## 选择与依据\n\n## 结果复盘\n', JOURNAL: '## 记录\n\n## 收获\n\n## 下一步\n' }
    const note = await api.post('/api/notes', { knowledgeBaseId: selectedKb.value, title: newForm.title.trim(), body: heading + (templates[newForm.kind] || ''), kind: newForm.kind, revision: 0, tags: [], properties: {} })
    noteDialog.value = false; drafts.set(String(note.id), wrap(note)); await openNote(note.id); loadNotes(); loadGraph()
  } catch (ex) { notify(ex.message) }
}
async function saveNote() {
  const note = active.value
  if (!note || note._saving || !dirty(note)) return
  note._saving = true
  try {
    let properties
    try { properties = JSON.parse(note._propertiesText) } catch { throw new Error('属性需要是有效的 JSON 对象') }
    const payload = { knowledgeBaseId: note.knowledgeBaseId, title: note.title, body: note.body, revision: note.revision, tags: note._tagsText.split(/[,，]/).map(t => t.trim()).filter(Boolean), properties, kind: note.kind }
    const savedSignature = signature(note), saved = await api.put(`/api/notes/${note.id}`, payload)
    if (signature(note) === savedSignature) Object.assign(note, wrap(saved))
    else { note.revision = saved.revision; note.links = saved.links; note._saved = signature(wrap(saved)) }
    loadNotes(); loadGraph(); loadContext(); notify('笔记已保存')
  } catch (ex) { notify(ex.message) } finally { note._saving = false }
}
async function loadContext() {
  const note = active.value, ticket = ++contextEpoch
  backlinks.value = []; versions.value = []; localGraph.value = null
  if (!note) return
  const ids = [...new Set([String(note.knowledgeBaseId), ...graphIds.value])].slice(0, 32)
  try {
    const results = await Promise.all([api.get(`/api/notes/${note.id}/backlinks`, { params: { knowledgeBaseIds: scopeQuery(ids) } }), api.get(`/api/notes/${note.id}/revisions`), api.get('/api/notes/graph', { params: { knowledgeBaseIds: scopeQuery(ids), focus: note.id } })])
    if (disposed || ticket !== contextEpoch) return
    ;[backlinks.value, versions.value, localGraph.value] = results
  } catch (ex) { if (ticket === contextEpoch) notify(ex.message) }
}
async function loadGraph() {
  const ids = graphIds.value, ticket = ++graphEpoch
  if (!ids.length) { graph.value = null; return }
  graphLoading.value = true
  try { const result = await api.get('/api/notes/graph', { params: { knowledgeBaseIds: scopeQuery(ids) } }); if (!disposed && ticket === graphEpoch) graph.value = result }
  catch (ex) { if (ticket === graphEpoch) notify(ex.message) } finally { if (ticket === graphEpoch) graphLoading.value = false }
}
function setGraphScope(id, event) { const checked = event.target.checked; const next = new Set(graphIds.value); checked ? next.add(String(id)) : next.delete(String(id)); if (!next.size || next.size > 32) { event.target.checked = graphIds.value.includes(String(id)); notify('请选择 1 至 32 个知识库'); return } graphIds.value = [...next]; loadGraph(); loadContext() }
function allGraph() { graphLevel.value = 'overview'; graphIds.value = bases.value.slice(0, 32).map(k => String(k.id)); view.value = 'graph'; loadGraph(); loadContext() }
function editKb(kb = null) { Object.assign(kbForm, { id: kb?.id || null, name: kb?.name || '', description: kb?.description || '', parentId: kb?.parentId || '', templateKey: kb?.templateKey || 'blank', color: kb?.color || '#a78bfa' }); kbDialog.value = true }
async function saveKb() {
  try {
    const payload = { name: kbForm.name.trim(), description: kbForm.description, parentId: kbForm.parentId || null, templateKey: kbForm.templateKey, color: kbForm.color }
    const kb = kbForm.id ? await api.put(`/api/knowledge-bases/${kbForm.id}`, payload) : await api.post('/api/knowledge-bases', payload)
    bases.value = await api.get('/api/knowledge-bases'); kbDialog.value = false; await selectKb(kb.id)
  } catch (ex) { notify(ex.message) }
}
async function restore(version) { try { const old = await api.get(`/api/notes/${active.value.id}/revisions/${version}`); Object.assign(active.value, { title: old.title, body: old.body, kind: old.kind, _tagsText: old.tags.join(', '), _propertiesText: JSON.stringify(old.properties, null, 2) }); notify('历史内容已载入编辑器，保存后生成新版本。') } catch (ex) { notify(ex.message) } }
async function deleteNote() {
  const note = active.value
  if (!note || !window.confirm(`将「${note.title}」移出知识库？`)) return
  try { await api.delete(`/api/notes/${note.id}`, { params: { revision: note.revision } }); note._saved = signature(note); closeTab(String(note.id)); loadNotes(); loadGraph() } catch (ex) { notify(ex.message) }
}
async function moveNote(destination) {
  if (!active.value || String(destination) === String(active.value.knowledgeBaseId)) return
  if (dirty(active.value)) { notify('请先保存笔记，再调整归属。'); return }
  try {
    const moved = await api.post(`/api/notes/${active.value.id}/move`, { knowledgeBaseId: destination, revision: active.value.revision })
    drafts.set(String(moved.id), wrap(moved)); await selectKb(moved.knowledgeBaseId); notify('笔记已移动，原有链接保持有效。')
  } catch (ex) { notify(ex.message) }
}
function exportNote() { const n = active.value; if (!n) return; const body = `---\ntitle: ${JSON.stringify(n.title)}\ntags: ${JSON.stringify(n.tags)}\nproperties: ${JSON.stringify(n.properties)}\n---\n\n${n.body}`; const url = URL.createObjectURL(new Blob([body], { type: 'text/markdown;charset=utf-8' })); const a = document.createElement('a'); a.href = url; a.download = `${n.title.replace(/[\\/:*?"<>|]/g, '_')}.md`; a.click(); URL.revokeObjectURL(url) }
function unresolved(target) { if (/^(note:|kb:)/.test(target)) notify('此链接的目标已删除或暂不可访问。'); else showNewNote(target) }
async function openSource(chunk) { try { const [doc, parts] = await Promise.all([api.get(`/api/documents/${chunk.documentId}`), api.get(`/api/documents/${chunk.documentId}/chunks`)]); source.value = { title: doc.originalFileName, chunks: parts, selected: chunk.chunkId } } catch (ex) { notify(ex.message) } }
function savedNote(note) { drafts.set(String(note.id), wrap(note)); openNote(note.id); loadNotes(); loadGraph(); notify('研究成果已存为笔记') }
function showPalette(type) { palette.value = type; paletteQuery.value = ''; searchResults.value = []; if (type === 'search') runSearch() }
async function runSearch() { const ticket = ++searchEpoch; if (!bases.value.length) return; try { const result = await api.get('/api/notes', { params: { knowledgeBaseIds: scopeQuery(bases.value.slice(0, 32).map(k => k.id)), query: paletteQuery.value, size: 40 } }); if (ticket === searchEpoch && palette.value === 'search') searchResults.value = result.items } catch (ex) { notify(ex.message) } }
function resize(event, side) { const initial = event.clientX, width = layout[side]; const move = e => { layout[side] = Math.max(side === 'left' ? 190 : 280, Math.min(side === 'left' ? 360 : 520, width + (side === 'left' ? e.clientX - initial : initial - e.clientX))) }; const end = () => { window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', end); resizeCleanup = null }; window.addEventListener('pointermove', move); window.addEventListener('pointerup', end); resizeCleanup = end }
</script>
<template>
  <main class="brain-shell" :style="{ '--brain-left': `${layout.left}px`, '--brain-right': `${layout.right}px` }">
    <nav class="brain-ribbon" aria-label="工作区导航"><button class="brain-logo" aria-label="StudyFlow 知识工作区" @click="view = 'notes'"><Network :size="24"/></button><button class="brain-icon" :class="{ active: view === 'notes' }" title="笔记" @click="view = 'notes'"><BookOpen :size="20"/></button><button class="brain-icon" title="搜索 · Ctrl O" @click="showPalette('search')"><Search :size="20"/></button><button class="brain-icon" :class="{ active: view === 'graph' }" title="知识图谱" @click="view = 'graph'; loadGraph()"><Network :size="20"/></button><button class="brain-icon" :class="{ active: view === 'documents' }" title="资料" @click="view = 'documents'"><Files :size="20"/></button><button class="brain-icon" title="Agent" @click="rightVisible = true; panel = 'agent'"><Sparkles :size="20"/></button><div class="brain-ribbon-bottom"><button class="brain-icon" title="命令面板 · Ctrl P" @click="showPalette('commands')"><Command :size="19"/></button><button class="brain-icon" aria-label="退出登录" @click="dirtyCount ? notify('请先保存未完成的笔记。') : emit('logout')"><LogOut :size="18"/></button></div></nav>
    <div class="brain-workspace">
      <header class="brain-topbar"><button class="brain-icon" aria-label="切换左侧导航" @click="leftVisible = !leftVisible"><PanelLeft :size="17"/></button><strong>StudyFlow</strong><span class="brain-topbar-divider">/</span><span class="brain-topbar-kb">{{ currentKb?.name || '个人知识空间' }}</span><button class="brain-quick-search" @click="showPalette('search')"><Search :size="14"/><span>搜索你的知识</span><kbd>⌘ O</kbd></button><span class="brain-user">{{ user.nickname || user.username }}</span><button class="brain-icon" aria-label="切换右侧面板" @click="rightVisible = !rightVisible"><PanelRight :size="17"/></button></header>
      <div class="brain-body">
        <aside v-if="leftVisible" class="brain-sidebar"><div class="brain-sidebar-title"><span>知识空间</span><button class="brain-icon" aria-label="新建知识库" @click="editKb()"><Plus :size="16"/></button></div><div class="brain-tree-scroll"><KnowledgeTree :nodes="tree" :selected-id="selectedKb" @select="selectKb" @edit="editKb"/><button v-if="!bases.length" class="brain-link-button" @click="editKb()">＋ 创建第一个知识库</button></div><div class="brain-sidebar-title"><span>{{ currentKb?.name || '笔记' }}</span><button class="brain-icon" aria-label="新建笔记" @click="showNewNote()"><Plus :size="16"/></button></div><div class="brain-note-search"><Search :size="13"/><input v-model="noteSearch" placeholder="筛选笔记…" aria-label="筛选笔记" @input="loadNotes()"/></div><div class="brain-note-list"><button v-for="note in notes" :key="note.id" class="brain-note-row" :class="{ selected: String(note.id) === String(activeId) }" @click="openNote(note.id)"><FileText :size="14"/><span>{{ note.title }}</span><i v-if="dirty(drafts.get(String(note.id)))"/></button><button v-if="noteMore" class="brain-link-button" @click="loadNotes(true)">加载更多</button><p v-if="!notes.length && selectedKb" class="brain-muted brain-pad">还没有笔记。记录一个想法，或上传资料后让 Agent 帮你整理。</p></div><div class="brain-sidebar-footer"><span>{{ bases.length }} 个知识库</span><a href="#classic" @click="dirtyCount && ($event.preventDefault(), notify('请先保存笔记，再打开资料工作台。'))">资料处理与检索 ↗</a></div></aside>
        <div v-if="leftVisible" class="brain-resizer" @pointerdown="resize($event, 'left')"/>
        <section class="brain-center">
          <div class="brain-tabs"><button class="brain-tab" :class="{ active: view === 'graph' }" @click="view = 'graph'; loadGraph()"><Network :size="14"/>知识图谱</button><button v-for="id in tabs" :key="id" class="brain-tab" :class="{ active: view === 'notes' && activeId === id }" @click="openNote(id)"><FileText :size="13"/><span>{{ drafts.get(id)?.title }}</span><i v-if="dirty(drafts.get(id))"/><span class="brain-tab-close" role="button" :aria-label="`关闭 ${drafts.get(id)?.title}`" @click.stop="closeTab(id)"><X :size="12"/></span></button><button class="brain-icon" aria-label="新笔记" @click="showNewNote()"><Plus :size="16"/></button></div>
          <template v-if="view === 'graph'"><div class="brain-graph-heading"><div><small>KNOWLEDGE CONNECTIONS</small><h2>让知识彼此连接</h2><p>{{ graphLoading ? '正在读取知识关系…' : '每一条连接，都能回到一条真实的笔记。' }}</p></div><div class="brain-actions"><details class="brain-graph-scope"><summary>{{ graphIds.length }} 个知识库 ▾</summary><label v-for="kb in bases" :key="kb.id"><input type="checkbox" :checked="graphIds.includes(String(kb.id))" @change="setGraphScope(kb.id, $event)">{{ kb.name }}</label></details><button @click="graphLevel = graphLevel === 'overview' ? 'notes' : 'overview'">{{ graphLevel === 'overview' ? '展开笔记关系' : '领域概览' }}</button><button @click="allGraph">个人全景</button></div></div><KnowledgeGraph :data="graph" :bases="bases" :level="graphLevel" :scope-ids="graphIds" @open-note="openNote" @refresh="loadGraph"/><div class="brain-graph-legend"><span v-for="kb in bases.filter(k => graphIds.includes(String(k.id)))" :key="kb.id"><i :style="{ background: kb.color }"/>{{ kb.name }}</span><span>实线：笔记中的明确链接</span></div></template>
          <DocumentLibrary v-else-if="view === 'documents'" :knowledge-base-id="selectedKb" @open-source="openSource"/>
          <template v-else-if="active"><div class="brain-note-toolbar"><span>{{ bases.find(k => k.id === active.knowledgeBaseId)?.name }} <ChevronRight :size="12"/> {{ active.title }}</span><div class="brain-actions"><button :class="{ active: mode === 'edit' }" @click="mode = 'edit'">编辑</button><button :class="{ active: mode === 'split' }" @click="mode = 'split'">分屏</button><button :class="{ active: mode === 'read' }" @click="mode = 'read'">阅读</button><button class="brain-icon" aria-label="导出笔记" @click="exportNote"><Download :size="15"/></button><button class="brain-primary" :disabled="!dirty(active) || active._saving" @click="saveNote"><Save :size="13"/>{{ active._saving ? '保存中' : '保存' }}</button></div></div><div class="brain-note-title"><input v-model="active.title" aria-label="笔记标题" maxlength="160"/><div class="brain-note-meta"><span>{{ active.kind }}</span><span>版本 {{ active.revision }}</span><span v-for="tag in active._tagsText.split(',').map(t => t.trim()).filter(Boolean)" :key="tag">#{{ tag }}</span><span v-if="dirty(active)" class="brain-unsaved">未保存</span></div></div><div class="brain-note-content" :class="`mode-${mode}`"><MarkdownEditor v-if="mode !== 'read'" :key="active.id" v-model="active.body" :candidates="candidates" @save="saveNote"/><MarkdownPreview v-if="mode !== 'edit'" :key="`${active.id}-preview`" :body="active.body" :links="active.links" @open-note="openNote" @unresolved="unresolved"/></div></template>
          <div v-else class="brain-welcome"><div class="brain-welcome-mark"><Network :size="42"/></div><span class="brain-eyebrow">YOUR CONNECTED KNOWLEDGE</span><h1>把想法，连接成你的世界。</h1><p>从一条笔记开始。让学习、研究、项目与生活，<br/>在同一份知识中积累，也彼此启发。</p><div class="brain-actions"><button class="brain-primary" @click="showNewNote()"><Plus :size="16"/>写下第一条笔记</button><button @click="view = 'graph'; loadGraph()"><Network :size="16"/>探索知识图谱</button></div><div class="brain-welcome-shortcuts"><span><kbd>⌘ O</kbd> 快速打开</span><span><kbd>[[ ]]</kbd> 连接知识</span><span><Sparkles :size="13"/> Agent 辅助研究</span></div></div>
        </section>
        <div v-if="rightVisible" class="brain-resizer" @pointerdown="resize($event, 'right')"/>
        <aside v-if="rightVisible" class="brain-context"><div class="brain-context-tabs"><button :class="{ active: panel === 'links' }" aria-label="关联与反向链接" @click="panel = 'links'"><Link2 :size="16"/></button><button :class="{ active: panel === 'properties' }" aria-label="属性与版本" @click="panel = 'properties'"><Settings2 :size="16"/></button><button :class="{ active: panel === 'agent' }" aria-label="Agent 助手" @click="panel = 'agent'"><Sparkles :size="16"/><span>Agent</span></button></div><div v-show="panel === 'agent'" class="brain-context-scroll"><AgentPanel :knowledge-base-id="selectedKb" :bases="bases" :note="active" @open-note="openNote" @saved-note="savedNote" @open-source="openSource"/></div><div v-if="panel === 'links'" class="brain-context-scroll"><div class="brain-context-section"><h3>局部图谱 <span>{{ localGraph?.nodes?.length || 0 }}</span></h3><KnowledgeGraph v-if="active && localGraph" :data="localGraph" :bases="bases" :focus-id="active.id" compact @open-note="openNote"/><p v-else class="brain-muted">打开一条笔记，探索它附近的知识。</p></div><div class="brain-context-section"><h3>反向链接 <span>{{ backlinks.length }}</span></h3><button v-for="item in backlinks" :key="item.id" class="brain-context-link" @click="openNote(item.id)"><Link2 :size="13"/><span>{{ item.title }}</span></button><p v-if="!backlinks.length" class="brain-muted">还没有笔记链接到这里。</p></div><div class="brain-context-section"><h3>链接到</h3><button v-for="link in active?.links || []" :key="link.ordinal" class="brain-context-link" :class="{ unresolved: !link.targetId }" @click="link.targetId ? openNote(link.targetId, link.anchor) : unresolved(link.targetText)"><FileText :size="13"/><span>{{ link.currentTitle || link.label }}</span><small v-if="!link.targetId">待创建</small></button></div><div class="brain-context-section"><h3>大纲</h3><div v-for="(heading, i) in outline" :key="i" class="brain-outline" :style="{ paddingLeft: `${(heading.depth - 1) * 12}px` }">{{ heading.title }}</div></div></div><div v-if="panel === 'properties'" class="brain-context-scroll brain-pad"><template v-if="active"><h3>笔记属性</h3><label class="brain-field">所属知识库<select :value="active.knowledgeBaseId" @change="moveNote($event.target.value)"><option v-for="kb in bases" :key="kb.id" :value="kb.id">{{ kb.name }}</option></select></label><label class="brain-field">类型<select v-model="active.kind"><option v-for="kind in ['NOTE','CONCEPT','PROJECT','DECISION','JOURNAL','REPORT']" :key="kind">{{ kind }}</option></select></label><label class="brain-field">标签 · 逗号分隔<input v-model="active._tagsText"/></label><label class="brain-field">自定义属性 · JSON<textarea v-model="active._propertiesText" rows="6"/></label><button :disabled="!dirty(active)" @click="saveNote">保存属性</button><h3>版本历史</h3><button v-for="version in versions" :key="version.revision" class="brain-version" @click="restore(version.revision)"><Clock3 :size="13"/>版本 {{ version.revision }}<span>载入编辑器</span></button><button class="brain-danger" @click="deleteNote"><Trash2 :size="13"/>移出知识库</button></template><p v-else class="brain-muted">打开笔记后可编辑属性和查看版本。</p></div></aside>
      </div>
      <footer class="brain-statusbar"><span>{{ notification || (loading ? '正在打开知识空间…' : dirtyCount ? `${dirtyCount} 条笔记有未保存的修改` : '知识已保存') }}</span><div><span>{{ active ? `${wordCount} 字符` : `${graph?.totalNotes || 0} 条知识` }}</span><span>Markdown</span><span>{{ user.nickname || user.username }} 的空间</span></div></footer>
    </div>
    <div v-if="kbDialog" class="brain-modal-backdrop" @click.self="kbDialog = false"><form class="brain-modal" role="dialog" aria-modal="true" aria-label="知识库设置" @submit.prevent="saveKb"><div class="brain-modal-heading"><h2>{{ kbForm.id ? '知识库设置' : '扩展一个知识领域' }}</h2><button type="button" class="brain-icon" @click="kbDialog = false"><X :size="18"/></button></div><p class="brain-muted">知识库可以承载一个领域、一项研究或一个项目。</p><label class="brain-field">名称<input v-model="kbForm.name" autofocus required maxlength="80" placeholder="例如：机器学习、产品研发、旅行…"/></label><label class="brain-field">描述<input v-model="kbForm.description" maxlength="500"/></label><label class="brain-field">上级知识库<select v-model="kbForm.parentId"><option value="">个人空间 · 根目录</option><option v-for="kb in bases.filter(k => k.id !== kbForm.id)" :key="kb.id" :value="kb.id">{{ kb.name }}</option></select></label><div class="brain-form-row"><label class="brain-field">模板<select v-model="kbForm.templateKey"><option value="blank">空白</option><option value="research">研究</option><option value="project">项目</option><option value="learning">学习</option><option value="life">生活</option></select></label><label class="brain-field">图谱颜色<input v-model="kbForm.color" type="color"/></label></div><button class="brain-primary full" type="submit">{{ kbForm.id ? '保存设置' : '创建知识库' }}</button></form></div>
    <div v-if="noteDialog" class="brain-modal-backdrop" @click.self="noteDialog = false"><form class="brain-modal" role="dialog" aria-modal="true" aria-label="新建笔记" @submit.prevent="createNote"><div class="brain-modal-heading"><h2>一个新的想法</h2><button type="button" class="brain-icon" @click="noteDialog = false"><X :size="18"/></button></div><p class="brain-muted">保存在 {{ currentKb?.name }}</p><label class="brain-field">标题<input v-model="newForm.title" autofocus required maxlength="160"/></label><label class="brain-field">笔记模板<select v-model="newForm.kind"><option value="NOTE">空白笔记</option><option value="CONCEPT">概念卡片</option><option value="PROJECT">项目</option><option value="DECISION">决策与复盘</option><option value="JOURNAL">生活记录</option></select></label><button class="brain-primary full" type="submit">创建笔记</button></form></div>
    <div v-if="palette" class="brain-modal-backdrop" @click.self="palette = null"><div class="brain-modal brain-palette" role="dialog" aria-modal="true" aria-label="快速打开"><div class="brain-palette-search"><Search :size="18"/><input v-model="paletteQuery" autofocus :placeholder="palette === 'search' ? '搜索知识库中的笔记（最多 32 个库）…' : '输入命令…'" @input="palette === 'search' && runSearch()" @keydown.esc="palette = null"/><button class="brain-icon" @click="palette = null"><X :size="16"/></button></div><template v-if="palette === 'commands'"><button v-for="command in commands" :key="command.label" class="brain-palette-result" @click="palette = null; command.action()"><strong>{{ command.label }}</strong><small>{{ command.hint }}</small></button></template><template v-else><button v-for="item in searchResults" :key="item.id" class="brain-palette-result" @click="palette = null; openNote(item.id)"><strong>{{ item.title }}</strong><small>{{ bases.find(k => k.id === item.knowledgeBaseId)?.name }}</small></button><p v-if="!searchResults.length" class="brain-muted brain-pad">没有找到匹配的笔记。</p></template></div></div>
    <div v-if="source" class="brain-modal-backdrop" @click.self="source = null"><section class="brain-modal brain-source-modal" role="dialog" aria-modal="true" :aria-label="source.title"><div class="brain-modal-heading"><h2>{{ source.title }}</h2><button class="brain-icon" @click="source = null"><X :size="18"/></button></div><article v-for="chunk in source.chunks" :key="chunk.id" class="brain-source-chunk" :class="{ selected: String(chunk.id) === String(source.selected) }"><small>原文片段 {{ chunk.chunkIndex }}</small><p>{{ chunk.content }}</p></article></section></div>
  </main>
</template>
