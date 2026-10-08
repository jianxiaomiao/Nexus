<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { ArrowRight, Plus } from '@element-plus/icons-vue'
import type { Application } from '@/api/applications'
import { createApiKey, listApiKeys, type ApiKey } from '@/api/apiKeys'
import { apiKeyErrorMessage } from '@/api/apiKeyErrors'
import ListPagination from '@/components/ListPagination.vue'

const props = defineProps<{ application: Application }>()
const router = useRouter()
const keys = ref<ApiKey[]>([])
const current = ref(1)
const size = ref(10)
const total = ref(0)
const loading = ref(false)
const loadError = ref('')
const creating = ref(false)
const createDialogOpen = ref(false)
const formRef = ref<FormInstance>()
const form = reactive({ name: '' })
const rules: FormRules<typeof form> = {
  name: [
    { required: true, whitespace: true, message: '请输入 Key 名称', trigger: 'blur' },
    { max: 64, message: 'Key 名称不能超过 64 个字符', trigger: 'blur' },
  ],
}

// 完整凭证只在本组件的创建成功弹窗内短暂保留，不进入 Store、路由或日志。
const rawKey = ref('')
const revealOpen = ref(false)
const savedConfirmation = ref(false)
const copied = ref(false)
let copyResetTimer: ReturnType<typeof setTimeout> | undefined
let removeNavigationGuard: (() => void) | undefined

