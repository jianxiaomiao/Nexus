<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { isAxiosError } from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { Application } from '@/api/applications'
import {
  createApplicationWebhook,
  queryApplicationWebhook,
  rotateApplicationWebhookSecret,
  updateApplicationWebhook,
  type ApplicationWebhook,
} from '@/api/webhooks'
import { webhookErrorMessage } from '@/api/webhookErrors'
import { formatBeijingTime } from '@/utils/beijingTime'

const props = defineProps<{ application: Application }>()
const router = useRouter()
const webhook = ref<ApplicationWebhook | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const actionBusy = ref(false)
const editingUrl = ref(false)
const form = reactive({ url: '' })

const revealOpen = ref(false)
const revealedSecret = ref('')
const revealTitle = ref('保存 Webhook Secret')
const savedConfirmation = ref(false)
const copied = ref(false)
let copyResetTimer: ReturnType<typeof setTimeout> | undefined
let loadVersion = 0
let removeNavigationGuard: (() => void) | undefined

function normalizedWebhookUrl(value: string): string | null {
  try {
    const url = new URL(value.trim())
    if (url.protocol !== 'https:' || !url.hostname || url.username || url.password || url.hash) return null
    return url.toString()
  } catch {
    return null
  }
}

function warnBeforeUnload(event: BeforeUnloadEvent) {
  if (!revealedSecret.value || savedConfirmation.value) return
  event.preventDefault()
}

async function load() {
  const version = ++loadVersion
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await queryApplicationWebhook(props.application.id)
    if (version !== loadVersion) return
    webhook.value = result
    form.url = result?.url ?? ''
    editingUrl.value = false
  } catch (error) {
    if (version === loadVersion) errorMessage.value = webhookErrorMessage(error, '加载 Webhook 配置失败，请稍后重试')
  } finally {
    if (version === loadVersion) loading.value = false
  }
}

function openSecret(secret: string, title: string) {
  revealedSecret.value = secret
  revealTitle.value = title
  savedConfirmation.value = false
  copied.value = false
  revealOpen.value = true
}

async function createWebhook() {
  if (actionBusy.value) return
  const url = normalizedWebhookUrl(form.url)
  if (!url) {
    ElMessage.error('请输入不含账号和 Fragment 的完整 HTTPS 地址')
    return
  }

  actionBusy.value = true
  try {
    const result = await createApplicationWebhook(props.application.id, url)
    const { secret, ...configuration } = result
    webhook.value = configuration
    form.url = configuration.url
    openSecret(secret, '保存 Webhook Secret')
  } catch (error) {
    if (isAxiosError(error) && (!error.response || error.response.status >= 500)) {
      ElMessage.error('创建结果暂时无法确认。请先刷新配置；如果配置已存在但未保存 Secret，请执行一次轮换')
    } else {
      ElMessage.error(webhookErrorMessage(error, '创建 Webhook 失败，请稍后重试'))
    }
  } finally {
    actionBusy.value = false
  }
}

async function saveUrl() {
  const current = webhook.value
  if (!current || actionBusy.value) return
  const url = normalizedWebhookUrl(form.url)
  if (!url) {
    ElMessage.error('请输入不含账号和 Fragment 的完整 HTTPS 地址')
    return
  }
  if (url === current.url) {
    editingUrl.value = false
    return
  }

  actionBusy.value = true
  try {
    webhook.value = await updateApplicationWebhook(props.application.id, { url })
    form.url = webhook.value.url
    editingUrl.value = false
    ElMessage.success('回调 URL 已更新；尚未开始的投递会使用新地址')
  } catch (error) {
    ElMessage.error(webhookErrorMessage(error, '修改回调 URL 失败，请稍后重试'))
  } finally {
    actionBusy.value = false
  }
}

function cancelUrlEdit() {
  form.url = webhook.value?.url ?? ''
  editingUrl.value = false
}

