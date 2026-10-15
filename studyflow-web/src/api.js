import axios from 'axios'
import { consumeSse } from './lib/sse'

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '')

const TOKEN_KEY = 'studyflow_token'

export const api = axios.create({
  baseURL: API_BASE_URL,
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

async function stream(path, payload, onEvent, terminalEvent, { signal } = {}) {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: getToken() || '' },
    body: JSON.stringify(payload),
    signal
  })
  return consumeSse(response, onEvent, { signal, terminalEvent })
}

export function streamAsk(payload, onEvent, options) {
  return stream('/api/qa/ask/stream', payload, onEvent, 'message_end', options)
}

export function streamAgent(payload, onEvent, options) {
  return stream('/api/agent/runs/stream', payload, onEvent, 'run_end', options)
}
