<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, ArrowRight, Camera, DataAnalysis, Document, DocumentCopy, Link } from '@element-plus/icons-vue'
import decoration from '@/assets/playground-decoration.png'
import {
  callOpenApi, callWebExtract, callWebScreenshot, getScreenshotJob, getScreenshotJobResult,
  isWebExtractResponse, submitScreenshotJob, type CreatedScreenshotJob, type HashAlgorithm,
  type OpenApiEndpoint, type ScreenshotJob, type WebExtractResponse,
} from '@/api/openApi'
import { callShortLinkOpenApi, publicShortLinkUrl, type ShortLink, type ShortLinkOperation } from '@/api/shortLinks'
import WebExtractPreview from './WebExtractPreview.vue'
import ScreenshotJobStatus from './ScreenshotJobStatus.vue'
import ScreenshotPreview from './ScreenshotPreview.vue'
import '@/assets/playground.css'

type PlaygroundEndpoint = OpenApiEndpoint | 'shortlink' | 'web-extract' | 'web-screenshot'
type ScreenshotMode = 'sync' | 'async'
type Result = { status: number | null; durationMs: number | null; body: string; message: string; invalidImage?: boolean; isError?: boolean }
const allowedHosts = new Set(['www.douyin.com', 'v.douyin.com', 'www.xiaohongshu.com', 'weibo.com', 'm.weibo.cn'])
const articleHosts = new Set(['blog.csdn.net', 'zhuanlan.zhihu.com'])
const durations = [
  { label: '10 秒', value: '10s', ms: 10_000 },
  { label: '15 秒', value: '15s', ms: 15_000 },
  { label: '30 秒', value: '30s', ms: 30_000 },
  { label: '45 秒', value: '45s', ms: 45_000 },
  { label: '1 分钟', value: '1m', ms: 60_000 },
  { label: '2 分钟', value: '2m', ms: 120_000 },
  { label: '1 小时', value: '1h', ms: 3_600_000 },
  { label: '1 天', value: '1d', ms: 86_400_000 },
] as const

function queryEndpoint(value: unknown): PlaygroundEndpoint {
  return value === 'uuid' || value === 'shortlink' || value === 'web-extract' || value === 'web-screenshot' ? value : 'hash'
}

const route = useRoute()
const router = useRouter()
const endpoint = ref<PlaygroundEndpoint>(queryEndpoint(route.query.endpoint))
const operation = ref<ShortLinkOperation>('create')
const apiKey = ref('')
const keyError = ref('')
const formError = ref('')
const algorithm = ref<HashAlgorithm>('SHA256')
const value = ref('hello')
const byteCount = computed(() => new TextEncoder().encode(value.value).length)
const name = ref('')
const originalUrl = ref('')
const articleUrl = ref('')
const duration = ref('1d')
const customExpiry = ref('')
const targetId = ref('')
const updatedName = ref('')
const updatedStatus = ref('')
const loading = ref(false)
const examplesOpen = ref(!window.matchMedia('(max-width: 700px)').matches)
const exampleTab = ref<'curl' | 'powershell'>('curl')
const result = ref<Result | null>(null)
const createdLink = ref<ShortLink | null>(null)
const listedLinks = ref<ShortLink[] | null>(null)
const extractedArticle = ref<WebExtractResponse | null>(null)
const screenshot = ref<{ image: Blob; blockedResources: number | null; resourceLimitReached: boolean; cacheControl: string | null } | null>(null)
const screenshotMode = ref<ScreenshotMode>('async')
const screenshotJob = ref<ScreenshotJob | null>(null)
const screenshotPolling = ref(false)
const screenshotPollError = ref('')
const screenshotPollRetryable = ref(false)
const articleTab = ref('preview')
let controller: AbortController | null = null
let requestId = 0
let screenshotJobApiKey = ''

