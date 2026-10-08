<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ArrowLeft, CircleCheck, CircleClose, Delete, EditPen } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import brandSpark from '@/assets/icons/brand-spark.svg'
import { deleteApplication, listApplications, updateApplication, type Application } from '@/api/applications'
import { applicationErrorMessage } from '@/api/applicationErrors'
import ApiKeysPanel from './ApiKeysPanel.vue'

const route = useRoute()
const router = useRouter()
const application = ref<Application | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const actionBusy = ref(false)
const activeTab = computed(() => route.query.tab === 'keys' ? 'keys' : 'overview')
let loadVersion = 0

async function load() {
  const version = ++loadVersion
  const id = Number(route.params.id)
  application.value = null
  if (!Number.isSafeInteger(id) || id <= 0) {
    errorMessage.value = '应用地址无效'
    loading.value = false
    return
  }

  loading.value = true
  errorMessage.value = ''
  try {
    const applications = await listApplications({ applicationId: id })
    if (version !== loadVersion) return
    application.value = applications.records[0] ?? null
    if (!application.value) errorMessage.value = '这个应用不存在，或你没有访问权限'
  } catch (error) {
    if (version === loadVersion) errorMessage.value = applicationErrorMessage(error, '加载应用失败，请稍后重试')
  } finally {
    if (version === loadVersion) loading.value = false
  }
}

function formatDate(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}

