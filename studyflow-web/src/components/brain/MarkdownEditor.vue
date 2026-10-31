<script setup>
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { EditorView, keymap, placeholder, lineNumbers, highlightActiveLine } from '@codemirror/view'
import { EditorState } from '@codemirror/state'
import { markdown } from '@codemirror/lang-markdown'
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands'
import { syntaxHighlighting, defaultHighlightStyle } from '@codemirror/language'
import { autocompletion } from '@codemirror/autocomplete'
const props = defineProps({ modelValue: { type: String, default: '' }, candidates: { type: Array, default: () => [] } })
const emit = defineEmits(['update:modelValue', 'save'])
const host = ref(null)
let view, external = false
onMounted(() => {
  view = new EditorView({ parent: host.value, state: EditorState.create({ doc: props.modelValue, extensions: [
    lineNumbers(), history(), highlightActiveLine(), markdown(), syntaxHighlighting(defaultHighlightStyle), EditorView.lineWrapping,
    placeholder('写下一个想法。输入 [[ 连接另一条知识，或在右侧让 Agent 帮你研究。'),
    keymap.of([{ key: 'Mod-s', run: () => { emit('save'); return true } }, ...defaultKeymap, ...historyKeymap]),
    autocompletion({ override: [(context) => {
      const match = context.matchBefore(/\[\[[^\]\n]*/)
      if (!match) return null
      const query = match.text.slice(2).toLowerCase()
      return { from: match.from, options: props.candidates.filter(n => n.title.toLowerCase().includes(query)).slice(0, 30)
        .map(n => ({ label: n.title, detail: n.knowledgeBaseName || '', type: 'text', apply: `[[note:${n.id}|${n.title}]]` })) }
    }] }),
    EditorView.updateListener.of(update => { if (update.docChanged && !external) emit('update:modelValue', update.state.doc.toString()) }),
    EditorView.theme({ '&': { height: '100%', backgroundColor: 'transparent', color: 'var(--brain-text)' },
      '.cm-content': { fontFamily: 'ui-monospace, SFMono-Regular, Consolas, monospace', fontSize: '14px', lineHeight: '1.9', padding: '20px 0 100px' },
      '.cm-scroller': { overflow: 'auto' }, '.cm-gutters': { backgroundColor: 'transparent', color: '#606075', border: 'none', padding: '20px 4px 0' },
      '.cm-activeLine': { backgroundColor: '#a78bfa08' }, '.cm-activeLineGutter': { backgroundColor: 'transparent' },
      '.cm-cursor': { borderLeftColor: '#c4b5fd' }, '&.cm-focused': { outline: 'none' },
      '.cm-selectionBackground, &.cm-focused .cm-selectionBackground': { backgroundColor: '#8b5cf63b' },
      '.cm-tooltip': { backgroundColor: '#272535', color: '#eceaf6', border: '1px solid #484059' } }, { dark: true })
  ] }) })
})
watch(() => props.modelValue, value => { if (view && value !== view.state.doc.toString()) { external = true; view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: value } }); external = false } })
onBeforeUnmount(() => view?.destroy())
</script>
<template><div ref="host" class="brain-editor" aria-label="Markdown 笔记编辑器"/></template>