const docsTarget = computed(() => endpoint.value === 'shortlink' ? 'shortlink' : endpoint.value)
const endpointPath = computed(() => {
  if (endpoint.value === 'web-extract') return 'POST /v1/web/extract'
  if (endpoint.value === 'web-screenshot') return screenshotMode.value === 'async'
    ? 'POST /v1/web/screenshot-jobs' : 'POST /v1/web/screenshot'
  if (endpoint.value === 'shortlink') {
    const method = { create: 'POST', list: 'GET', update: 'PUT', delete: 'DELETE' }[operation.value]
    return `${method} /v1/short-links${operation.value === 'delete' ? '/{id}' : ''}`
  }
  return endpoint.value === 'hash' ? 'POST /v1/utils/hash' : 'GET /v1/utils/uuid'
})
const sample = computed(() => {
  const host = 'http://localhost:8080'
  if (endpoint.value === 'web-screenshot') {
    const asyncMode = screenshotMode.value === 'async'
    const path = `${host}/v1/web/${asyncMode ? 'screenshot-jobs' : 'screenshot'}`
    if (asyncMode && exampleTab.value === 'curl') {
      return `curl --fail-with-body -X POST '${path}' \\\n  -H 'Authorization: ApiKey <完整密钥>' \\\n  -H 'Content-Type: application/json' \\\n  -d '{"url":"https://zhuanlan.zhihu.com/p/<文章ID>"}'\n\n# 从创建响应的 data.jobId 取得 Job ID，再查询：\ncurl -i '${path}/<Job ID>' \\\n  -H 'Authorization: ApiKey <完整密钥>'\n\n# SUCCEEDED 后下载：\ncurl --fail-with-body '${path}/<Job ID>/result' \\\n  -H 'Authorization: ApiKey <完整密钥>' --output screenshot.png`
    }
    if (exampleTab.value === 'powershell') {
      if (asyncMode) {
        return `$apiKey = Read-Host '输入完整 API Key'\n$pageUrl = Read-Host '输入网页的完整 HTTPS URL'\n$headers = @{ Authorization = "ApiKey $apiKey" }\n$body = @{ url = $pageUrl } | ConvertTo-Json -Compress\n$job = Invoke-RestMethod -Uri '${path}' -Method Post -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $body\n$jobId = $job.data.jobId\ndo {\n  Start-Sleep -Seconds 2\n  $status = Invoke-RestMethod -Uri "${path}/$jobId" -Headers $headers\n} while ($status.data.status -in @('QUEUED', 'RUNNING'))\nif ($status.data.status -eq 'SUCCEEDED') {\n  Invoke-WebRequest -UseBasicParsing -Uri "${path}/$jobId/result" -Headers $headers -OutFile '.\\screenshot.png'\n}`
      }
      return `$apiKey = Read-Host '输入完整 API Key'\n$pageUrl = Read-Host '输入网页的完整 HTTPS URL'\n$body = @{ url = $pageUrl } | ConvertTo-Json -Compress\nInvoke-WebRequest -UseBasicParsing -Uri '${path}' \`\n  -Method Post -Headers @{ Authorization = "ApiKey $apiKey" } \`\n  -ContentType 'application/json; charset=utf-8' \`\n  -Body ([Text.Encoding]::UTF8.GetBytes($body)) -OutFile '.\\screenshot.png'\n# 仅在请求成功后打开 PNG；失败请查看错误正文和后端日志。`
    }
    return `curl --fail-with-body -X POST '${path}' \\\n  -H 'Authorization: ApiKey <完整密钥>' \\\n  -H 'Content-Type: application/json' \\\n  -d '{"url":"https://zhuanlan.zhihu.com/p/<文章ID>"}' \\\n  --output screenshot.png\n# 成功保存 PNG；失败响应仍为 JSON，请勿当作图片打开。`
  }
  if (endpoint.value === 'web-extract') {
    const path = `${host}/v1/web/extract`
    if (exampleTab.value === 'powershell') {
      return `$apiKey = Read-Host '输入完整 API Key'\n$articleUrl = Read-Host '输入文章的完整 HTTPS URL'\n$body = @{ url = $articleUrl } | ConvertTo-Json -Compress\nInvoke-RestMethod -Uri '${path}' \`\n  -Method Post -Headers @{ Authorization = "ApiKey $apiKey" } \`\n  -ContentType 'application/json; charset=utf-8' -Body $body`
    }
    return `curl -i '${path}' \\\n  -H 'Authorization: ApiKey <完整密钥>' \\\n  -H 'Content-Type: application/json' \\\n  -d '{"url":"https://zhuanlan.zhihu.com/p/<文章ID>"}'`
  }
  if (endpoint.value === 'shortlink') {
    const path = `${host}/v1/short-links`
    if (exampleTab.value === 'powershell') {
      const prefix = `$apiKey = Read-Host '输入完整 API Key'\n$headers = @{ Authorization = "ApiKey $apiKey" }`
      if (operation.value === 'list') return `${prefix}\nInvoke-RestMethod -Uri '${path}' -Headers $headers`
      if (operation.value === 'delete') return `${prefix}\nInvoke-RestMethod -Uri '${path}/123' -Method Delete -Headers $headers`
      const body = operation.value === 'create'
        ? "@{ name = '演示链接'; originalUrl = 'https://www.douyin.com/video/1'; expiresAt = '2030-01-01T16:00:00+08:00' }"
        : "@{ id = 123; name = '新名称'; status = 0 }"
      return `${prefix}\n$body = ${body} | ConvertTo-Json -Compress\nInvoke-RestMethod -Uri '${path}' -Method ${operation.value === 'create' ? 'Post' : 'Put'} -Headers $headers -ContentType 'application/json' -Body $body`
    }
    const auth = "-H 'Authorization: ApiKey <完整密钥>'"
    if (operation.value === 'list') return `curl -i '${path}' \\\n  ${auth}`
    if (operation.value === 'delete') return `curl -i -X DELETE '${path}/123' \\\n  ${auth}`
    const body = operation.value === 'create'
      ? '{"name":"演示链接","originalUrl":"https://www.douyin.com/video/1","expiresAt":"2030-01-01T16:00:00+08:00"}'
      : '{"id":123,"name":"新名称","status":0}'
    return `curl -i -X ${operation.value === 'create' ? 'POST' : 'PUT'} '${path}' \\\n  ${auth} \\\n  -H 'Content-Type: application/json' \\\n  -d '${body}'`
  }
  const url = `${host}/v1/utils/${endpoint.value}`
  if (exampleTab.value === 'powershell') {
    return endpoint.value === 'hash'
      ? `$apiKey = Read-Host '输入完整 API Key'\n$body = @{ algorithm = 'SHA256'; value = 'hello' } | ConvertTo-Json -Compress\nInvoke-RestMethod -Uri '${url}' \`\n  -Method Post -Headers @{ Authorization = "ApiKey $apiKey" } \`\n  -ContentType 'application/json; charset=utf-8' -Body $body`
      : `$apiKey = Read-Host '输入完整 API Key'\nInvoke-RestMethod -Uri '${url}' \`\n  -Headers @{ Authorization = "ApiKey $apiKey" }`
  }
  return endpoint.value === 'hash'
    ? `curl -i '${url}' \\\n  -H 'Authorization: ApiKey <完整密钥>' \\\n  -H 'Content-Type: application/json' \\\n  -d '{"algorithm":"SHA256","value":"hello"}'`
    : `curl -i '${url}' \\\n  -H 'Authorization: ApiKey <完整密钥>'`
})

