import axios from 'axios'

const TOKEN_KEY = 'studyflow_token'

export const api = axios.create({
  baseURL: 'http://localhost:8080',
  timeout: 120000
})

api.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.Authorization = token
  }
  return config
})

api.interceptors.response.use(
  (response) => {
    const body = response.data
    if (body && typeof body.code !== 'undefined' && body.code !== 0) {
      return Promise.reject(new Error(body.message || '请求失败'))
    }
    return body?.data ?? body
  },
  (error) => Promise.reject(new Error(error.response?.data?.message || error.message || '网络异常'))
)

export function saveToken(token) {
  localStorage.setItem(TOKEN_KEY, token)
}

export function getToken() {
  return localStorage.getItem(TOKEN_KEY)
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY)
}

export async function streamAsk(payload, onEvent) {
  const response = await fetch('http://localhost:8080/api/qa/ask/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: getToken() || ''
    },
    body: JSON.stringify(payload)
  })
  if (!response.ok || !response.body) {
    throw new Error(`流式问答失败：HTTP ${response.status}`)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const events = buffer.split('\n\n')
    buffer = events.pop() || ''
    for (const raw of events) {
      const event = parseSse(raw)
      if (event) onEvent(event)
    }
  }
}

export async function streamAgent(payload, onEvent) {
  const response = await fetch('http://localhost:8080/api/agent/runs/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: getToken() || ''
    },
    body: JSON.stringify(payload)
  })
  if (!response.ok || !response.body) {
    let message = `Agent 执行失败：HTTP ${response.status}`
    try {
      const body = await response.json()
      message = body?.message || message
    } catch {
      // Keep the HTTP fallback when the response is not JSON.
    }
    throw new Error(message)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const events = buffer.split('\n\n')
    buffer = events.pop() || ''
    for (const raw of events) {
      const event = parseSse(raw)
      if (event) onEvent(event)
    }
  }
}

function parseSse(raw) {
  const lines = raw.split('\n')
  let event = 'message'
  const dataLines = []
  for (const line of lines) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
  }
  if (!dataLines.length) return null
  const text = dataLines.join('\n')
  try {
    return { event, data: JSON.parse(text) }
  } catch {
    return { event, data: text }
  }
}