async function toggleStatus() {
  const current = webhook.value
  if (!current || actionBusy.value) return
  const disabling = current.status === 0
  try {
    await ElMessageBox.confirm(
      disabling
        ? '暂停后不会取消待投递事件。恢复前 Nexus 不会发送这些通知。'
        : '恢复后，待投递事件会继续处理，但不保证立即发送。',
      disabling ? '暂停 Webhook 投递' : '恢复 Webhook 投递',
      { type: disabling ? 'warning' : 'info', confirmButtonText: disabling ? '确认暂停' : '确认恢复', cancelButtonText: '取消' },
    )
  } catch {
    return
  }

  actionBusy.value = true
  try {
    webhook.value = await updateApplicationWebhook(props.application.id, { status: disabling ? 1 : 0 })
    ElMessage.success(disabling ? 'Webhook 投递已暂停' : 'Webhook 投递已恢复')
  } catch (error) {
    ElMessage.error(webhookErrorMessage(error, '修改 Webhook 状态失败，请稍后重试'))
  } finally {
    actionBusy.value = false
  }
}

async function rotateSecret() {
  if (!webhook.value || actionBusy.value || revealOpen.value) return
  try {
    await ElMessageBox.confirm(
      '新 Secret 只展示一次。轮换后的下一次投递会使用新 Secret，请先准备好安全保存并更新接收端。',
      '轮换 Webhook Secret',
      { type: 'warning', confirmButtonText: '确认轮换', cancelButtonText: '取消' },
    )
  } catch {
    return
  }

  actionBusy.value = true
  try {
    const result = await rotateApplicationWebhookSecret(props.application.id)
    webhook.value = result.webhook
    form.url = result.webhook.url
    openSecret(result.secret, '保存新的 Webhook Secret')
  } catch (error) {
    if (isAxiosError(error) && (!error.response || error.response.status >= 500)) {
      ElMessage.error('轮换结果可能已经生效。请刷新配置核对 Secret 版本，不要直接重试')
    } else {
      ElMessage.error(webhookErrorMessage(error, '轮换 Secret 失败，请稍后重试'))
    }
  } finally {
    actionBusy.value = false
  }
}

async function copySecret() {
  if (!revealedSecret.value) return
  try {
    await navigator.clipboard.writeText(revealedSecret.value)
    copied.value = true
    ElMessage.success('已复制，请妥善保存')
    if (copyResetTimer) clearTimeout(copyResetTimer)
    copyResetTimer = setTimeout(() => { copied.value = false }, 2000)
  } catch {
    ElMessage.error('复制失败，请手动选择并复制 Secret')
  }
}

function closeSecret() {
  if (!savedConfirmation.value) return
  revealedSecret.value = ''
  copied.value = false
  revealOpen.value = false
}

onMounted(() => {
  void load()
  window.addEventListener('beforeunload', warnBeforeUnload)
  removeNavigationGuard = router.beforeEach(() => {
    if (!revealedSecret.value || savedConfirmation.value) return
    ElMessage.warning('请先保存完整 Secret，并勾选确认后再离开')
    return false
  })
})

onBeforeUnmount(() => {
  revealedSecret.value = ''
  if (copyResetTimer) clearTimeout(copyResetTimer)
  window.removeEventListener('beforeunload', warnBeforeUnload)
  removeNavigationGuard?.()
})

watch(() => props.application.id, () => { void load() })
</script>

