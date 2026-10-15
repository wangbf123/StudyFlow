<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'
import { ArrowUpRight, FileText, Search, SlidersHorizontal } from 'lucide-vue-next'
import { api } from '../api'
const props = defineProps({ knowledgeBaseId: [String, Number] })
const query = ref(''), mode = ref('RRF'), trace = ref(null), loading = ref(false), error = ref('')
let controller, epoch = 0
watch(() => props.knowledgeBaseId, () => { epoch++; controller?.abort(); trace.value = null; error.value = ''; loading.value = false })
onBeforeUnmount(() => { epoch++; controller?.abort() })
async function search() {
  if (!props.knowledgeBaseId || !query.value.trim()) return
  const current = ++epoch; controller?.abort(); controller = new AbortController()
  loading.value = true; error.value = ''
  try {
    const result = await api.get('/api/search/explain', { signal: controller.signal, params: { knowledgeBaseId: props.knowledgeBaseId, keyword: query.value.trim(), mode: mode.value, size: 10 } })
    if (current === epoch) trace.value = result
  } catch (err) { if (current === epoch) error.value = err.message }
  finally { if (current === epoch) loading.value = false }
}
</script>

<template>
  <section class="search-workbench">
    <header class="search-intro"><span class="eyebrow">SEARCH YOUR LIBRARY</span><h2>不必记住原话，也能找到资料。</h2><p>用关键词或完整问题搜索，再查看召回方式和原文片段。</p></header>
    <form class="knowledge-search" @submit.prevent="search">
      <Search :size="22" />
      <input v-model="query" aria-label="检索问题" maxlength="2000" placeholder="例如：数据库如何处理并发修改？" />
      <el-button type="primary" native-type="submit" :loading="loading">搜索资料 <ArrowUpRight :size="15" /></el-button>
    </form>
    <div class="search-options"><SlidersHorizontal :size="15" /><span>检索方式</span><el-radio-group v-model="mode" size="small"><el-radio-button value="RRF">混合检索</el-radio-button><el-radio-button value="BM25">关键词</el-radio-button><el-radio-button value="VECTOR">语义</el-radio-button></el-radio-group></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <template v-if="trace">
      <el-alert v-if="trace.degradationReason" title="本次无法使用语义检索，已改用关键词检索" type="warning" :closable="false" show-icon />
      <div class="search-summary"><strong>本次展示 {{ trace.hits.length }} 个片段</strong><span>{{ trace.elapsedMs }} ms · {{ trace.effectiveMode }}</span><span v-if="trace.embeddingProvider === 'mock'" class="mock-label">开发向量模式</span></div>
      <article v-for="hit in trace.hits" :key="hit.result.chunkId" class="knowledge-hit">
        <div class="result-meta"><span class="document-icon"><FileText :size="18" /></span><strong>{{ hit.result.fileName }}</strong><span>{{ hit.result.pageNumber ? `第 ${hit.result.pageNumber} 页` : `片段 ${Number(hit.result.chunkIndex) + 1}` }}</span><el-tag size="small" type="info">{{ hit.result.source }}</el-tag></div>
        <p>{{ hit.result.content }}</p>
        <details class="retrieval-details"><summary>查看检索依据</summary><div><span>关键词排名 <b>{{ hit.bm25Rank ?? '未召回' }}</b></span><span>语义排名 <b>{{ hit.vectorRank ?? '未召回' }}</b></span><span>排序分数 <b>{{ hit.result.score }}</b></span></div><small>排名分数用于排序，不代表答案准确率。混合检索按 RRF 合并两路排名。</small></details>
      </article>
      <el-empty v-if="!trace.hits.length" description="没有找到匹配资料，试试更具体的关键词" />
      <details class="retrieval-details trace-footer"><summary>本次检索过程</summary><p>关键词候选 {{ trace.lexicalCandidates }} · 语义候选 {{ trace.vectorCandidates }} · 有效候选 {{ trace.verifiedCandidates }} · 过滤过期或无效片段 {{ trace.discardedCandidates }}</p><small>{{ trace.embeddingModel }} · RRF 常数 {{ trace.rrfConstant }}</small></details>
    </template>
    <div v-else-if="!loading" class="search-empty"><Search :size="38" /><h3>从你的知识库开始探索</h3><p>只搜索当前知识库的资料，并检查文档权限与有效版本。</p></div>
  </section>
</template>
