<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { hierarchyGraph } from '../../lib/workspace'
import { Maximize, RefreshCw, Network } from 'lucide-vue-next'
const props = defineProps({ data: Object, bases: Array, focusId: [String, Number], compact: Boolean, level: String, scopeIds: { type: Array, default: () => [] } })
const emit = defineEmits(['open-note', 'refresh'])
const expanded = ref(new Set())
const displayed = computed(() => props.level === 'overview' ? hierarchyGraph(props.data, props.bases || [], props.scopeIds, expanded.value) : props.data)
const host = ref(null), error = ref(''), loading = ref(true)
let graph, observer, disposed = false, rendering = false, rerender = false
async function draw() {
  if (!graph || rendering || disposed) { rerender = true; return }
  rendering = true
  try {
    const color = id => props.bases?.find(k => String(k.id) === String(id))?.color || '#c9b5ec'
    graph.setData({ nodes: (displayed.value?.nodes || []).map(n => ({ id: n.id, data: n, style: { fill: color(n.knowledgeBaseId), stroke: n.id === `n${props.focusId}` ? '#fff' : color(n.knowledgeBaseId), lineWidth: n.id === `n${props.focusId}` ? 3 : 1, size: n.id === `n${props.focusId}` ? 24 : n.kind === 'PERSON' ? 28 : n.kind === 'SPACE' ? 19 : 13, labelText: n.title.length > (props.compact ? 9 : 16) ? n.title.slice(0, props.compact ? 9 : 16) + '…' : n.title, labelFill: '#beb9cf', labelFontSize: props.compact ? 10 : 12, labelPlacement: 'bottom', labelMaxWidth: 120 } })),
      edges: (displayed.value?.edges || []).map(e => ({ id: e.id, source: e.source, target: e.target })) })
    await graph.render()
    if (!disposed && graph.getZoom() > 1.4) await graph.zoomTo(1.4, false)
  } catch (ex) { if (!disposed) error.value = ex.message || '图谱渲染失败' }
  finally { rendering = false; loading.value = false; if (rerender && !disposed) { rerender = false; draw() } }
}
onMounted(async () => {
  try {
    const { Graph } = await import('@antv/g6')
    if (disposed) return
    await nextTick()
    graph = new Graph({ container: host.value, width: Math.max(host.value.clientWidth, 200), height: Math.max(host.value.clientHeight, 160),
      animation: false, autoFit: 'view', padding: props.compact ? 20 : 60,
      layout: { type: 'd3-force', preventOverlap: true, nodeSize: props.compact ? 30 : 50, linkDistance: props.compact ? 80 : 160, nodeStrength: -350, edgeStrength: 0.3, alphaDecay: 0.08, alphaMin: 0.03, animation: false },
      node: { type: 'circle', style: { cursor: 'pointer' } }, edge: { type: 'line', style: { stroke: '#676078', opacity: 0.45, lineWidth: 1 } },
      behaviors: ['drag-canvas', 'zoom-canvas', 'drag-element'] })
    graph.on('node:click', event => { const id = event.target?.id; if (id?.startsWith('n')) emit('open-note', id.slice(1)); else if (id?.startsWith('k')) { const next = new Set(expanded.value), key = id.slice(1); next.has(key) ? next.delete(key) : next.add(key); expanded.value = next } })
    observer = new ResizeObserver(entries => { const { width, height } = entries[0].contentRect; if (graph && width > 0 && height > 0) graph.setSize(width, height) })
    observer.observe(host.value)
    await draw()
  } catch (ex) { error.value = ex.message || '图谱加载失败'; loading.value = false }
})
watch(displayed, draw, { deep: false })
onBeforeUnmount(() => { disposed = true; observer?.disconnect(); if (graph) { graph.destroy(); graph = null } })
</script>
<template>
  <div class="brain-graph" :class="{ compact }">
    <div ref="host" class="brain-graph-canvas"/>
    <div v-if="loading" class="brain-graph-message">正在连接知识…</div>
    <div v-else-if="error" class="brain-graph-message brain-error">{{ error }}</div>
    <div v-else-if="!data?.nodes?.length" class="brain-graph-message"><Network :size="30"/><span>写下第一条笔记，开始连接你的知识</span></div>
    <div v-if="!compact" class="brain-graph-tools"><span>{{ displayed?.nodes?.length || 0 }} 个节点 · {{ displayed?.edges?.length || 0 }} 条关联{{ data?.truncated ? ' · 已限制展示范围' : '' }}</span><button class="brain-icon" aria-label="适应画布" @click="graph?.fitView()"><Maximize :size="16"/></button><button class="brain-icon" aria-label="刷新图谱" @click="emit('refresh')"><RefreshCw :size="16"/></button></div>
  </div>
</template>
