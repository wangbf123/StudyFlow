/** Incremental SSE framing, independent from fetch/Vue so boundaries are testable. */
export function createSseParser(onEvent, { maxEventChars = 1024 * 1024 } = {}) {
  let pending = ''
  let event = 'message'
  let data = []
  let length = 0
  function line(value) {
    if (!value) {
      if (data.length) {
        const text = data.join('\n')
        let payload
        try { payload = JSON.parse(text) } catch { payload = text }
        onEvent({ event, data: payload })
      }
      event = 'message'; data = []; length = 0
      return
    }
    if (value.startsWith(':')) return
    const colon = value.indexOf(':')
    const field = colon < 0 ? value : value.slice(0, colon)
    let content = colon < 0 ? '' : value.slice(colon + 1)
    if (content.startsWith(' ')) content = content.slice(1)
    if (field === 'event') event = content
    if (field === 'data') data.push(content)
    length += value.length
    if (length > maxEventChars) throw new Error('流式事件超过大小限制')
  }
  return {
    feed(chunk) {
      pending += chunk
      let offset = 0
      for (let i = 0; i < pending.length; i++) {
        if (pending[i] !== '\r' && pending[i] !== '\n') continue
        if (pending[i] === '\r' && i === pending.length - 1) break
        line(pending.slice(offset, i))
        if (pending[i] === '\r' && pending[i + 1] === '\n') i++
        offset = i + 1
      }
      pending = pending.slice(offset)
      if (pending.length + length > maxEventChars) throw new Error('流式事件超过大小限制')
    },
    end() {
      // A trailing CR is a complete delimiter; other unfinished data is discarded.
      if (pending.endsWith('\r')) line(pending.slice(0, -1))
      pending = ''; data = []; length = 0
    }
  }
}

export async function consumeSse(response, onEvent, { signal, terminalEvent } = {}) {
  if (!response.ok || !response.body) throw new Error(`流式请求失败：HTTP ${response.status}`)
  if (!response.headers.get('content-type')?.toLowerCase().includes('text/event-stream')) {
    let message = '服务器未返回事件流，请检查登录状态'
    try { message = (await response.json())?.message || message } catch { /* Keep fallback. */ }
    throw new Error(message)
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let finished = false
  let failed = null
  const parser = createSseParser((event) => {
    if (finished || failed) return
    if (event.event === 'error') failed = new Error(event.data?.message || event.data?.data || '流式处理失败')
    if (event.event === terminalEvent) finished = true
    onEvent(event)
  })
  const abort = () => reader.cancel().catch(() => {})
  signal?.addEventListener('abort', abort, { once: true })
  try {
    if (signal?.aborted) throw new DOMException('已取消', 'AbortError')
    while (true) {
      const { value, done } = await reader.read()
      if (signal?.aborted) throw new DOMException('已取消', 'AbortError')
      if (done) break
      parser.feed(decoder.decode(value, { stream: true }))
      if (failed) throw failed
      if (finished) break
    }
    parser.feed(decoder.decode())
    parser.end()
    if (failed) throw failed
    if (terminalEvent && !finished) throw new Error('连接提前结束，请查看历史记录后重试')
  } finally {
    signal?.removeEventListener('abort', abort)
    await reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}