function resetRequest() {
  controller?.abort()
  controller = null
  requestId++
  loading.value = false
  result.value = null
  createdLink.value = null
  listedLinks.value = null
  extractedArticle.value = null
  screenshot.value = null
  screenshotJob.value = null
  screenshotPolling.value = false
  screenshotPollError.value = ''
  screenshotPollRetryable.value = false
  screenshotJobApiKey = ''
  articleTab.value = 'preview'
  formError.value = ''
}

watch(() => route.query.endpoint, (next) => {
  const selected = queryEndpoint(next)
  if (selected === endpoint.value) return
  resetRequest()
  endpoint.value = selected
})
watch(operation, resetRequest)
watch(screenshotMode, resetRequest)

function selectEndpoint(next: PlaygroundEndpoint) {
  if (next === endpoint.value) return
  void router.replace({ name: 'playground', query: { endpoint: next } })
}

function resultMessage(status: number, code?: string): string {
  if (status === 401) return 'API Key 无效，请检查是否使用创建时保存的完整 Key。'
  if (status === 403) return 'API Key、所属应用或账号已被禁用。'
  if (code === 'HASH_INPUT_TOO_LARGE') return '输入内容超过 4096 个 UTF-8 字节。'
  if (code === 'SHORT_LINK_NOT_FOUND') return '短链接不存在，或不属于当前 API Key。'
  if (code === 'SHORT_CODE_UNAVAILABLE') return '短码暂时无法生成，请稍后重试。'
  if (code === 'INVALID_WEB_EXTRACT_REQUEST') return '文章 URL 有误，仅支持知乎专栏和 CSDN 的 HTTPS 地址。'
  if (code === 'WEB_CONTENT_UNAVAILABLE') return '页面中没有可提取的文章正文。'
  if (code === 'WEB_PAGE_FETCH_FAILED') return '目标页面无法获取，可能发生跳转、拒绝访问或网络异常。'
  if (code === 'INVALID_SCREENSHOT_REQUEST') return '网页 URL 有误，仅支持知乎专栏和 CSDN 的 HTTPS 地址。'
  if (code === 'SCREENSHOT_PAGE_FETCH_FAILED') return '目标页面无法获取，可能被原站拒绝、发生跳转或 DNS/网络异常。'
  if (code === 'SCREENSHOT_LIMIT_EXCEEDED') return '资源或 PNG 大小超过截图限制，请换一个较小的页面。'
  if (code === 'SCREENSHOT_TIMEOUT') return '截图处理超时，请稍后重试或换一个较小的页面。'
  if (code === 'SCREENSHOT_BUSY') return '截图服务正在处理其他请求，请稍后重试。'
  if (code === 'SCREENSHOT_ENGINE_UNAVAILABLE') return '截图浏览器暂不可用，请检查后端 Chromium 安装及运行环境。'
  if (code === 'SCREENSHOT_JOB_QUEUE_FULL') return '全局未完成截图任务已达 10 个，请稍后再创建。'
  if (code === 'SCREENSHOT_JOB_SUBMISSION_UNAVAILABLE') return '截图任务暂时无法提交，本次配额与任务写入已共同回滚。'
  if (code === 'SCREENSHOT_JOB_CREATE_FAILED') return '截图任务创建失败，请稍后重试。'
  if (code === 'SCREENSHOT_JOB_NOT_FOUND') return '任务不存在，或不属于当前 API Key。'
  if (code === 'SCREENSHOT_JOB_NOT_READY') return '截图仍在排队或执行，请稍后再查询结果。'
  if (code === 'SCREENSHOT_JOB_FAILED') return '截图任务执行失败，请查看任务失败代码。'
  if (code === 'SCREENSHOT_JOB_RESULT_EXPIRED') return '截图结果已过 1 小时保留期，请重新创建任务。'
  if (code === 'SCREENSHOT_JOB_RESULT_UNAVAILABLE') return '截图结果文件暂时无法读取。'
  if (status === 400) return '请求参数有误，请核对输入内容与接口文档。'
  return `请求失败（HTTP ${status}），请稍后重试。`
}

function validateShortLink(): { payload?: { name: string; originalUrl: string; expiresAt: string } | { id: number; name?: string; status?: 0 | 1 } | number; error?: string } {
  if (operation.value === 'list') return {}
  if (operation.value === 'create') {
    const trimmedName = name.value.trim()
    const target = originalUrl.value.trim()
    if (!trimmedName || trimmedName.length > 64) return { error: '短链名称需为 1～64 个字符。' }
    if (target.length > 2048) return { error: '原始地址不能超过 2048 个字符。' }
    try {
      const url = new URL(target)
      if (url.protocol !== 'https:' || !allowedHosts.has(url.hostname) || url.username || url.password || url.port) {
        return { error: '仅支持 HTTPS 与列出的精确主机名，且不能包含登录信息或非标准端口。' }
      }
    } catch { return { error: '请输入完整有效的 HTTPS 地址。' } }
    let expiresAt: string
    if (duration.value === 'custom') {
      const timestamp = new Date(customExpiry.value).getTime()
      if (!Number.isFinite(timestamp) || timestamp <= Date.now()) return { error: '请选择晚于当前时间的自定义到期时刻。' }
      expiresAt = new Date(timestamp).toISOString()
    } else {
      const selected = durations.find((item) => item.value === duration.value)
      if (!selected) return { error: '请选择有效期。' }
      // 短时有效期在提交时计算，避免填写表单的耗时提前消耗有效期。
      expiresAt = new Date(Date.now() + selected.ms).toISOString()
    }
    return { payload: { name: trimmedName, originalUrl: target, expiresAt } }
  }
  const id = Number(targetId.value)
  if (!Number.isSafeInteger(id) || id <= 0) return { error: '请输入有效的短链 ID。' }
  if (operation.value === 'delete') return { payload: id }
  const nextName = updatedName.value.trim()
  if (nextName.length > 64) return { error: '新名称不能超过 64 个字符。' }
  if (!nextName && !updatedStatus.value) return { error: '请填写新名称或选择新状态。' }
  return { payload: { id, ...(nextName ? { name: nextName } : {}), ...(updatedStatus.value ? { status: Number(updatedStatus.value) as 0 | 1 } : {}) } }
}