<template>
  <section class="webhook-panel" aria-labelledby="webhook-title">
    <div v-if="loading" class="webhook-card" aria-busy="true"><el-skeleton :rows="6" animated /></div>
    <div v-else-if="errorMessage" class="webhook-card webhook-error" role="alert">
      <h2 id="webhook-title">暂时无法加载 Webhook</h2>
      <p>{{ errorMessage }}</p>
      <el-button @click="load">重试</el-button>
    </div>

    <div v-else-if="!webhook" class="webhook-card empty-webhook">
      <h2 id="webhook-title">Webhook 通知</h2>
      <p>截图任务成功或最终失败后，Nexus 会通知你的服务器。</p>
      <el-form label-position="top" @submit.prevent="createWebhook">
        <el-form-item label="回调 URL">
          <el-input v-model="form.url" class="url-input" placeholder="https://api.example.com/nexus/webhooks" :disabled="actionBusy" />
          <p class="field-help">仅支持 HTTPS；域名及全部解析地址必须允许公网访问。</p>
        </el-form-item>
        <el-button type="primary" native-type="submit" :loading="actionBusy">创建 Webhook</el-button>
      </el-form>
      <div class="behavior-note" role="note">
        <p>创建后将生成签名 Secret</p>
        <p>完整 Secret 只展示一次</p>
        <p>接收端需要使用 <code>eventId</code> 持久化去重</p>
      </div>
    </div>

    <div v-else-if="webhook" class="webhook-card configured-webhook">
      <div class="webhook-heading">
        <h2 id="webhook-title">Webhook 配置</h2>
        <div class="webhook-state-actions">
          <span class="webhook-status" :class="webhook.status === 0 ? 'is-enabled' : 'is-paused'">
            <span aria-hidden="true" />{{ webhook.status === 0 ? '已启用' : '已暂停' }}
          </span>
          <el-button :loading="actionBusy" @click="toggleStatus">{{ webhook.status === 0 ? '暂停投递' : '恢复投递' }}</el-button>
        </div>
      </div>

      <el-alert v-if="webhook.status === 1" class="paused-alert" type="warning" :closable="false" title="投递已暂停；待投递事件不会取消，恢复后将继续处理。" />

      <section class="webhook-section" aria-labelledby="webhook-url-title">
        <div class="section-copy">
          <h3 id="webhook-url-title">回调 URL</h3>
          <el-input v-if="editingUrl" v-model="form.url" class="url-input" :disabled="actionBusy" />
          <code v-else class="code-value">{{ webhook.url }}</code>
        </div>
        <div v-if="editingUrl" class="section-actions">
          <el-button :disabled="actionBusy" @click="cancelUrlEdit">取消</el-button>
          <el-button type="primary" :loading="actionBusy" @click="saveUrl">保存 URL</el-button>
        </div>
        <el-button v-else @click="editingUrl = true">修改 URL</el-button>
      </section>

      <section class="webhook-section" aria-labelledby="webhook-secret-title">
        <div class="section-copy">
          <h3 id="webhook-secret-title">签名密钥</h3>
          <strong>Secret 版本 {{ webhook.currentSecretVersion }}</strong>
          <p>完整 Secret 创建后无法再次查看。</p>
        </div>
        <el-button class="rotate-button" :loading="actionBusy" @click="rotateSecret">轮换 Secret</el-button>
      </section>

      <section class="webhook-section event-section" aria-labelledby="webhook-event-title">
        <div class="section-copy">
          <h3 id="webhook-event-title">通知事件</h3>
          <code class="code-value">screenshot.completed</code>
          <p>仅通知截图成功和最终失败。</p>
          <dl>
            <div><dt>创建时间</dt><dd>{{ formatBeijingTime(webhook.createdAt) }}</dd></div>
            <div><dt>更新时间</dt><dd>{{ formatBeijingTime(webhook.updatedAt) }}</dd></div>
          </dl>
        </div>
      </section>

      <div class="behavior-note" role="note">至少一次投递；普通重试、租约恢复和 Secret 刷新保持同一 <code>eventId</code>。接收端应持久化去重。</div>
    </div>

    <el-dialog :model-value="revealOpen" :title="revealTitle" width="min(720px, 94vw)" destroy-on-close :show-close="false" :close-on-click-modal="false" :close-on-press-escape="false">
      <el-alert type="warning" :closable="false" title="这是你唯一一次查看完整 Secret 的机会。关闭后 Nexus 无法再次显示。" />
      <label class="secret-label" for="webhook-secret">Webhook Secret（仅显示一次）</label>
      <div class="secret-row">
        <el-input id="webhook-secret" :model-value="revealedSecret" readonly class="secret-input" />
        <el-button type="primary" @click="copySecret">{{ copied ? '已复制' : '复制' }}</el-button>
      </div>
      <p class="secret-help">使用原始 Secret 验证 <code>X-Nexus-Signature</code>。请勿将它写入前端代码或日志。</p>
      <el-checkbox v-model="savedConfirmation" class="saved-confirmation">我已经安全保存这个 Secret</el-checkbox>
      <template #footer><el-button type="primary" :disabled="!savedConfirmation" @click="closeSecret">完成</el-button></template>
    </el-dialog>
  </section>
