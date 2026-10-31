import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mergeEvents, knowledgeTree, safeLayout, scopeQuery } from '../src/lib/workspace.js'
test('replayed events deduplicate, preserve order and reject another run', () => {
  const result = mergeEvents([{ runId: '1', seq: 2 }], [{ runId: '1', seq: 1 }, { runId: '1', seq: 2 }, { runId: '2', seq: 3 }], '1')
  assert.deepEqual(result.map(e => e.seq), [1, 2])
})
test('knowledge tree tolerates missing parents and invalid cycles without hiding libraries', () => {
  const tree = knowledgeTree([{ id: '1', parentId: '2' }, { id: '2', parentId: '1' }, { id: '3', parentId: 'missing' }, { id: '4', parentId: '3' }])
  assert.equal(tree.length, 3); assert.equal(tree[2].children[0].id, '4')
})
test('layout restoration accepts only IDs and clamps panel dimensions', () => {
  assert.deepEqual(safeLayout('broken').tabs, [])
  const value = safeLayout('{"tabs":["123","<script>",4],"left":99999,"right":-10}')
  assert.deepEqual(value.tabs, ['123']); assert.equal(value.left, 360); assert.equal(value.right, 280)
})
test('scope query keeps large ID strings intact and removes duplicates', () => {
  assert.equal(scopeQuery(['981234567890123456', '2', '2']), '981234567890123456,2')
})
test('personal graph aggregates only selected libraries and expands their actual notes', async () => {
  const { hierarchyGraph } = await import('../src/lib/workspace.js')
  const data = { nodes: [{ id: 'n1', title: 'a', knowledgeBaseId: '1' }, { id: 'n2', title: 'b', knowledgeBaseId: '2' }, { id: 'n3', title: 'private', knowledgeBaseId: '3' }], edges: [{ source: 'n1', target: 'n2' }, { source: 'n2', target: 'n3' }] }
  const graph = hierarchyGraph(data, [{ id: '1', name: 'A' }, { id: '2', name: 'B' }, { id: '3', name: 'private' }], ['1', '2'], new Set(['1']))
  assert.deepEqual(graph.nodes.map(n => n.id), ['person', 'k1', 'n1', 'k2'])
  assert.ok(graph.edges.some(e => e.source === 'n1' && e.target === 'k2'))
  assert.ok(graph.edges.every(e => e.target !== 'n3' && e.target !== 'k3'))
})