function fullScreenshotJob(job: CreatedScreenshotJob): ScreenshotJob {
  return {
    ...job,
    resultSizeBytes: null,
    resultSha256: null,
    blockedResources: null,
    resourceLimitReached: null,
    resultExpiresAt: null,
    failureCode: null,
    startedAt: null,
    completedAt: null,
  }
}

function responseBody(body: unknown, key: string): string {
  return body ? JSON.stringify(body, null, 2).replaceAll(key, '[已隐藏]') : ''
}

function waitForNextScreenshotPoll(signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) { reject(new DOMException('请求已取消', 'AbortError')); return }
    const timer = window.setTimeout(() => {
      signal.removeEventListener('abort', abort)
      resolve()
    }, 2_000)
    const abort = () => {
      window.clearTimeout(timer)
      reject(new DOMException('请求已取消', 'AbortError'))
    }
    signal.addEventListener('abort', abort, { once: true })
  })
}

async function monitorScreenshotJob(currentId: number, key: string, jobId: string, signal: AbortSignal) {
  screenshotPolling.value = true
  screenshotPollError.value = ''
  screenshotPollRetryable.value = false
  try {
    while (currentId === requestId && !signal.aborted) {
      const statusResponse = await getScreenshotJob(key, jobId, signal)
      if (currentId !== requestId || signal.aborted) return
      if (statusResponse.status < 200 || statusResponse.status >= 300) {
        const message = resultMessage(statusResponse.status, statusResponse.body?.code)
        result.value = {
          status: statusResponse.status, durationMs: null,
          body: responseBody(statusResponse.body, key), message, isError: true,
        }
        screenshotPollError.value = message
        screenshotPollRetryable.value = statusResponse.status >= 500
        return
      }
      if (!statusResponse.data) {
        const message = '服务器返回的任务状态格式不正确，自动查询已停止。'
        result.value = { status: statusResponse.status, durationMs: null, body: '', message, isError: true }
        screenshotPollError.value = message
        return
      }

      screenshotJob.value = statusResponse.data
      if (statusResponse.data.status === 'QUEUED' || statusResponse.data.status === 'RUNNING') {
        await waitForNextScreenshotPoll(signal)
        continue
      }
      if (statusResponse.data.status === 'FAILED') {
        result.value = {
          status: statusResponse.status, durationMs: null,
          body: responseBody(statusResponse.body, key), isError: true,
          message: `截图任务执行失败（${statusResponse.data.failureCode || '未提供失败代码'}）。`,
        }
        return
      }
      if (statusResponse.data.status === 'EXPIRED') {
        result.value = {
          status: statusResponse.status, durationMs: null,
          body: responseBody(statusResponse.body, key), isError: true,
          message: '截图任务的 PNG 结果已过 1 小时保留期。',
        }
        return
      }

      const downloadStart = performance.now()
      const imageResponse = await getScreenshotJobResult(key, jobId, signal)
      if (currentId !== requestId || signal.aborted) return
      if (imageResponse.png) {
        screenshot.value = {
          image: imageResponse.png,
          blockedResources: statusResponse.data.blockedResources,
          resourceLimitReached: statusResponse.data.resourceLimitReached === true,
          cacheControl: imageResponse.cacheControl,
        }
      }
      const message = imageResponse.png ? '异步截图已完成，以下是保留期内的 PNG 结果。'
        : imageResponse.invalidImage ? '服务器没有返回有效的 PNG 图片。'
          : resultMessage(imageResponse.status, imageResponse.body?.code)
      result.value = {
        status: imageResponse.status,
        durationMs: Math.round(performance.now() - downloadStart),
        body: responseBody(imageResponse.body, key),
        invalidImage: imageResponse.invalidImage,
        isError: !imageResponse.png,
        message,
      }
      if (!imageResponse.png && imageResponse.status >= 500) {
        screenshotPollError.value = `${message}可以继续查询并重试读取。`
        screenshotPollRetryable.value = true
      }
      return
    }
  } catch (error) {
    if (currentId !== requestId || (error instanceof DOMException && error.name === 'AbortError')) return
    const message = '自动查询因网络问题停止，Job ID 已保留，可继续查询。'
    result.value = { status: null, durationMs: null, body: '', message, isError: true }
    screenshotPollError.value = message
    screenshotPollRetryable.value = true
  } finally {
    if (currentId === requestId) {
      screenshotPolling.value = false
      loading.value = false
      controller = null
    }
  }
}