</template>

<style scoped>
.webhook-card { padding: clamp(24px, 4vw, 40px); border: 1px solid var(--nexus-line); border-radius: 16px; background: var(--nexus-surface); box-shadow: var(--nexus-card-shadow); }
h2, h3, p { margin-top: 0; }
h2 { margin-bottom: 10px; font-size: 24px; }
.empty-webhook > p { margin-bottom: 28px; color: var(--nexus-muted); }
.empty-webhook :deep(.el-form-item__label) { color: var(--nexus-ink); font-weight: 650; }
.empty-webhook :deep(.el-form-item__content) { display: block; }
.url-input :deep(input), .secret-input :deep(input) { font-family: 'Consolas', 'SFMono-Regular', monospace; }
.field-help { margin: 8px 0 0; color: var(--nexus-muted); font-size: 13px; line-height: 1.7; }
.behavior-note { margin-top: 26px; padding: 18px 20px; border: 1px solid #d6e5e0; border-radius: 10px; background: #f1f7f3; color: #41645d; font-size: 13px; line-height: 1.8; }
.behavior-note p { margin: 3px 0; }
.behavior-note code, .secret-help code { font-family: 'Consolas', 'SFMono-Regular', monospace; }
.webhook-heading, .webhook-state-actions, .webhook-section, .section-actions, .secret-row { display: flex; align-items: center; gap: 12px; }
.webhook-heading { justify-content: space-between; margin-bottom: 22px; }
.webhook-heading h2 { margin: 0; }
.webhook-status { display: inline-flex; align-items: center; gap: 8px; padding: 7px 14px; border-radius: 999px; font-size: 13px; font-weight: 650; white-space: nowrap; }
.webhook-status > span { width: 8px; height: 8px; border-radius: 50%; background: currentColor; }
.webhook-status.is-enabled { background: var(--nexus-success-surface); color: var(--nexus-success-ink); }
.webhook-status.is-paused { background: var(--nexus-warning-surface); color: var(--nexus-warning-ink); }
.paused-alert { margin-bottom: 16px; }
.webhook-section { justify-content: space-between; min-height: 88px; margin-top: 14px; padding: 20px; border: 1px solid var(--nexus-line); border-radius: 12px; }
.section-copy { min-width: 0; flex: 1; }
.section-copy h3 { margin-bottom: 10px; font-size: 17px; }
.section-copy p { margin: 7px 0 0; color: var(--nexus-muted); font-size: 13px; }
.code-value { display: block; overflow-wrap: anywhere; padding: 9px 12px; border-radius: 7px; background: #eef3ef; font-family: 'Consolas', 'SFMono-Regular', monospace; font-size: 13px; }
.rotate-button { border-color: #d99b2b; color: #a86400; }
.event-section { align-items: flex-start; }
dl { margin: 16px 0 0; }
dl > div { display: grid; grid-template-columns: minmax(110px, 25%) 1fr; gap: 18px; padding: 10px 0; border-top: 1px solid #e9eee7; }
dt { color: var(--nexus-muted); font-size: 13px; }
dd { margin: 0; font-size: 13px; }
.webhook-error { color: #8a3c33; }
.webhook-error p { margin: 12px 0 22px; }
.secret-label { display: block; margin: 22px 0 8px; font-size: 13px; font-weight: 650; }
.secret-input { min-width: 0; }
.secret-help { margin: 16px 0 0; color: var(--nexus-muted); font-size: 13px; line-height: 1.8; }
.saved-confirmation { margin-top: 20px; }
@media (max-width: 650px) {
  .webhook-heading, .webhook-section { align-items: stretch; flex-direction: column; }
  .webhook-state-actions { align-items: stretch; flex-direction: column; }
  .webhook-status { align-self: flex-start; }
  .webhook-section > .el-button, .section-actions, .section-actions > .el-button { width: 100%; }
  .secret-row { align-items: stretch; flex-direction: column; }
  .secret-row > .el-button { width: 100%; min-height: 44px; }
  dl > div { grid-template-columns: 1fr; gap: 5px; }
}
</style>
