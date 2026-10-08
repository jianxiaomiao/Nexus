<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ArrowLeft, CircleCheck, CircleClose, Delete, EditPen } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { Application } from '@/api/applications'
import { listApplications } from '@/api/applications'
import { deleteApiKey, listApiKeys, updateApiKey, type ApiKey } from '@/api/apiKeys'
import { apiKeyErrorMessage } from '@/api/apiKeyErrors'
import { applicationErrorMessage } from '@/api/applicationErrors'
import ApiKeyUsagePanel from './ApiKeyUsagePanel.vue'

const route = useRoute()
const router = useRouter()
const application = ref<Application | null>(null)
const apiKey = ref<ApiKey | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const actionBusy = ref(false)
const activeTab = computed(() => route.query.tab === 'short-links' || route.query.tab === 'usage' ? route.query.tab : 'info')
let loadVersion = 0

async function load() {
  const version = ++loadVersion
  const applicationId = Number(route.params.applicationId)
  const keyId = Number(route.params.keyId)
  application.value = null
  apiKey.value = null
  if (!Number.isSafeInteger(applicationId) || applicationId <= 0 || !Number.isSafeInteger(keyId) || keyId <= 0) {
    errorMessage.value = 'API Key 地址无效'
    loading.value = false
    return
  }

  loading.value = true
  errorMessage.value = ''
  try {
    const applications = await listApplications({ applicationId })
    if (version !== loadVersion) return
    application.value = applications.records[0] ?? null
    if (!application.value) {
      errorMessage.value = '所属应用不存在，或你没有访问权限'
      return
    }
    const keys = await listApiKeys(applicationId, { apiKeyId: keyId })
    if (version !== loadVersion) return
    apiKey.value = keys.records[0] ?? null
    if (!apiKey.value) errorMessage.value = '这个 API Key 不存在，或你没有访问权限'
  } catch (error) {
    if (version === loadVersion) {
      errorMessage.value = application.value
        ? apiKeyErrorMessage(error, '加载 API Key 失败，请稍后重试')
        : applicationErrorMessage(error, '加载所属应用失败，请稍后重试')
    }
  } finally {
    if (version === loadVersion) loading.value = false
  }
}

function formatDate(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'medium' }).format(date)
}

async function rename() {
  const current = apiKey.value
  if (!current || actionBusy.value) return

  let name: string
  try {
    const answer = await ElMessageBox.prompt('为这枚 API Key 设置一个清晰的名称。', '编辑 Key 名称', {
      inputValue: current.name,
      inputPlaceholder: 'Key 名称',
      inputValidator: (value) => {
        const length = value.trim().length
        return (length > 0 && length <= 64) || '请输入 1–64 个字符的 Key 名称'
      },
      confirmButtonText: '保存修改',
      cancelButtonText: '取消',
    })
    name = answer.value.trim()
  } catch {
    return
  }

  if (name === current.name) return
  actionBusy.value = true
  try {
    apiKey.value = await updateApiKey({ applicationId: current.applicationId, apiKeyId: current.id, name })
    ElMessage.success('Key 名称已更新')
  } catch (error) {
    ElMessage.error(apiKeyErrorMessage(error, '保存失败，请稍后重试'))
  } finally {
    actionBusy.value = false
  }
}

async function toggleStatus() {
  const current = apiKey.value
  if (!current || actionBusy.value) return
  const disabling = current.status === 0
  try {
    await ElMessageBox.confirm(
      disabling ? `禁用「${current.name}」后，它将无法调用开放 API。确定继续吗？` : `确定重新启用「${current.name}」吗？`,
      disabling ? '禁用 API Key' : '启用 API Key',
      { type: disabling ? 'warning' : 'info', confirmButtonText: disabling ? '确认禁用' : '确认启用', cancelButtonText: '取消' },
    )
  } catch {
    return
  }

  actionBusy.value = true
  try {
    apiKey.value = await updateApiKey({ applicationId: current.applicationId, apiKeyId: current.id, status: disabling ? 1 : 0 })
    ElMessage.success(disabling ? 'API Key 已禁用' : 'API Key 已启用')
  } catch (error) {
    ElMessage.error(apiKeyErrorMessage(error, '修改状态失败，请稍后重试'))
  } finally {
    actionBusy.value = false
  }
}

async function remove() {
  const current = apiKey.value
  if (!current || actionBusy.value) return
  try {
    await ElMessageBox.prompt(
      `删除后，这枚 Key 将无法继续使用。请输入「${current.name}」以确认。`,
      '删除 API Key',
      {
        type: 'warning',
        inputPlaceholder: '输入 Key 名称',
        inputValidator: (value) => value === current.name || '名称不一致，请重新输入',
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
      },
    )
  } catch {
    return
  }

  actionBusy.value = true
  try {
    await deleteApiKey(current.applicationId, current.id)
    ElMessage.success('API Key 已删除')
    await router.replace({ name: 'application-detail', params: { id: current.applicationId }, query: { tab: 'keys' } })
  } catch (error) {
    ElMessage.error(apiKeyErrorMessage(error, '删除失败，请稍后重试'))
  } finally {
    actionBusy.value = false
  }
}