function resumeScreenshotPolling() {
  if (!screenshotJob.value || !screenshotJobApiKey || screenshotPolling.value) return
  controller?.abort()
  const currentId = ++requestId
  controller = new AbortController()
  void monitorScreenshotJob(currentId, screenshotJobApiKey, screenshotJob.value.jobId, controller.signal)
}

async function sendRequest() {
  if (loading.value) return
  const key = apiKey.value.trim()
  keyError.value = ''
  formError.value = ''
  if (!key) { keyError.value = '请输入完整 API Key'; return }
  if (endpoint.value === 'hash' && byteCount.value > 4096) return
  if (endpoint.value === 'web-extract' || endpoint.value === 'web-screenshot') {
    try {
      const target = articleUrl.value.trim()
      const url = new URL(target)
      if (target.length > 2048 || url.protocol !== 'https:' || !articleHosts.has(url.hostname)
        || url.username || url.password || url.port || target.includes('#')) {
        formError.value = '仅支持知乎专栏和 CSDN 的完整 HTTPS 地址，不允许登录信息、自定义端口或片段。'
        return
      }
    } catch { formError.value = '请输入完整有效的 HTTPS 文章地址。'; return }
  }
  const validated = endpoint.value === 'shortlink' ? validateShortLink() : {}
  if (validated.error) { formError.value = validated.error; return }
  if (endpoint.value === 'shortlink' && operation.value === 'delete') {
    try {
      await ElMessageBox.confirm(`删除短链 ID ${validated.payload} 后，短码不会复用。确定继续吗？`, '删除短链', {
        type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消',
      })
    } catch { return }
  }

  controller?.abort()
  const currentId = ++requestId
  controller = new AbortController()
  loading.value = true
  result.value = null
  createdLink.value = null
  listedLinks.value = null
  extractedArticle.value = null
  screenshot.value = null
  screenshotJob.value = null
  screenshotPolling.value = false
  screenshotPollError.value = ''
  screenshotPollRetryable.value = false
  screenshotJobApiKey = ''
  articleTab.value = 'preview'
  const start = performance.now()
  let backgroundStarted = false
  try {
    if (endpoint.value === 'web-screenshot') {
      if (screenshotMode.value === 'async') {
        const response = await submitScreenshotJob(key, articleUrl.value.trim(), controller.signal)
        if (currentId !== requestId) return
        const success = response.status >= 200 && response.status < 300 && response.data !== null
        result.value = {
          status: response.status,
          durationMs: Math.round(performance.now() - start),
          body: responseBody(response.body, key),
          isError: !success,
          message: success ? '截图任务已创建，正在自动查询状态。'
            : response.status >= 200 && response.status < 300
              ? '服务器返回的任务创建数据格式不正确。'
              : resultMessage(response.status, response.body?.code),
        }
        if (!success || !response.data) return
        screenshotJob.value = fullScreenshotJob(response.data)
        screenshotJobApiKey = key
        loading.value = false
        backgroundStarted = true
        void monitorScreenshotJob(currentId, key, response.data.jobId, controller.signal)
        return
      }
      const response = await callWebScreenshot(key, articleUrl.value.trim(), controller.signal)
      if (currentId !== requestId) return
      if (response.png) screenshot.value = { image: response.png, blockedResources: response.blockedResources, resourceLimitReached: response.resourceLimitReached, cacheControl: response.cacheControl }
      result.value = {
        status: response.status, durationMs: Math.round(performance.now() - start),
        body: responseBody(response.body, key),
        invalidImage: response.invalidImage,
        message: response.png ? '截图已生成，以下是服务器返回的 PNG。'
          : response.invalidImage ? '服务器没有返回有效的 PNG 图片，请检查后端响应。'
            : resultMessage(response.status, response.body?.code),
      }
      return
    }
    const response = endpoint.value === 'web-extract'
      ? await callWebExtract(key, articleUrl.value.trim(), controller.signal)
      : endpoint.value === 'shortlink'
        ? await callShortLinkOpenApi(operation.value, key, validated.payload, controller.signal)
        : await callOpenApi(endpoint.value, key, algorithm.value, value.value, controller.signal)
    if (currentId !== requestId) return
    const responseText = JSON.stringify(response.body, null, 2).replaceAll(key, '[已隐藏]')
    if (endpoint.value === 'web-extract' && response.status >= 200 && response.status < 300) {
      const data: unknown = JSON.parse(responseText)?.data
      if (isWebExtractResponse(data)) extractedArticle.value = data
    }
    if (endpoint.value === 'shortlink' && operation.value === 'create' && response.status >= 200 && response.status < 300) {
      createdLink.value = response.body?.data as ShortLink
    }
    if (endpoint.value === 'shortlink' && operation.value === 'list' && response.status >= 200 && response.status < 300 && Array.isArray(response.body?.data)) {
      listedLinks.value = response.body.data
    }
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
    result.value = { status: null, durationMs: null, body: '', message: '无法连接服务器，请确认后端已启动并检查网络。' }
  } finally {
    if (currentId === requestId && !backgroundStarted) { loading.value = false; controller = null }
  }
}

async function copyExample() {
  try { await navigator.clipboard.writeText(sample.value); ElMessage.success('示例已复制，请替换占位密钥') }
  catch { ElMessage.error('复制失败，请手动选择示例文本') }
}

async function copyShortUrl(shortCode: string) {
  try { await navigator.clipboard.writeText(publicShortLinkUrl(shortCode)); ElMessage.success('短地址已复制') }
  catch { ElMessage.error('复制失败，请手动选择短地址') }
}

