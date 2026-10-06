<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowDown, ArrowRight, DataAnalysis, DocumentCopy, Hide, View } from '@element-plus/icons-vue'
import decoration from '@/assets/playground-decoration.png'
import { callOpenApi, type HashAlgorithm, type OpenApiEndpoint } from '@/api/openApi'
import '@/assets/playground.css'

const route = useRoute()
const router = useRouter()
const endpoint = ref<OpenApiEndpoint>(route.query.endpoint === 'uuid' ? 'uuid' : 'hash')
const apiKey = ref('')
const showKey = ref(false)
const keyError = ref('')
const algorithm = ref<HashAlgorithm>('SHA256')
const value = ref('hello')
const byteCount = computed(() => new TextEncoder().encode(value.value).length)
const loading = ref(false)
const examplesOpen = ref(!window.matchMedia('(max-width: 700px)').matches)
const exampleTab = ref<'curl' | 'powershell'>('curl')
const result = ref<{ status: number | null; durationMs: number | null; body: string; message: string } | null>(null)
let controller: AbortController | null = null
let requestId = 0

const docsTarget = computed(() => endpoint.value === 'hash' ? 'hash' : 'uuid')
const endpointPath = computed(() => endpoint.value === 'hash' ? 'POST /v1/utils/hash' : 'GET /v1/utils/uuid')
const sample = computed(() => {
  const url = `http://localhost:8080/v1/utils/${endpoint.value}`
  if (exampleTab.value === 'powershell') {
    return endpoint.value === 'hash'
      ? `$apiKey = Read-Host '输入完整 API Key'\n$body = @{ algorithm = 'SHA256'; value = 'hello' } | ConvertTo-Json -Compress\nInvoke-RestMethod -Uri '${url}' \`\n  -Method Post -Headers @{ Authorization = "ApiKey $apiKey" } \`\n  -ContentType 'application/json; charset=utf-8' -Body $body`
      : `$apiKey = Read-Host '输入完整 API Key'\nInvoke-RestMethod -Uri '${url}' \`\n  -Headers @{ Authorization = "ApiKey $apiKey" }`
  }
  return endpoint.value === 'hash'
    ? `curl -i '${url}' \\\n  -H 'Authorization: ApiKey <完整密钥>' \\\n  -H 'Content-Type: application/json' \\\n  -d '{"algorithm":"SHA256","value":"hello"}'`
    : `curl -i '${url}' \\\n  -H 'Authorization: ApiKey <完整密钥>'`
})

watch(() => route.query.endpoint, (next) => {
  const selected: OpenApiEndpoint = next === 'uuid' ? 'uuid' : 'hash'
  if (selected === endpoint.value) return
  controller?.abort()
  requestId++
  loading.value = false
  result.value = null
  endpoint.value = selected
})

function selectEndpoint(next: OpenApiEndpoint) {
  if (next === endpoint.value) return
  void router.replace({ name: 'playground', query: { endpoint: next } })
}

function resultMessage(status: number, code?: string): string {
  if (status === 401) return 'API Key 无效，请检查是否使用创建时保存的完整 Key。'
  if (status === 403) return 'API Key、所属应用或账号已被禁用。'
  if (code === 'HASH_INPUT_TOO_LARGE') return '输入内容超过 4096 个 UTF-8 字节。'
  if (status === 400) return '请求参数有误，请核对算法与输入内容。'
  return `请求失败（HTTP ${status}），请稍后重试。`
}

async function sendRequest() {
  if (loading.value) return
  const key = apiKey.value.trim()
  keyError.value = ''
  if (!key) {
    keyError.value = '请输入完整 API Key'
    return
  }
  if (endpoint.value === 'hash' && byteCount.value > 4096) return

  const currentId = ++requestId
  controller = new AbortController()
  loading.value = true
  result.value = null
  const start = performance.now()
  try {
    const response = await callOpenApi(endpoint.value, key, algorithm.value, value.value, controller.signal)
    if (currentId !== requestId) return
    const responseText = JSON.stringify(response.body, null, 2).replaceAll(key, '[已隐藏]')
    result.value = {
      status: response.status,
      durationMs: Math.round(performance.now() - start),
      body: responseText,
      message: response.status >= 200 && response.status < 300
        ? '请求成功，以下是服务器返回的真实结果。'
        : resultMessage(response.status, response.body?.code),
    }
  } catch (error) {
    if (currentId !== requestId || (error instanceof DOMException && error.name === 'AbortError')) return
    result.value = {
      status: null,
      durationMs: null,
      body: '',
      message: '无法连接服务器，请确认后端已启动并检查网络。',
    }
  } finally {
    if (currentId === requestId) {
      loading.value = false
      controller = null
    }
  }
}

async function copyExample() {
  try {
    await navigator.clipboard.writeText(sample.value)
    ElMessage.success('示例已复制，请替换占位密钥')
  } catch {
    ElMessage.error('复制失败，请手动选择示例文本')
  }
}

onBeforeUnmount(() => {
  controller?.abort()
  apiKey.value = ''
})
</script>