onMounted(() => { void load() })
watch([() => route.params.applicationId, () => route.params.keyId], () => { void load() })
</script>

<template>
  <section class="key-detail-page" aria-labelledby="key-title">
    <RouterLink class="back-link" :to="{ name: 'application-detail', params: { id: route.params.applicationId }, query: { tab: 'keys' } }"><el-icon :size="16" aria-hidden="true"><ArrowLeft /></el-icon>返回 API Keys</RouterLink>

    <div v-if="loading" class="detail-card" aria-busy="true"><el-skeleton :rows="7" animated /></div>
    <div v-else-if="errorMessage" class="detail-card error-card" role="alert">
      <h1 id="key-title">暂时无法查看 API Key</h1>
      <p>{{ errorMessage }}</p>
      <el-button @click="load">重试</el-button>
    </div>
    <template v-else-if="apiKey && application">
      <div class="detail-heading">
        <div>
          <div class="name-row">
            <h1 id="key-title">{{ apiKey.name }}</h1>
            <span class="status-pill" :class="apiKey.status === 0 ? 'is-active' : 'is-disabled'"><span class="status-dot" aria-hidden="true" />{{ apiKey.status === 0 ? '运行中' : '已禁用' }}</span>
          </div>
          <p>管理这枚密钥的信息、短链接与调用统计。</p>
        </div>
        <div class="heading-actions" role="group" aria-label="API Key 操作">
          <el-tooltip content="编辑名称" placement="top"><el-button class="icon-action" :disabled="actionBusy" aria-label="编辑 Key 名称" @click="rename"><el-icon :size="18" aria-hidden="true"><EditPen /></el-icon></el-button></el-tooltip>
          <el-tooltip :content="apiKey.status === 0 ? '禁用 Key' : '启用 Key'" placement="top"><el-button class="icon-action warning-action" :disabled="actionBusy" :aria-label="apiKey.status === 0 ? '禁用 Key' : '启用 Key'" @click="toggleStatus"><el-icon :size="18" aria-hidden="true"><CircleClose v-if="apiKey.status === 0" /><CircleCheck v-else /></el-icon></el-button></el-tooltip>
          <el-tooltip content="删除 Key" placement="top"><el-button class="icon-action danger-action" :disabled="actionBusy" aria-label="删除 Key" @click="remove"><el-icon :size="18" aria-hidden="true"><Delete /></el-icon></el-button></el-tooltip>
        </div>
      </div>

      <el-alert v-if="application.status !== 0" class="disabled-alert" type="warning" :closable="false" title="所属应用已禁用；即使此 Key 处于运行中，也无法调用开放 API。" />

      <el-scrollbar class="detail-tabs"><nav class="detail-tabs-inner" aria-label="API Key 详情分区">
        <RouterLink :to="{ name: 'api-key-detail', params: { applicationId: application.id, keyId: apiKey.id } }" :class="{ 'is-current': activeTab === 'info' }" :aria-current="activeTab === 'info' ? 'page' : undefined">密钥信息</RouterLink>
        <RouterLink :to="{ name: 'api-key-detail', params: { applicationId: application.id, keyId: apiKey.id }, query: { tab: 'short-links' } }" :class="{ 'is-current': activeTab === 'short-links' }" :aria-current="activeTab === 'short-links' ? 'page' : undefined">短链接</RouterLink>
        <RouterLink :to="{ name: 'api-key-detail', params: { applicationId: application.id, keyId: apiKey.id }, query: { tab: 'usage' } }" :class="{ 'is-current': activeTab === 'usage' }" :aria-current="activeTab === 'usage' ? 'page' : undefined">调用统计</RouterLink>
      </nav></el-scrollbar>

      <div v-if="activeTab === 'info'" class="detail-card">
        <h2>密钥信息</h2>
        <dl>
          <div><dt>名称</dt><dd>{{ apiKey.name }}</dd></div>
          <div><dt>所属应用</dt><dd><RouterLink class="application-link" :to="{ name: 'application-detail', params: { id: application.id } }">{{ application.name }}</RouterLink></dd></div>
          <div><dt>Key Preview</dt><dd><code>{{ apiKey.keyPreview }}</code></dd></div>
          <div><dt>Public ID</dt><dd><code>{{ apiKey.publicId }}</code></dd></div>
          <div><dt>状态</dt><dd>{{ apiKey.status === 0 ? '运行中' : '已禁用' }}</dd></div>
          <div><dt>创建时间</dt><dd>{{ formatDate(apiKey.createdAt) }}</dd></div>
          <div><dt>更新时间</dt><dd>{{ formatDate(apiKey.updatedAt) }}</dd></div>
        </dl>
        <div class="secret-notice" role="note">完整 API Key 仅在创建时显示，之后无法再次查看。</div>
      </div>
      <div v-else-if="activeTab === 'short-links'" class="detail-card short-links-entry">
        <div><h2>短链接</h2><p>查看和管理这枚 Key 创建的短链。创建新短链需要使用保存的完整 API Key。</p></div>
        <RouterLink :to="{ name: 'short-links', query: { applicationId: application.id, apiKeyId: apiKey.id } }">查看短链接 →</RouterLink>
      </div>
      <ApiKeyUsagePanel v-else :application-id="application.id" :api-key-id="apiKey.id" />
    </template>
  </section>
