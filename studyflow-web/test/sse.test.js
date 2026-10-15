import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createSseParser, consumeSse } from '../src/lib/sse.js'

function response(text, type = 'text/event-stream') {
  return new Response(text, { headers: { 'content-type': type } })
}

test('frames split across every CRLF boundary preserve Unicode and multiline JSON', () => {
  const events = []
  const parser = createSseParser((event) => events.push(event))
  for (const char of ': heartbeat\r\nevent: delta\r\ndata: {"text":\r\ndata: "知识库"}\r\n\r\n') parser.feed(char)
  assert.deepEqual(events, [{ event: 'delta', data: { text: '知识库' } }])
})
test('lone CR and LF delimit frames; event names reset between messages', () => {
  const events = []; const parser = createSseParser((e) => events.push(e))
  parser.feed('event: custom\rdata: hello\r\rdata: next\n\n')
  assert.deepEqual(events.map((e) => e.event), ['custom', 'message'])
  parser.feed('event: done\rdata: ok\r\r'); parser.end()
  assert.deepEqual(events.at(-1), { event: 'done', data: 'ok' })
})
test('partial data is not emitted at EOF and oversize input fails', () => {
  const events = []; const parser = createSseParser((e) => events.push(e), { maxEventChars: 20 })
  parser.feed('data: partial\n'); parser.end(); assert.equal(events.length, 0)
  assert.throws(() => parser.feed('data: ' + 'x'.repeat(30)), /大小限制/)
})
test('multibyte UTF-8 split across chunks is decoded before framing', async () => {
  const bytes = new TextEncoder().encode('event: message_end\ndata: "证据"\n\n')
  const body = new ReadableStream({ start(c) { for (const byte of bytes) c.enqueue(Uint8Array.of(byte)); c.close() } })
  const events = []
  await consumeSse(new Response(body, { headers: { 'content-type': 'text/event-stream' } }), (e) => events.push(e), { terminalEvent: 'message_end' })
  assert.equal(events[0].data, '证据')
})
test('truncated streams and server error events reject', async () => {
  await assert.rejects(consumeSse(response('event: delta\ndata: "part"\n\n'), () => {}, { terminalEvent: 'message_end' }), /提前结束/)
  await assert.rejects(consumeSse(response('event: error\ndata: {"message":"核验失败"}\n\n'), () => {}), /核验失败/)
})
test('JSON authentication errors cannot be mistaken for successful streams', async () => {
  await assert.rejects(consumeSse(response('{"code":401,"message":"请先登录"}', 'application/json'), () => {}), /请先登录/)
})
test('aborted stream cancels the reader and rejects with AbortError', async () => {
  const controller = new AbortController(); controller.abort()
  await assert.rejects(consumeSse(response(''), () => {}, { signal: controller.signal }), { name: 'AbortError' })
})

test('terminal event completes and cancels an upstream stream that stays open', { timeout: 1000 }, async () => {
  let cancelled = false
  const bytes = new TextEncoder().encode('event: message_end\ndata: "done"\n\nevent: delta\ndata: "late"\n\n')
  const body = new ReadableStream({ start(c) { c.enqueue(bytes) }, cancel() { cancelled = true } })
  const events = []
  await consumeSse(new Response(body, { headers: { 'content-type': 'text/event-stream' } }), (event) => events.push(event), { terminalEvent: 'message_end' })
  assert.equal(cancelled, true)
  assert.deepEqual(events.map(e => e.event), ['message_end'])
})