<template>
  <div class="playground-page">
    <header class="playground-intro">
      <div>
        <p class="playground-eyebrow">NEXUS · OPEN API</p>
        <h1>API 调试台</h1>
        <p>快速调用开放 API，查看真实响应。</p>
      </div>
      <img class="playground-decoration" :src="decoration" alt="" aria-hidden="true" />
    </header>

    <div class="playground-endpoints" role="group" aria-label="选择开放 API">
      <button type="button" :class="{ 'is-active': endpoint === 'uuid' }" :aria-pressed="endpoint === 'uuid'" @click="selectEndpoint('uuid')">
        <el-icon aria-hidden="true"><DocumentCopy /></el-icon>生成 UUID
      </button>
      <button type="button" :class="{ 'is-active': endpoint === 'hash' }" :aria-pressed="endpoint === 'hash'" @click="selectEndpoint('hash')">
        <el-icon aria-hidden="true"><DataAnalysis /></el-icon>计算 Hash
      </button>
    </div>

    <div class="playground-workspace">
      <section class="playground-panel playground-request" aria-labelledby="playground-request-title">
        <div class="playground-panel-heading">
          <h2 id="playground-request-title">请求</h2>
          <code>{{ endpointPath }}</code>
        </div>
        <form novalidate @submit.prevent="sendRequest">
          <div class="playground-field">
            <label for="playground-key">API Key</label>
            <div class="playground-key-wrap">
              <input id="playground-key" v-model="apiKey" :type="showKey ? 'text' : 'password'" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="粘贴创建时保存的完整 API Key" :aria-invalid="Boolean(keyError)" aria-describedby="playground-key-help playground-key-error" @input="keyError = ''" />
              <button type="button" :aria-label="showKey ? '隐藏 API Key' : '显示 API Key'" @click="showKey = !showKey"><el-icon aria-hidden="true"><Hide v-if="showKey" /><View v-else /></el-icon></button>
            </div>
            <small id="playground-key-help">仅在本页临时使用，不保存到浏览器存储或请求示例。</small>
            <small v-if="keyError" id="playground-key-error" class="playground-field-error" role="alert">{{ keyError }}</small>
          </div>

          <template v-if="endpoint === 'hash'">
            <div class="playground-field">
              <label for="playground-algorithm">算法</label>
              <select id="playground-algorithm" v-model="algorithm"><option value="SHA256">SHA256</option><option value="SHA512">SHA512</option></select>
            </div>
            <div class="playground-field">
              <label for="playground-value">待计算的内容</label>
              <textarea id="playground-value" v-model="value" rows="4" spellcheck="false" :aria-invalid="byteCount > 4096" aria-describedby="playground-byte-count" />
              <small id="playground-byte-count" class="playground-byte-count" :class="{ 'is-over-limit': byteCount > 4096 }">{{ byteCount }} / 4096 字节<span v-if="byteCount > 4096"> · 已超出限制</span></small>
            </div>
          </template>
          <p v-else class="playground-uuid-help">此接口不需要请求体，每次发送都会生成一个新 UUID。</p>

          <el-button class="playground-submit" native-type="submit" type="primary" :loading="loading" :disabled="endpoint === 'hash' && byteCount > 4096">{{ loading ? '发送中…' : '发送请求' }}</el-button>
        </form>
      </section>

      <section class="playground-panel playground-response" aria-labelledby="playground-response-title" aria-live="polite">
        <div class="playground-panel-heading">
          <h2 id="playground-response-title">响应</h2>
          <div v-if="result?.status !== null && result?.status !== undefined" class="playground-response-meta">
            <span class="playground-status" :class="{ 'is-error': result.status >= 400 }">{{ result.status }} {{ result.status >= 200 && result.status < 300 ? 'OK' : 'ERROR' }}</span>
            <span v-if="result.durationMs !== null">{{ result.durationMs }} ms</span>
          </div>
        </div>
        <p v-if="result" class="playground-result-message" :class="{ 'is-error': result.status === null || result.status >= 400 }">{{ result.message }}</p>
        <div v-if="result?.body" class="playground-code"><span>JSON</span><pre><code>{{ result.body }}</code></pre></div>
        <div v-else class="playground-response-empty">
          <p v-if="loading">正在等待服务器响应…</p>
          <p v-else-if="!result">填写 API Key 并发送请求后，结果会显示在这里。</p>
          <p v-else>本次请求没有可显示的 JSON 响应。</p>
        </div>
      </section>
    </div>

    <section class="playground-panel playground-examples" aria-labelledby="playground-example-title">
      <div class="playground-example-heading">
        <button type="button" class="playground-example-toggle" :aria-expanded="examplesOpen" aria-controls="playground-example-content" @click="examplesOpen = !examplesOpen">
          <h2 id="playground-example-title">请求示例</h2>
          <el-icon aria-hidden="true" :class="{ 'is-open': examplesOpen }"><ArrowDown /></el-icon>
        </button>
        <RouterLink :to="{ name: 'docs-article', params: { slug: docsTarget } }">查看接口文档 <el-icon aria-hidden="true"><ArrowRight /></el-icon></RouterLink>
      </div>
      <div v-if="examplesOpen" id="playground-example-content">
        <div class="playground-example-tabs" role="group" aria-label="示例命令格式">
          <button type="button" :class="{ 'is-active': exampleTab === 'curl' }" :aria-pressed="exampleTab === 'curl'" @click="exampleTab = 'curl'">curl</button>
          <button type="button" :class="{ 'is-active': exampleTab === 'powershell' }" :aria-pressed="exampleTab === 'powershell'" @click="exampleTab = 'powershell'">PowerShell</button>
        </div>
        <p class="playground-example-note">示例始终使用占位密钥，不会包含你在上方输入的 API Key。本地后端默认端口为 8080。</p>
        <div class="playground-code playground-example-code"><button type="button" @click="copyExample">复制示例</button><pre><code>{{ sample }}</code></pre></div>
      </div>
    </section>
  </div>
</template>