function warnBeforeUnload(event: BeforeUnloadEvent) {
  if (!rawKey.value || savedConfirmation.value) return
  event.preventDefault()
  event.returnValue = ''
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const page = await listApiKeys(props.application.id, { current: current.value, size: size.value })
    if (current.value > 1 && page.records.length === 0 && page.total > 0) {
      current.value = Math.ceil(page.total / size.value)
      await load()
      return
    }
    keys.value = page.records
    total.value = page.total
  } catch (error) {
    loadError.value = apiKeyErrorMessage(error, '加载 API Key 失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

function changePage(value: number) { current.value = value; void load() }
function changeSize(value: number) { size.value = value; current.value = 1; void load() }

function openCreate() {
  if (props.application.status !== 0) return
  form.name = ''
  formRef.value?.clearValidate()
  createDialogOpen.value = true
}

async function submitCreate() {
  if (creating.value || !formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }

  creating.value = true
  try {
    const response = await createApiKey(props.application.id, form.name.trim())
    createDialogOpen.value = false
    savedConfirmation.value = false
    copied.value = false
    rawKey.value = response.apiKey
    revealOpen.value = true
    void load()
  } catch (error) {
    ElMessage.error(apiKeyErrorMessage(error, '创建 API Key 失败，请稍后重试'))
  } finally {
    creating.value = false
  }
}

async function copyKey() {
  if (!rawKey.value) return
  try {
    await navigator.clipboard.writeText(rawKey.value)
    copied.value = true
    ElMessage.success('已复制，请妥善保存')
    if (copyResetTimer) clearTimeout(copyResetTimer)
    copyResetTimer = setTimeout(() => { copied.value = false }, 2000)
  } catch {
    ElMessage.error('复制失败，请手动选择并复制')
  }
}

function closeReveal() {
  if (!savedConfirmation.value) return
  rawKey.value = ''
  revealOpen.value = false
  copied.value = false
}

function formatDate(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium' }).format(date)
}

onMounted(() => {
  void load()
  window.addEventListener('beforeunload', warnBeforeUnload)
  removeNavigationGuard = router.beforeEach(() => {
    if (!rawKey.value || savedConfirmation.value) return
    ElMessage.warning('请先保存完整 API Key，并勾选确认后再离开')
    return false
  })
})
onUnmounted(() => {
  rawKey.value = ''
  if (copyResetTimer) clearTimeout(copyResetTimer)
  window.removeEventListener('beforeunload', warnBeforeUnload)
  removeNavigationGuard?.()
})
</script>

<template>
  <section class="keys-panel" aria-labelledby="keys-title">
    <div class="panel-heading">
      <div>
        <h2 id="keys-title">API Keys</h2>
        <p>为「{{ application.name }}」管理调用开放 API 的凭证。</p>
      </div>
      <el-button type="primary" :icon="Plus" :disabled="application.status !== 0" @click="openCreate">创建 API Key</el-button>
    </div>

    <el-alert v-if="application.status !== 0" class="disabled-alert" type="warning" :closable="false" title="应用已禁用，现有 Key 无法调用开放 API，也不能创建新 Key。" />

    <div v-if="loadError" class="notice error-notice" role="alert">
      <div><strong>暂时无法加载 API Key</strong><p>{{ loadError }}</p></div>
      <el-button @click="load">重试</el-button>
    </div>
    <div v-else-if="loading" class="panel-card" aria-busy="true"><el-skeleton :rows="4" animated /></div>
    <div v-else-if="keys.length === 0" class="empty-state">
      <span class="empty-mark" aria-hidden="true">✦</span>
      <h3>还没有 API Key</h3>
      <p>创建一枚 Key 后，完整凭证只会显示一次。</p>
      <el-button v-if="application.status === 0" type="primary" :icon="Plus" @click="openCreate">创建 API Key</el-button>
    </div>
    <div v-else class="panel-card">
      <el-table class="key-table" :data="keys" row-key="id" table-layout="auto" style="width: 100%">
        <el-table-column label="名称" min-width="180"><template #default="scope"><strong>{{ scope.row.name }}</strong></template></el-table-column>
        <el-table-column label="Key Preview" min-width="190"><template #default="scope"><code>{{ scope.row.keyPreview }}</code></template></el-table-column>
        <el-table-column label="状态" width="115"><template #default="scope"><span class="status-pill" :class="scope.row.status === 0 ? 'is-active' : 'is-disabled'"><span class="status-dot" aria-hidden="true" />{{ scope.row.status === 0 ? '运行中' : '已禁用' }}</span></template></el-table-column>
        <el-table-column label="创建时间" min-width="140"><template #default="scope">{{ formatDate(scope.row.createdAt) }}</template></el-table-column>
        <el-table-column label="操作" width="115"><template #default="scope"><RouterLink class="detail-link" :to="{ name: 'api-key-detail', params: { applicationId: application.id, keyId: scope.row.id } }">查看详情 <el-icon :size="15" aria-hidden="true"><ArrowRight /></el-icon></RouterLink></template></el-table-column>
      </el-table>

      <div class="mobile-key-list">
        <article v-for="key in keys" :key="key.id" class="mobile-key">
          <div class="mobile-key-heading"><strong>{{ key.name }}</strong><span class="status-pill" :class="key.status === 0 ? 'is-active' : 'is-disabled'"><span class="status-dot" aria-hidden="true" />{{ key.status === 0 ? '运行中' : '已禁用' }}</span></div>
          <code>{{ key.keyPreview }}</code>
          <p>创建时间 {{ formatDate(key.createdAt) }}</p>
          <RouterLink class="detail-link" :to="{ name: 'api-key-detail', params: { applicationId: application.id, keyId: key.id } }">查看详情 <el-icon :size="15" aria-hidden="true"><ArrowRight /></el-icon></RouterLink>
        </article>
      </div>
    </div>

    <ListPagination v-if="!loadError && total > 0" :current="current" :size="size" :total="total" @page-change="changePage" @size-change="changeSize" />

    <el-dialog v-model="createDialogOpen" title="创建 API Key" width="min(440px, 92vw)" destroy-on-close>
      <p class="dialog-hint">用一个名称区分这枚 Key 的用途。</p>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submitCreate">
        <el-form-item label="Key 名称" prop="name"><el-input v-model="form.name" maxlength="64" show-word-limit placeholder="例如：生产环境 Key" autocomplete="off" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="createDialogOpen = false">取消</el-button><el-button type="primary" :loading="creating" @click="submitCreate">创建 Key</el-button></template>
    </el-dialog>

    <el-dialog :model-value="revealOpen" title="API Key 创建成功" width="min(560px, 94vw)" destroy-on-close :show-close="false" :close-on-click-modal="false" :close-on-press-escape="false">
      <p class="secret-warning">这是完整 API Key，关闭后将无法再次查看。请现在复制并保存在安全的位置。</p>
      <label class="secret-label" for="new-api-key">完整 API Key</label>
      <div class="secret-row"><el-input id="new-api-key" :model-value="rawKey" readonly class="secret-input" /><el-button type="primary" @click="copyKey">{{ copied ? '已复制' : '复制' }}</el-button></div>
      <el-checkbox v-model="savedConfirmation" class="saved-confirmation">我已经保存这枚 API Key</el-checkbox>
      <template #footer><el-button type="primary" :disabled="!savedConfirmation" @click="closeReveal">完成</el-button></template>
    </el-dialog>
  </section>
</template>

<style scoped>
.keys-panel { min-width: 0; }
.panel-heading { display: flex; align-items: center; justify-content: space-between; gap: 20px; margin-bottom: 22px; }
.panel-heading h2 { margin: 0; color: var(--nexus-ink); font-size: 21px; }
.panel-heading p { margin: 7px 0 0; color: var(--nexus-muted); font-size: 13px; }
.panel-heading :deep(.el-button) { min-height: 44px; }
.disabled-alert { margin-bottom: 18px; }
.panel-card, .empty-state { padding: 24px; border: 1px solid var(--nexus-line); border-radius: 16px; background: var(--nexus-surface); box-shadow: var(--nexus-card-shadow); }
.panel-card :deep(.el-table) { --el-table-header-bg-color: var(--nexus-surface); --el-table-bg-color: var(--nexus-surface); --el-table-tr-bg-color: var(--nexus-surface); }
.panel-card :deep(.el-table__header th) { color: var(--nexus-muted); font-weight: 600; }
.panel-card code, .mobile-key code, .secret-input :deep(input) { font-family: 'Consolas', 'SFMono-Regular', monospace; }
.panel-card code { font-size: 12px; }
.detail-link { display: inline-flex; align-items: center; gap: 4px; color: var(--nexus-teal); font-size: 13px; font-weight: 650; text-decoration: none; white-space: nowrap; }
.detail-link:hover { text-decoration: underline; }
.status-pill { display: inline-flex; align-items: center; gap: 7px; padding: 5px 10px; border-radius: 999px; font-size: 12px; font-weight: 650; white-space: nowrap; }
.status-pill.is-active { background: var(--nexus-success-surface); color: var(--nexus-success-ink); }
.status-pill.is-disabled { background: var(--nexus-warning-surface); color: var(--nexus-warning-ink); }
.status-dot { width: 7px; height: 7px; border-radius: 50%; background: currentColor; }
.empty-state { display: grid; justify-items: center; padding: 50px 24px; text-align: center; }
.empty-mark { display: grid; width: 54px; height: 54px; place-items: center; border-radius: 14px; background: var(--nexus-sage); color: var(--nexus-gold); font-size: 30px; }
.empty-state h3 { margin: 18px 0 0; font-size: 20px; }
.empty-state p { margin: 8px 0 20px; color: var(--nexus-muted); font-size: 13px; }
.notice { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 24px; padding: 18px 20px; border-radius: 12px; }
.error-notice { border: 1px solid #efd8d2; background: #fff8f5; color: #8a3c33; }
.notice p { margin: 4px 0 0; font-size: 13px; }
.mobile-key-list { display: none; }
.dialog-hint { margin: -4px 0 20px; color: var(--nexus-muted); font-size: 13px; }
.secret-warning { margin: 0 0 22px; color: var(--nexus-ink); line-height: 1.7; }
.secret-label { display: block; margin-bottom: 8px; font-size: 13px; font-weight: 600; }
.secret-row { display: flex; gap: 10px; }
.secret-input { min-width: 0; }
.saved-confirmation { margin-top: 20px; }
@media (max-width: 700px) {
  .panel-heading { align-items: flex-start; flex-direction: column; }
  .key-table { display: none; }
  .mobile-key-list { display: grid; }
  .panel-card { padding: 0 18px; }
  .mobile-key { padding: 20px 0; border-bottom: 1px solid var(--nexus-line); }
  .mobile-key:last-child { border-bottom: 0; }
  .mobile-key-heading { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 10px; }
  .mobile-key code { display: block; margin-top: 14px; overflow-wrap: anywhere; font-size: 12px; }
  .mobile-key p { margin: 12px 0; color: var(--nexus-muted); font-size: 12px; }
  .secret-row { flex-direction: column; }
}
</style>
