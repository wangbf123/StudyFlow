<script setup>
import { ref } from 'vue'
import { ChevronDown, ChevronRight, BookOpen, MoreHorizontal } from 'lucide-vue-next'
defineProps({ nodes: Array, selectedId: [String, Number], depth: { type: Number, default: 0 } })
const emit = defineEmits(['select', 'edit'])
const folded = ref(new Set())
function toggle(id) { const next = new Set(folded.value); next.has(id) ? next.delete(id) : next.add(id); folded.value = next }
</script>
<template>
  <div v-for="node in nodes" :key="node.id" class="brain-tree-branch">
    <div class="brain-tree-row" :class="{ selected: String(selectedId) === String(node.id) }" :style="{ paddingLeft: `${8 + depth * 14}px` }">
      <button class="brain-icon tiny" :aria-label="`${folded.has(node.id) ? '展开' : '折叠'} ${node.name}`" @click="toggle(node.id)"><ChevronRight v-if="folded.has(node.id)" :size="13"/><ChevronDown v-else :size="13"/></button>
      <button class="brain-tree-name" @click="emit('select', node.id)"><BookOpen :size="15" :style="{ color: node.color }"/><span>{{ node.name }}</span></button>
      <button class="brain-icon tiny brain-tree-edit" :aria-label="`设置 ${node.name}`" @click="emit('edit', node)"><MoreHorizontal :size="14"/></button>
    </div>
    <KnowledgeTree v-if="!folded.has(node.id)" :nodes="node.children" :selected-id="selectedId" :depth="depth + 1" @select="emit('select', $event)" @edit="emit('edit', $event)"/>
  </div>
</template>