async function rename() {
  const current = application.value
  if (!current || actionBusy.value) return

  let name: string
  try {
    const answer = await ElMessageBox.prompt('为这个应用设置一个清晰的名称。', '编辑应用名称', {
      inputValue: current.name,
      inputPlaceholder: '应用名称',
      inputValidator: (value) => {
        const length = value.trim().length
        return (length > 0 && length <= 64) || '请输入 1–64 个字符的应用名称'
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
    application.value = await updateApplication({ id: current.id, name })
    ElMessage.success('应用名称已更新')
  } catch (error) {
    ElMessage.error(applicationErrorMessage(error, '保存失败，请稍后重试'))
  } finally {
    actionBusy.value = false
  }
}

async function toggleStatus() {
  const current = application.value
  if (!current || actionBusy.value) return
  const disabling = current.status === 0
  try {
    await ElMessageBox.confirm(
      disabling
        ? `禁用「${current.name}」后，所属 API Key 将无法调用开放 API。确定继续吗？`
        : `确定重新启用「${current.name}」吗？`,
      disabling ? '禁用应用' : '启用应用',
      { type: disabling ? 'warning' : 'info', confirmButtonText: disabling ? '确认禁用' : '确认启用', cancelButtonText: '取消' },
    )
  } catch {
    return
  }

  actionBusy.value = true
  try {
    application.value = await updateApplication({ id: current.id, status: disabling ? 1 : 0 })
    ElMessage.success(disabling ? '应用已禁用' : '应用已启用')
  } catch (error) {
    ElMessage.error(applicationErrorMessage(error, '修改状态失败，请稍后重试'))
  } finally {
    actionBusy.value = false
  }
}

async function remove() {
  const current = application.value
  if (!current || actionBusy.value) return
  try {
    await ElMessageBox.prompt(
      `删除后，应用及所属 API Key 将无法继续使用。请输入「${current.name}」以确认。`,
      '删除应用',
      {
        type: 'warning',
        inputPlaceholder: '输入应用名称',
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
    await deleteApplication(current.id)
    ElMessage.success('应用已删除')
    await router.replace({ name: 'applications' })
  } catch (error) {
    ElMessage.error(applicationErrorMessage(error, '删除失败，请稍后重试'))
  } finally {
    actionBusy.value = false
  }
}

onMounted(() => { void load() })
watch(() => route.params.id, () => { void load() })
</script>

<template>
  <section class="detail-page" aria-labelledby="detail-title">
    <RouterLink class="back-link" :to="{ name: 'applications' }"><el-icon :size="16" aria-hidden="true"><ArrowLeft /></el-icon>返回 Applications</RouterLink>

    <div v-if="loading" class="detail-card" aria-busy="true"><el-skeleton :rows="5" animated /></div>
    <div v-else-if="errorMessage" class="detail-card error-card" role="alert">
      <h1 id="detail-title">暂时无法查看应用</h1>
      <p>{{ errorMessage }}</p>
      <el-button @click="load">重试</el-button>
    </div>
    <template v-else-if="application">
      <div class="detail-heading">
        <div>
          <p class="eyebrow">APPLICATION <img :src="brandSpark" alt="" aria-hidden="true" /></p>
          <div class="name-row">
            <h1 id="detail-title">{{ application.name }}</h1>
            <span class="status-pill" :class="application.status === 0 ? 'is-active' : 'is-disabled'">
              <span class="status-dot" aria-hidden="true"></span>
              {{ application.status === 0 ? '运行中' : '已禁用' }}
            </span>
          </div>
        </div>
        <div class="heading-actions" role="group" aria-label="应用操作">
          <el-tooltip content="编辑名称" placement="top"><el-button class="icon-action" :disabled="actionBusy" aria-label="编辑应用名称" @click="rename"><el-icon :size="18" aria-hidden="true"><EditPen /></el-icon></el-button></el-tooltip>
          <el-tooltip :content="application.status === 0 ? '禁用应用' : '启用应用'" placement="top"><el-button class="icon-action warning-action" :disabled="actionBusy" :aria-label="application.status === 0 ? '禁用应用' : '启用应用'" @click="toggleStatus"><el-icon :size="18" aria-hidden="true"><CircleClose v-if="application.status === 0" /><CircleCheck v-else /></el-icon></el-button></el-tooltip>
          <el-tooltip content="删除应用" placement="top"><el-button class="icon-action danger-action" :disabled="actionBusy" aria-label="删除应用" @click="remove"><el-icon :size="18" aria-hidden="true"><Delete /></el-icon></el-button></el-tooltip>
        </div>
      </div>

      <nav class="detail-tabs" aria-label="应用详情分区">
        <RouterLink :to="{ name: 'application-detail', params: { id: application.id } }" :class="{ 'is-current': activeTab === 'overview' }" :aria-current="activeTab === 'overview' ? 'page' : undefined">概览</RouterLink>
        <RouterLink :to="{ name: 'application-detail', params: { id: application.id }, query: { tab: 'keys' } }" :class="{ 'is-current': activeTab === 'keys' }" :aria-current="activeTab === 'keys' ? 'page' : undefined">API Keys</RouterLink>
      </nav>

      <div v-if="activeTab === 'overview'" class="detail-card">
        <h2>应用信息</h2>
        <dl>
          <div><dt>应用名称</dt><dd>{{ application.name }}</dd></div>
          <div><dt>状态</dt><dd>{{ application.status === 0 ? '运行中' : '已禁用' }}</dd></div>
          <div><dt>创建时间</dt><dd>{{ formatDate(application.createdAt) }}</dd></div>
          <div><dt>更新时间</dt><dd>{{ formatDate(application.updatedAt) }}</dd></div>
        </dl>
      </div>
      <ApiKeysPanel v-else :key="application.id" :application="application" />
    </template>
  </section>
</template>

<style scoped>
.detail-page { max-width: 1320px; }
.back-link { display: inline-flex; align-items: center; gap: 6px; margin-bottom: 38px; color: var(--nexus-teal); font-size: 13px; font-weight: 600; text-decoration: none; }
.back-link:hover { text-decoration: underline; }
.eyebrow { margin: 0 0 12px; color: var(--nexus-teal); font-size: 12px; font-weight: 700; letter-spacing: .1em; }
.eyebrow img { width: 14px; height: 14px; vertical-align: -2px; }
.detail-heading { display: flex; align-items: center; justify-content: space-between; gap: 24px; margin-bottom: 42px; }
.heading-actions { display: flex; flex: none; gap: 10px; }
.heading-actions :deep(.el-button + .el-button) { margin-left: 0; }
.heading-actions :deep(.icon-action) { display: grid; width: 44px; height: 44px; place-items: center; padding: 0; border-color: #c8dbd5; border-radius: 9px; background: var(--nexus-surface); color: var(--nexus-ink); }
.heading-actions :deep(.warning-action) { border-color: #edcb95; color: var(--nexus-warning-ink); }
.heading-actions :deep(.danger-action) { border-color: #e4aaa2; color: #aa453c; }
.heading-actions :deep(.icon-action:hover) { background: var(--nexus-sage); }
.heading-actions :deep(.danger-action:hover) { background: #fff1ee; }
.name-row { display: flex; flex-wrap: wrap; align-items: center; gap: 18px; }
h1 { overflow-wrap: anywhere; margin: 0; color: var(--nexus-ink); font-size: clamp(30px, 3vw, 42px); }
.detail-tabs { display: flex; gap: 8px; margin-bottom: 30px; border-bottom: 1px solid var(--nexus-line); }
.detail-tabs a { display: inline-block; padding: 0 18px 14px; border-bottom: 2px solid transparent; color: var(--nexus-muted); font-size: 15px; font-weight: 600; text-decoration: none; }
.detail-tabs a:hover { color: var(--nexus-teal); }
.detail-tabs a.is-current { border-bottom-color: var(--nexus-teal); color: var(--nexus-teal); }
.status-pill { display: inline-flex; align-items: center; gap: 8px; padding: 7px 14px; border-radius: 999px; font-size: 13px; font-weight: 650; white-space: nowrap; }
.status-pill.is-active { background: var(--nexus-success-surface); color: var(--nexus-success-ink); }
.status-pill.is-disabled { background: var(--nexus-warning-surface); color: var(--nexus-warning-ink); }
.status-dot { width: 8px; height: 8px; border-radius: 50%; background: currentColor; }
.detail-card { padding: clamp(24px, 4vw, 40px); border: 1px solid var(--nexus-line); border-radius: 16px; background: var(--nexus-surface); box-shadow: var(--nexus-card-shadow); }
h2 { margin: 0 0 24px; font-size: 20px; }
dl { margin: 0; }
dl > div { display: grid; grid-template-columns: minmax(150px, 32%) 1fr; gap: 24px; padding: 19px 0; border-top: 1px solid #e9eee7; }
dt { color: var(--nexus-muted); font-size: 13px; }
dd { overflow-wrap: anywhere; margin: 0; font-size: 14px; }
.error-card { color: #8a3c33; }
.error-card p { margin: 12px 0 22px; }
@media (max-width: 650px) { .detail-heading { align-items: flex-start; flex-direction: column; } dl > div { grid-template-columns: 1fr; gap: 6px; } }
</style>