onBeforeUnmount(() => {
  controller?.abort()
  controller = null
  requestId++
  screenshotJobApiKey = ''
  apiKey.value = ''
})
</script>

<template>
  <div class="playground-page" :class="{ 'is-screenshot': endpoint === 'web-screenshot' }">
    <header class="playground-intro"><div><p class="playground-eyebrow">NEXUS · OPEN API</p><h1>API 调试台</h1><p>快速调用开放 API，查看真实响应。</p></div><img class="playground-decoration" :src="decoration" alt="" aria-hidden="true" /></header>
    <div class="playground-endpoints" role="group" aria-label="选择开放 API">
      <el-button :class="{ 'is-active': endpoint === 'uuid' }" :aria-pressed="endpoint === 'uuid'" @click="selectEndpoint('uuid')"><el-icon aria-hidden="true"><DocumentCopy /></el-icon>生成 UUID</el-button>
      <el-button :class="{ 'is-active': endpoint === 'hash' }" :aria-pressed="endpoint === 'hash'" @click="selectEndpoint('hash')"><el-icon aria-hidden="true"><DataAnalysis /></el-icon>计算 Hash</el-button>
      <el-button :class="{ 'is-active': endpoint === 'shortlink' }" :aria-pressed="endpoint === 'shortlink'" @click="selectEndpoint('shortlink')"><el-icon aria-hidden="true"><Link /></el-icon>短链接</el-button>
      <el-button :class="{ 'is-active': endpoint === 'web-extract' }" :aria-pressed="endpoint === 'web-extract'" @click="selectEndpoint('web-extract')"><el-icon aria-hidden="true"><Document /></el-icon>网页正文提取</el-button>
      <el-button :class="{ 'is-active': endpoint === 'web-screenshot' }" :aria-pressed="endpoint === 'web-screenshot'" @click="selectEndpoint('web-screenshot')"><el-icon aria-hidden="true"><Camera /></el-icon>网页截图</el-button>
    </div>
    <el-scrollbar v-if="endpoint === 'shortlink'" class="playground-operations"><div class="playground-operations-inner" role="group" aria-label="短链接操作">
      <el-button v-for="item in ([['create', '创建短链'], ['list', '查询列表'], ['update', '修改短链'], ['delete', '删除短链']] as const)" :key="item[0]" text :class="{ 'is-active': operation === item[0] }" :aria-pressed="operation === item[0]" @click="operation = item[0]">{{ item[1] }}</el-button>
    </div></el-scrollbar>
    <div class="playground-workspace">
      <section class="playground-panel playground-request" aria-labelledby="playground-request-title"><div class="playground-panel-heading"><h2 id="playground-request-title">请求</h2><code>{{ endpointPath }}</code></div>
        <el-form novalidate @submit.prevent="sendRequest">
          <div v-if="endpoint === 'web-screenshot'" class="playground-screenshot-mode-field">
            <div class="playground-screenshot-mode-label"><strong>截图方式</strong><small>共用同一个 Chromium 执行槽</small></div>
            <div class="playground-screenshot-modes" role="group" aria-label="截图方式">
              <button type="button" class="playground-screenshot-mode" :class="{ 'is-active': screenshotMode === 'sync' }" :aria-pressed="screenshotMode === 'sync'" @click="screenshotMode = 'sync'">
                <span class="playground-screenshot-mode-head"><span aria-hidden="true">↯</span><strong>同步截图</strong><i></i></span>
                <small>保持请求连接，生成后直接返回 PNG。</small>
                <span class="playground-screenshot-mode-tags"><em>立即预览</em><em>需持续等待</em></span>
              </button>
              <button type="button" class="playground-screenshot-mode" :class="{ 'is-active': screenshotMode === 'async' }" :aria-pressed="screenshotMode === 'async'" @click="screenshotMode = 'async'">
                <span class="playground-screenshot-mode-head"><span aria-hidden="true">◷</span><strong>异步任务</strong><i></i></span>
                <small>后台排队与恢复，完成后下载结果。</small>
                <span class="playground-screenshot-mode-tags"><em>可恢复</em><em>结果保留 1 小时</em></span>
              </button>
            </div>
          </div>
          <div class="playground-field"><label for="playground-key">API Key</label><el-input id="playground-key" v-model="apiKey" type="password" show-password autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="粘贴创建时保存的完整 API Key" :aria-invalid="Boolean(keyError)" aria-describedby="playground-key-help playground-key-error" @input="keyError = ''" /><small id="playground-key-help">仅在本页临时使用，不保存到浏览器存储或请求示例。</small><small v-if="keyError" id="playground-key-error" class="playground-field-error" role="alert">{{ keyError }}</small></div>
          <template v-if="endpoint === 'hash'"><div class="playground-field"><label for="playground-algorithm">算法</label><el-select id="playground-algorithm" v-model="algorithm" aria-label="算法"><el-option label="SHA256" value="SHA256" /><el-option label="SHA512" value="SHA512" /></el-select></div><div class="playground-field"><label for="playground-value">待计算的内容</label><el-input id="playground-value" v-model="value" type="textarea" :rows="4" spellcheck="false" :aria-invalid="byteCount > 4096" aria-describedby="playground-byte-count" /><small id="playground-byte-count" class="playground-byte-count" :class="{ 'is-over-limit': byteCount > 4096 }">{{ byteCount }} / 4096 字节<span v-if="byteCount > 4096"> · 已超出限制</span></small></div></template>
          <template v-else-if="endpoint === 'shortlink'">
            <template v-if="operation === 'create'"><div class="playground-field"><label for="short-link-name">短链名称</label><el-input id="short-link-name" v-model="name" maxlength="64" placeholder="例如：秋季活动视频" /></div><div class="playground-field"><label for="short-link-url">原始 HTTPS 地址</label><el-input id="short-link-url" v-model="originalUrl" type="url" maxlength="2048" placeholder="https://www.douyin.com/video/…" /><small>允许 www.douyin.com、v.douyin.com、www.xiaohongshu.com、weibo.com、m.weibo.cn；只检查协议和精确主机名。</small></div><div class="playground-field"><label for="short-link-duration">有效期</label><el-select id="short-link-duration" v-model="duration" aria-label="有效期"><el-option v-for="item in durations" :key="item.value" :label="item.label" :value="item.value" /><el-option label="自定义到期时刻" value="custom" /></el-select><small>提交时换算为绝对到期时刻；10～30 秒短链可能在首次访问前过期。</small></div><div v-if="duration === 'custom'" class="playground-field"><label for="short-link-custom-expiry">到期时刻（本地时间）</label><el-date-picker id="short-link-custom-expiry" v-model="customExpiry" type="datetime" format="YYYY-MM-DD HH:mm" value-format="YYYY-MM-DDTHH:mm" placeholder="选择到期时刻" popper-class="nexus-datetime-popper" /></div></template>
            <p v-else-if="operation === 'list'" class="playground-uuid-help">列出当前 API Key 创建且未删除的短链接。其他 Key 的记录不会出现在结果中。</p>
            <template v-else><div class="playground-field"><label for="short-link-id">短链 ID</label><el-input id="short-link-id" v-model="targetId" type="number" min="1" step="1" placeholder="例如：123" /></div><template v-if="operation === 'update'"><div class="playground-field"><label for="short-link-update-name">新名称（可选）</label><el-input id="short-link-update-name" v-model="updatedName" maxlength="64" placeholder="留空则不修改名称" /></div><div class="playground-field"><label for="short-link-update-status">新状态（可选）</label><el-select id="short-link-update-status" v-model="updatedStatus" aria-label="新状态（可选）"><el-option label="不修改状态" value="" /><el-option label="启用" value="0" /><el-option label="禁用" value="1" /></el-select><small>原始地址和到期时刻创建后不可修改。</small></div></template><p v-else class="playground-delete-help">删除后短链将无法跳转，短码也不会再次分配。</p></template>
          </template>
          <template v-else-if="endpoint === 'web-extract'">
            <div class="playground-field"><label for="playground-article-url">文章 URL</label><el-input id="playground-article-url" v-model="articleUrl" type="url" maxlength="2048" placeholder="https://zhuanlan.zhihu.com/p/…" aria-describedby="playground-article-help" @input="formError = ''" /><small id="playground-article-help">仅支持 zhuanlan.zhihu.com 和 blog.csdn.net 的 HTTPS 文章地址；不自动跟随跳转。</small></div>
            <p class="playground-extract-help">提取标题、正文和图片位置。图片由浏览器直接加载，原站限制可能导致图片无法显示。</p>
          </template>
          <template v-else-if="endpoint === 'web-screenshot'">
            <div class="playground-field"><label for="playground-screenshot-url">网页 URL</label><el-input id="playground-screenshot-url" v-model="articleUrl" type="url" maxlength="2048" placeholder="https://blog.csdn.net/…/article/details/…" aria-describedby="playground-screenshot-help" @input="formError = ''" /><small id="playground-screenshot-help">支持 CSDN 博客和知乎专栏的 HTTPS 地址；不自动跟随跳转。</small></div>
            <div class="playground-screenshot-capabilities" aria-label="截图能力说明"><el-tag type="info">PNG</el-tag><el-tag type="info">1280 × 720</el-tag><el-tag type="info">关闭脚本</el-tag></div>
            <p class="playground-extract-help">保留原站静态排版，截取当前视口；{{ screenshotMode === 'async' ? '当前页只保留本次 Job ID，不提供历史列表。' : '同步结果只在当前页保留。' }}</p>
          </template>
          <p v-else class="playground-uuid-help">此接口不需要请求体，每次发送都会生成一个新 UUID。</p>
          <p v-if="formError" class="playground-form-error" role="alert">{{ formError }}</p>
          <el-button class="playground-submit" native-type="submit" type="primary" :loading="loading" :disabled="endpoint === 'hash' && byteCount > 4096">{{ loading ? '发送中…' : endpoint === 'web-screenshot' ? screenshotMode === 'async' ? '创建异步任务' : '生成同步截图' : '发送请求' }}</el-button>
        </el-form>
      </section>
      <section class="playground-panel playground-response" aria-labelledby="playground-response-title" aria-live="polite"><div class="playground-panel-heading"><h2 id="playground-response-title">响应</h2><div v-if="result?.status !== null && result?.status !== undefined" class="playground-response-meta"><span class="playground-status" :class="{ 'is-error': result.status >= 400 }">{{ result.status }} {{ result.status >= 200 && result.status < 300 ? 'OK' : 'ERROR' }}</span><span v-if="result.durationMs !== null">{{ result.durationMs }} ms</span></div></div>
        <p v-if="result" class="playground-result-message" :class="{ 'is-error': result.status === null || result.status >= 400 || result.invalidImage || result.isError }">{{ result.message }}</p>
        <div v-if="createdLink" class="playground-created-link"><p class="playground-eyebrow">SHORT LINK CREATED</p><h3>短链接已创建</h3><p>复制后即可分享；到期或禁用后将无法跳转。</p><code>{{ publicShortLinkUrl(createdLink.shortCode) }}</code><div class="playground-created-actions"><el-button type="primary" @click="copyShortUrl(createdLink.shortCode)">复制短地址</el-button><RouterLink :to="{ name: 'short-links', query: { apiKeyId: createdLink.apiKeyId } }">管理这把 Key 的短链 <el-icon aria-hidden="true"><ArrowRight /></el-icon></RouterLink></div></div>
        <div v-if="listedLinks" class="playground-link-list"><h3>这把 Key 的短链接</h3><p v-if="listedLinks.length === 0" class="playground-list-empty">这把 Key 还没有短链接。</p><div v-for="link in listedLinks" :key="link.id" class="playground-link-item"><strong>{{ link.name }}</strong><small>ID {{ link.id }} · {{ link.status === 1 ? '已禁用' : new Date(link.expiresAt).getTime() <= Date.now() ? '已到期' : '自身启用' }} · 到期 {{ new Date(link.expiresAt).toLocaleString('zh-CN') }}</small><a :href="publicShortLinkUrl(link.shortCode)" target="_blank" rel="noopener noreferrer">{{ publicShortLinkUrl(link.shortCode) }}</a><div class="playground-link-actions"><el-button size="small" @click="copyShortUrl(link.shortCode)">复制短地址</el-button><a :href="publicShortLinkUrl(link.shortCode)" target="_blank" rel="noopener noreferrer">打开短地址 <el-icon aria-hidden="true"><ArrowRight /></el-icon></a></div></div></div>
        <div v-if="endpoint === 'web-screenshot' && screenshotMode === 'async' && screenshotJob" class="playground-async-screenshot-result">
          <ScreenshotJobStatus :job="screenshotJob" :polling="screenshotPolling" :poll-error="screenshotPollError" :retryable="screenshotPollRetryable" @retry="resumeScreenshotPolling" />
          <ScreenshotPreview v-if="screenshot" :image="screenshot.image" :blocked-resources="screenshot.blockedResources" :resource-limit-reached="screenshot.resourceLimitReached" :cache-control="screenshot.cacheControl" />
        </div>
        <ScreenshotPreview v-else-if="screenshot" :image="screenshot.image" :blocked-resources="screenshot.blockedResources" :resource-limit-reached="screenshot.resourceLimitReached" :cache-control="screenshot.cacheControl" />
        <div v-else-if="extractedArticle" class="playground-extracted-result">
          <h3 class="playground-article-title">{{ extractedArticle.title || '未提供标题' }}</h3>
          <el-tabs v-model="articleTab" class="playground-article-tabs" aria-label="提取结果格式">
            <el-tab-pane label="正文预览" name="preview" lazy><el-scrollbar max-height="480px" class="playground-preview-scroll"><WebExtractPreview :html="extractedArticle.contentHtml" /></el-scrollbar></el-tab-pane>
            <el-tab-pane label="纯文本" name="text" lazy><el-scrollbar max-height="480px" class="playground-text-scroll"><pre class="playground-article-text">{{ extractedArticle.textContent }}</pre></el-scrollbar></el-tab-pane>
            <el-tab-pane label="JSON" name="json" lazy><div class="playground-code"><span>JSON</span><el-scrollbar max-height="480px"><pre><code>{{ result?.body }}</code></pre></el-scrollbar></div></el-tab-pane>
          </el-tabs>
        </div>
        <div v-else-if="result?.body" class="playground-code"><span>JSON</span><el-scrollbar max-height="480px"><pre><code>{{ result.body }}</code></pre></el-scrollbar></div><div v-else class="playground-response-empty"><p v-if="loading">{{ endpoint === 'web-screenshot' ? screenshotMode === 'async' ? '正在创建截图任务…' : '正在生成截图…' : '正在等待服务器响应…' }}</p><p v-else-if="!result">填写 API Key 并发送请求后，结果会显示在这里。</p><p v-else>{{ endpoint === 'web-screenshot' ? '本次请求没有可显示的 PNG 图片。' : '本次请求没有可显示的 JSON 响应。' }}</p></div>
      </section>
    </div>
    <section class="playground-panel playground-examples" aria-labelledby="playground-example-title"><div class="playground-example-heading"><el-button text class="playground-example-toggle" :aria-expanded="examplesOpen" aria-controls="playground-example-content" @click="examplesOpen = !examplesOpen"><h2 id="playground-example-title">请求示例</h2><el-icon aria-hidden="true" :class="{ 'is-open': examplesOpen }"><ArrowDown /></el-icon></el-button><RouterLink :to="{ name: 'docs-article', params: { slug: docsTarget } }">查看接口文档 <el-icon aria-hidden="true"><ArrowRight /></el-icon></RouterLink></div><div v-if="examplesOpen" id="playground-example-content"><div class="playground-example-tabs" role="group" aria-label="示例命令格式"><el-button :class="{ 'is-active': exampleTab === 'curl' }" :aria-pressed="exampleTab === 'curl'" @click="exampleTab = 'curl'">curl</el-button><el-button :class="{ 'is-active': exampleTab === 'powershell' }" :aria-pressed="exampleTab === 'powershell'" @click="exampleTab = 'powershell'">PowerShell</el-button></div><p class="playground-example-note">示例始终使用占位密钥，不会包含你在上方输入的 API Key。本地后端默认端口为 8080。</p><div class="playground-code playground-example-code"><el-button @click="copyExample">复制示例</el-button><el-scrollbar max-height="480px"><pre><code>{{ sample }}</code></pre></el-scrollbar></div></div></section>
  </div>
</template>
