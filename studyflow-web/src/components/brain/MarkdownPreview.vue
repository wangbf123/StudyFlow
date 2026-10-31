<script setup>
import { computed } from 'vue'
import { Marked } from 'marked'
import DOMPurify from 'dompurify'
const props = defineProps({ body: { type: String, default: '' }, links: { type: Array, default: () => [] } })
const emit = defineEmits(['open-note', 'unresolved'])
const escape = s => String(s || '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]))
const html = computed(() => {
  const parser = new Marked({ breaks: false, gfm: true })
  parser.use({ renderer: {
    heading(token) { const id = token.text.trim().toLowerCase().replace(/\s+/g, '-'); return `<h${token.depth} id="${escape(id)}">${this.parser.parseInline(token.tokens)}</h${token.depth}>` },
    paragraph(token) {
      const match = /\s+\^([a-zA-Z0-9_-]+)\s*$/.exec(token.text)
      if (!match) return false
      const tokens = token.tokens.map(t => ({ ...t }))
      if (tokens.length && tokens.at(-1).type === 'text') tokens.at(-1).text = tokens.at(-1).text.replace(/\s+\^[a-zA-Z0-9_-]+\s*$/, '')
      return `<p id="${escape(match[1].toLowerCase())}">${this.parser.parseInline(tokens)}</p>`
    }
  }, extensions: [{ name: 'wiki', level: 'inline', start: text => text.indexOf('[['),
    tokenizer(text) { const match = /^\[\[([^\]\n]+)\]\]/.exec(text); return match ? { type: 'wiki', raw: match[0], inner: match[1] } : undefined },
    renderer(token) {
      const [targetAndAnchor, alias] = token.inner.split('|', 2), [target, anchor] = targetAndAnchor.split('#', 2)
      const binding = props.links.find(l => l.targetText === target.trim())
      const directId = /^note:(\d+)$/.exec(target.trim())?.[1]
      const id = binding ? binding.targetId : null
      return `<a href="#" class="brain-wiki ${id ? '' : 'unresolved'}" data-note-id="${escape(id || '')}" data-target="${escape(target.trim())}" data-anchor="${escape(anchor || '')}" title="${escape(binding?.currentTitle || alias || target)}">${escape(alias || binding?.currentTitle || (directId ? '关联笔记' : target))}</a>`
    } }] })
  return DOMPurify.sanitize(parser.parse(props.body), { ADD_ATTR: ['data-note-id', 'data-target', 'data-anchor'], FORBID_TAGS: ['style', 'iframe', 'form', 'input'], FORBID_ATTR: ['style'] })
})
function click(event) {
  const link = event.target.closest('.brain-wiki')
  if (!link) return
  event.preventDefault()
  if (link.dataset.noteId) emit('open-note', link.dataset.noteId, link.dataset.anchor)
  else emit('unresolved', link.dataset.target)
}
</script>
<template><article class="brain-prose" v-html="html" @click="click"/></template>