</template>

<style scoped>
.key-detail-page { max-width: 1320px; }
.back-link { display: inline-flex; align-items: center; gap: 6px; margin-bottom: 38px; color: var(--nexus-teal); font-size: 13px; font-weight: 600; text-decoration: none; }
.back-link:hover { text-decoration: underline; }
.detail-heading { display: flex; align-items: center; justify-content: space-between; gap: 24px; margin-bottom: 34px; }
.name-row { display: flex; flex-wrap: wrap; align-items: center; gap: 18px; }
h1 { overflow-wrap: anywhere; margin: 0; color: var(--nexus-ink); font-size: clamp(30px, 3vw, 42px); }
.detail-heading p { margin: 12px 0 0; color: var(--nexus-muted); }
.heading-actions { display: flex; flex: none; gap: 10px; }
.heading-actions :deep(.el-button + .el-button) { margin-left: 0; }
.heading-actions :deep(.icon-action) { display: grid; width: 44px; height: 44px; place-items: center; padding: 0; border-color: #c8dbd5; border-radius: 9px; background: var(--nexus-surface); color: var(--nexus-ink); }
.heading-actions :deep(.warning-action) { border-color: #edcb95; color: var(--nexus-warning-ink); }
.heading-actions :deep(.danger-action) { border-color: #e4aaa2; color: #aa453c; }
.heading-actions :deep(.icon-action:hover) { background: var(--nexus-sage); }
.heading-actions :deep(.danger-action:hover) { background: #fff1ee; }
.status-pill { display: inline-flex; align-items: center; gap: 8px; padding: 7px 14px; border-radius: 999px; font-size: 13px; font-weight: 650; white-space: nowrap; }
.status-pill.is-active { background: var(--nexus-success-surface); color: var(--nexus-success-ink); }
.status-pill.is-disabled { background: var(--nexus-warning-surface); color: var(--nexus-warning-ink); }
.status-dot { width: 8px; height: 8px; border-radius: 50%; background: currentColor; }
.disabled-alert { margin-bottom: 20px; }
.detail-tabs { margin-bottom: 30px; border-bottom: 1px solid var(--nexus-line); }
.detail-tabs-inner { display: flex; gap: 8px; min-width: max-content; }
.detail-tabs a { flex: none; padding: 0 18px 14px; border-bottom: 2px solid transparent; color: var(--nexus-muted); font-size: 15px; font-weight: 600; text-decoration: none; }
.detail-tabs a:hover, .detail-tabs a.is-current { color: var(--nexus-teal); }
.detail-tabs a.is-current { border-bottom-color: var(--nexus-teal); }
.detail-card { padding: clamp(24px, 4vw, 40px); border: 1px solid var(--nexus-line); border-radius: 16px; background: var(--nexus-surface); box-shadow: var(--nexus-card-shadow); }
h2 { margin: 0 0 24px; font-size: 20px; }
dl { margin: 0; }
dl > div { display: grid; grid-template-columns: minmax(150px, 25%) 1fr; gap: 24px; padding: 17px 0; border-top: 1px solid #e9eee7; }
dt { color: var(--nexus-muted); font-size: 13px; }
dd { overflow-wrap: anywhere; margin: 0; font-size: 14px; }
code { font-family: 'Consolas', 'SFMono-Regular', monospace; font-size: 13px; }
.application-link { color: var(--nexus-teal); text-decoration: none; }
.application-link:hover { text-decoration: underline; }
.secret-notice { margin-top: 20px; padding: 16px 18px; border: 1px solid #d6e5e5; border-radius: 9px; background: #f2f8f8; color: #476775; font-size: 13px; }
.error-card { color: #8a3c33; }
.error-card p { margin: 12px 0 22px; }
.short-links-entry { display: flex; align-items: center; justify-content: space-between; gap: 20px; }
.short-links-entry h2 { margin: 0 0 6px; }
.short-links-entry p { margin: 0; color: var(--nexus-muted); font-size: 13px; }
.short-links-entry a { flex: none; color: var(--nexus-teal); font-size: 14px; font-weight: 650; text-decoration: none; }
.short-links-entry a:hover { text-decoration: underline; }
@media (max-width: 650px) { .detail-heading { align-items: flex-start; flex-direction: column; } dl > div { grid-template-columns: 1fr; gap: 6px; } }
@media (max-width: 650px) { .short-links-entry { align-items: flex-start; flex-direction: column; } }
</style>
