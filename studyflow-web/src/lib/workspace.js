export const terminalStatuses = new Set(['SUCCESS', 'ABSTAINED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'BUDGET_EXCEEDED'])
export function mergeEvents(previous, incoming, runId) {
  const events = new Map(previous.filter(e => String(e.runId) === String(runId)).map(e => [e.seq, e]))
  for (const event of incoming) if (String(event.runId) === String(runId) && Number.isInteger(event.seq) && event.seq > 0) events.set(event.seq, event)
  return [...events.values()].sort((a, b) => a.seq - b.seq)
}
export function knowledgeTree(items) {
  const map = new Map(items.map(kb => [String(kb.id), { ...kb, children: [] }]))
  const roots = []
  for (const node of map.values()) {
    const seen = new Set([String(node.id)])
    let ancestor = node.parentId, valid = true
    while (ancestor && map.has(String(ancestor))) {
      if (seen.has(String(ancestor))) { valid = false; break }
      seen.add(String(ancestor)); ancestor = map.get(String(ancestor)).parentId
    }
    const parent = valid && map.get(String(node.parentId))
    if (parent) parent.children.push(node)
    else roots.push(node)
  }
  return roots
}
export function scopeQuery(ids) { return [...new Set(ids.map(String))].map(encodeURIComponent).join(',') }
export function safeLayout(raw) {
  try {
    const value = JSON.parse(raw)
    return { selectedKb: typeof value?.selectedKb === 'string' ? value.selectedKb : null,
      tabs: Array.isArray(value?.tabs) ? value.tabs.filter(id => typeof id === 'string' && /^\d+$/.test(id)).slice(0, 12) : [],
      left: Math.max(190, Math.min(360, Number(value?.left) || 248)), right: Math.max(280, Math.min(520, Number(value?.right) || 340)) }
  } catch { return { selectedKb: null, tabs: [], left: 248, right: 340 } }
}
export function hierarchyGraph(data, bases, scopeIds, expanded = new Set()) {
  const allowed = new Set(scopeIds.map(String)), libraries = bases.filter(k => allowed.has(String(k.id)))
  const byNote = new Map((data?.nodes || []).filter(n => allowed.has(String(n.knowledgeBaseId))).map(n => [n.id, n]))
  const nodes = [{ id: 'person', title: '我的知识空间', kind: 'PERSON', knowledgeBaseId: null, tags: [] }]
  const edges = [], links = new Set()
  for (const kb of libraries) {
    const count = [...byNote.values()].filter(n => String(n.knowledgeBaseId) === String(kb.id)).length
    nodes.push({ id: `k${kb.id}`, title: `${kb.name} · ${count}`, kind: 'SPACE', knowledgeBaseId: kb.id, tags: [] })
    edges.push({ id: `parent:${kb.id}`, source: allowed.has(String(kb.parentId)) ? `k${kb.parentId}` : 'person', target: `k${kb.id}`, type: 'CONTAINS' })
    if (expanded.has(String(kb.id))) for (const n of byNote.values()) if (String(n.knowledgeBaseId) === String(kb.id)) {
      nodes.push(n); edges.push({ id: `member:${n.id}`, source: `k${kb.id}`, target: n.id, type: 'CONTAINS' })
    }
  }
  for (const edge of data?.edges || []) {
    const a = byNote.get(edge.source), b = byNote.get(edge.target)
    if (!a || !b) continue
    const source = expanded.has(String(a.knowledgeBaseId)) ? a.id : `k${a.knowledgeBaseId}`
    const target = expanded.has(String(b.knowledgeBaseId)) ? b.id : `k${b.knowledgeBaseId}`
    if (source === target || links.has(`${source}:${target}`)) continue
    links.add(`${source}:${target}`); edges.push({ id: `bridge:${source}:${target}`, source, target, type: 'WIKILINK' })
  }
  return { ...data, nodes, edges }
}
