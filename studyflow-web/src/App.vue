<script setup>
import { ref, onMounted, onBeforeUnmount, defineAsyncComponent } from 'vue'
import { Network, ArrowRight, BookOpen, Link2, Sparkles } from 'lucide-vue-next'
import { api, getToken, saveToken, clearToken } from './api'
const BrainWorkspace = defineAsyncComponent(() => import('./components/brain/BrainWorkspace.vue'))
const LegacyApp = defineAsyncComponent(() => import('./LegacyApp.vue'))
const user = ref(null), loading = ref(true), busy = ref(false), error = ref(''), mode = ref('login'), classic = ref(location.hash === '#classic')
const username = ref(''), password = ref(''), nickname = ref('')
const hash = async () => {
  classic.value = location.hash === '#classic'
  if (!classic.value) {
    if (!getToken()) { user.value = null; return }
    user.value = null
    try { user.value = await api.get('/api/auth/me') } catch { clearToken(); user.value = null }
  }
}
onMounted(async () => { window.addEventListener('hashchange', hash); if (getToken()) { try { user.value = await api.get('/api/auth/me') } catch { clearToken() } } loading.value = false })
onBeforeUnmount(() => window.removeEventListener('hashchange', hash))
async function submit() {
  busy.value = true; error.value = ''
  try {
    if (mode.value === 'register') await api.post('/api/auth/register', { username: username.value, password: password.value, nickname: nickname.value || username.value })
    const login = await api.post('/api/auth/login', { username: username.value, password: password.value }); saveToken(login.tokenValue)
    user.value = await api.get('/api/auth/me'); password.value = ''
  } catch (ex) { error.value = ex.message } finally { busy.value = false }
}
async function logout() { try { await api.post('/api/auth/logout') } catch {} clearToken(); user.value = null; location.hash = '#workspace' }
</script>
<template>
  <LegacyApp v-if="classic"/>
  <BrainWorkspace v-else-if="user" :user="user" @logout="logout"/>
  <main v-else class="brain-login"><section class="brain-login-story"><div class="brain-login-brand"><Network :size="28"/><strong>StudyFlow</strong></div><div><span class="brain-eyebrow">A SPACE FOR YOUR MIND</span><h1>让每一个想法，<br/>找到它的连接。</h1><p>把学习、研究、项目与生活，沉淀为属于你的知识。<br/>从笔记出发，让 Agent 与你一起探索。</p><div class="brain-login-features"><span><BookOpen :size="18"/>可扩展知识库</span><span><Link2 :size="18"/>双向链接与图谱</span><span><Sparkles :size="18"/>有证据的 Agent</span></div></div><small>你的知识，始终由你组织。</small></section><section class="brain-login-form"><div class="brain-login-card"><span class="brain-eyebrow">WELCOME TO YOUR WORKSPACE</span><h2>{{ mode === 'login' ? '继续你的思考' : '创建你的知识空间' }}</h2><p>记录、连接、探索，逐渐构建自己的知识体系。</p><form @submit.prevent="submit"><label class="brain-field">用户名<input v-model="username" autocomplete="username" required/></label><label v-if="mode === 'register'" class="brain-field">昵称<input v-model="nickname" autocomplete="nickname"/></label><label class="brain-field">密码<input v-model="password" type="password" :autocomplete="mode === 'login' ? 'current-password' : 'new-password'" required/></label><p v-if="error" class="brain-error" role="alert">{{ error }}</p><button class="brain-primary full" :disabled="busy || loading">{{ loading ? '正在打开…' : busy ? '请稍候…' : mode === 'login' ? '进入工作区' : '创建并进入' }}<ArrowRight :size="16"/></button></form><button class="brain-link-button" @click="mode = mode === 'login' ? 'register' : 'login'; error = ''">{{ mode === 'login' ? '还没有账号？创建一个' : '已有账号？登录' }}</button></div></section></main>
</template>
