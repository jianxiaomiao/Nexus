<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowRight, CopyDocument, Link, Plus, Refresh } from '@element-plus/icons-vue'
import { listApplications, type Application } from '@/api/applications'
import { listApiKeys, type ApiKey } from '@/api/apiKeys'
import { applicationErrorMessage } from '@/api/applicationErrors'
import { apiKeyErrorMessage } from '@/api/apiKeyErrors'
import ListPagination from '@/components/ListPagination.vue'
import {
  deleteManagedShortLink, listManagedShortLinks, publicShortLinkUrl,
  shortLinkManagementError, updateManagedShortLink, type ShortLink,
} from '@/api/shortLinks'
import '@/assets/shortLinks.css'

const route = useRoute()
const router = useRouter()
const applications = ref<Application[]>([])
const keys = ref<ApiKey[]>([])
const links = ref<ShortLink[]>([])
const applicationCurrent = ref(1)
const applicationTotal = ref(0)
const keyCurrent = ref(1)
const keyTotal = ref(0)
const linkCurrent = ref(1)
const linkSize = ref(10)
const linkTotal = ref(0)
const selectedApplicationId = ref(0)
const selectedKeyId = ref(0)
const loading = ref(true)
const keyLoading = ref(false)
const listLoading = ref(false)
const pageError = ref('')
const listError = ref('')
const actionBusy = ref(false)
const now = ref(Date.now())
let clockTimer: ReturnType<typeof setInterval> | undefined
let loadVersion = 0
let selectionVersion = 0

const selectedApplication = computed(() => applications.value.find((item) => item.id === selectedApplicationId.value))
const selectedKey = computed(() => keys.value.find((item) => item.id === selectedKeyId.value))
const createTarget = computed(() => ({ name: 'playground', query: { endpoint: 'shortlink' } }))

function positiveId(value: unknown): number {
  const id = Number(value)
  return Number.isSafeInteger(id) && id > 0 ? id : 0
}

function formatDate(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}

function expired(link: ShortLink): boolean {
  return now.value >= new Date(link.expiresAt).getTime()
}

function availability(link: ShortLink): { label: string; kind: string } {
  if (link.status === 1) return { label: '已禁用', kind: 'disabled' }
  if (selectedApplication.value?.status === 1 || selectedKey.value?.status === 1) return { label: '上层已禁用', kind: 'disabled' }
  if (expired(link)) return { label: '已到期', kind: 'expired' }
  return { label: '可跳转', kind: 'active' }
}

async function loadLinks(keyId = selectedKeyId.value) {
  const version = ++selectionVersion
  links.value = []
  listError.value = ''
  listLoading.value = false
  if (!keyId) return
  listLoading.value = true
  try {
    const data = await listManagedShortLinks(keyId, { current: linkCurrent.value, size: linkSize.value })
    if (version === selectionVersion) {
      if (data.records.length === 0 && data.total > 0 && linkCurrent.value > 1) {
        linkCurrent.value = Math.ceil(data.total / linkSize.value)
        await loadLinks(keyId)
        return
      }
      links.value = data.records
      linkTotal.value = data.total
    }
  } catch (error) {
    if (version === selectionVersion) listError.value = shortLinkManagementError(error, '加载短链接失败，请稍后重试')
  } finally {
    if (version === selectionVersion) listLoading.value = false
  }
}

async function loadKeys(applicationId: number, preferredKeyId = 0) {
  const version = ++selectionVersion
  keys.value = []
  links.value = []
  selectedKeyId.value = 0
  linkCurrent.value = 1
  linkTotal.value = 0
  listError.value = ''
  if (!applicationId) return
  keyLoading.value = true
  try {
    const data = await listApiKeys(applicationId, { current: keyCurrent.value, size: 10 })
    if (version !== selectionVersion) return
    let chosen = data.records.find((item) => item.id === preferredKeyId)
    if (preferredKeyId && !chosen) {
      const filtered = await listApiKeys(applicationId, { apiKeyId: preferredKeyId })
      if (version !== selectionVersion) return
      chosen = filtered.records[0]
    }
    keys.value = chosen && !data.records.some((item) => item.id === chosen.id) ? [chosen, ...data.records] : data.records
    keyTotal.value = data.total
    selectedKeyId.value = chosen?.id ?? (preferredKeyId ? 0 : data.records[0]?.id ?? 0)
    if (preferredKeyId && !selectedKeyId.value) listError.value = '这枚 API Key 不存在，或你没有访问权限'
    else {
      keyLoading.value = false
      await loadLinks(selectedKeyId.value)
    }
  } catch (error) {
    if (version === selectionVersion) listError.value = apiKeyErrorMessage(error, '加载 API Key 失败，请稍后重试')
  } finally {
    if (version === selectionVersion) keyLoading.value = false
  }
}

async function initialize() {
  const version = ++loadVersion
  loading.value = true
  pageError.value = ''
  const requestedKeyId = positiveId(route.query.apiKeyId)
  const requestedApplicationId = positiveId(route.query.applicationId)
  try {
    if (requestedKeyId && !requestedApplicationId) {
      // 调试台只知道 Key ID；按页检查本人应用，Key 查询仍由现有列表方法按 ID 过滤。
      let page = 1
      while (true) {
        const apps = await listApplications({ current: page, size: 100 })
        if (version !== loadVersion) return
        for (const app of apps.records) {
          const found = await listApiKeys(app.id, { apiKeyId: requestedKeyId })
          if (version !== loadVersion) return
          if (found.records.length) {
            applications.value = [app]
            applicationCurrent.value = 1
            applicationTotal.value = apps.total
            selectedApplicationId.value = app.id
            keys.value = found.records
            keyCurrent.value = 1
            keyTotal.value = found.total
            selectedKeyId.value = requestedKeyId
            linkCurrent.value = 1
            await loadLinks(requestedKeyId)
            return
          }
        }
        if (page * 100 >= apps.total) break
        page++
      }
      selectedApplicationId.value = 0
      selectedKeyId.value = 0
      keys.value = []
      links.value = []
      pageError.value = '这枚 API Key 不存在，或当前账号没有管理权限'
      return
    }
    const data = await listApplications({ current: applicationCurrent.value, size: 10 })
    if (version !== loadVersion) return
    let app = data.records.find((item) => item.id === requestedApplicationId)
    if (requestedApplicationId && !app) {
      const filtered = await listApplications({ applicationId: requestedApplicationId })
      if (version !== loadVersion) return
      app = filtered.records[0]
    }
    const selectedApp = app
    applications.value = selectedApp && !data.records.some((item) => item.id === selectedApp.id) ? [selectedApp, ...data.records] : data.records
    applicationTotal.value = data.total
    if (!data.records.length && !requestedApplicationId) { selectedApplicationId.value = 0; keys.value = []; links.value = []; return }
    if (!requestedApplicationId) app = data.records[0]
    if (!app) { pageError.value = '这个 Application 不存在，或你没有访问权限'; return }
    selectedApplicationId.value = app.id
    keyCurrent.value = 1
    await loadKeys(app.id, requestedKeyId)
  } catch (error) {
    if (version === loadVersion) pageError.value = applicationErrorMessage(error, '加载应用失败，请稍后重试')
  } finally {
    if (version === loadVersion) loading.value = false
  }
}

function changeApplication() {
  void router.replace({ name: 'short-links', query: { applicationId: selectedApplicationId.value || undefined } })
  keyCurrent.value = 1
  void loadKeys(selectedApplicationId.value)
}

function changeKey() {
  void router.replace({ name: 'short-links', query: { applicationId: selectedApplicationId.value, apiKeyId: selectedKeyId.value || undefined } })
  linkCurrent.value = 1
  void loadLinks()
}

async function changeApplicationPage(value: number) {
  applicationCurrent.value = value
  try {
    const data = await listApplications({ current: value, size: 10 })
    applications.value = data.records
    applicationTotal.value = data.total
    selectedApplicationId.value = data.records[0]?.id ?? 0
    keyCurrent.value = 1
    await router.replace({ name: 'short-links', query: { applicationId: selectedApplicationId.value || undefined } })
    await loadKeys(selectedApplicationId.value)
  } catch (error) {
    pageError.value = applicationErrorMessage(error, '加载应用失败，请稍后重试')
  }
}

async function changeKeyPage(value: number) {
  keyCurrent.value = value
  await loadKeys(selectedApplicationId.value)
  await router.replace({ name: 'short-links', query: { applicationId: selectedApplicationId.value,
    apiKeyId: selectedKeyId.value || undefined } })
}

function changeLinkPage(value: number) { linkCurrent.value = value; void loadLinks() }
function changeLinkSize(value: number) { linkSize.value = value; linkCurrent.value = 1; void loadLinks() }

async function clearFilter() {
  await router.replace({ name: 'short-links' })
  applicationCurrent.value = 1
  keyCurrent.value = 1
  linkCurrent.value = 1
  await initialize()
}

async function copyLink(link: ShortLink) {
  try { await navigator.clipboard.writeText(publicShortLinkUrl(link.shortCode)); ElMessage.success('短地址已复制') }
  catch { ElMessage.error('复制失败，请手动选择短地址') }
}

async function rename(link: ShortLink) {
  if (actionBusy.value) return
  let nextName: string
  try {
    const answer = await ElMessageBox.prompt('原始地址和到期时刻不能修改。', '编辑短链名称', {
      inputValue: link.name, inputPlaceholder: '短链名称',
      inputValidator: (value) => (value.trim().length > 0 && value.trim().length <= 64) || '请输入 1～64 个字符的名称',
      confirmButtonText: '保存修改', cancelButtonText: '取消',
    })
    nextName = answer.value.trim()
  } catch { return }
  if (nextName === link.name) return
  actionBusy.value = true
  try {
    const updated = await updateManagedShortLink({ id: link.id, name: nextName })
    links.value = links.value.map((item) => item.id === link.id ? updated : item)
    ElMessage.success('短链名称已更新')
  } catch (error) { ElMessage.error(shortLinkManagementError(error, '保存失败，请稍后重试')) }
  finally { actionBusy.value = false }
}

async function toggle(link: ShortLink) {
  if (actionBusy.value || expired(link)) return
  const disabling = link.status === 0
  try {
    await ElMessageBox.confirm(disabling ? `禁用「${link.name}」后将无法公开跳转。确定继续吗？` : `确定重新启用「${link.name}」吗？`, disabling ? '禁用短链' : '启用短链', {
      type: disabling ? 'warning' : 'info', confirmButtonText: disabling ? '确认禁用' : '确认启用', cancelButtonText: '取消',
    })
  } catch { return }
  actionBusy.value = true
  try {
    const updated = await updateManagedShortLink({ id: link.id, status: disabling ? 1 : 0 })
    links.value = links.value.map((item) => item.id === link.id ? updated : item)
    ElMessage.success(disabling ? '短链已禁用' : '短链已启用')
  } catch (error) { ElMessage.error(shortLinkManagementError(error, '修改状态失败，请稍后重试')) }
  finally { actionBusy.value = false }
}

async function remove(link: ShortLink) {
  if (actionBusy.value) return
  try {
    await ElMessageBox.confirm(`删除「${link.name}」后，短地址永久失效且短码不会复用。确定删除吗？`, '删除短链', {
      type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消',
    })
  } catch { return }
  actionBusy.value = true
  try {
    await deleteManagedShortLink(link.id)
    await loadLinks()
    ElMessage.success('短链已删除')
  } catch (error) { ElMessage.error(shortLinkManagementError(error, '删除失败，请稍后重试')) }
  finally { actionBusy.value = false }
}

onMounted(() => { clockTimer = setInterval(() => { now.value = Date.now() }, 1000); void initialize() })
onUnmounted(() => { if (clockTimer) clearInterval(clockTimer); loadVersion++; selectionVersion++ })
</script>

<template>
  <section class="short-links-page" aria-labelledby="short-links-title">
    <header class="short-links-heading"><div><p class="short-links-eyebrow">NEXUS · SHORT LINKS</p><h1 id="short-links-title">短链接</h1><p>按 Application 和 API Key 查找、复制并管理已创建的短链。</p></div></header>
    <div v-if="loading" class="short-links-card" aria-busy="true"><el-skeleton :rows="7" animated /></div>
    <div v-else-if="pageError" class="short-links-card short-links-error" role="alert"><strong>暂时无法查看短链接</strong><p>{{ pageError }}</p><el-button @click="initialize">重试</el-button><el-button link type="primary" class="short-links-clear-filter" @click="clearFilter">查看我的短链接</el-button></div>
    <template v-else>
      <div v-if="applications.length" class="short-links-card short-links-selectors"><div class="short-links-field"><label for="short-links-application">Application</label><el-select id="short-links-application" v-model="selectedApplicationId" aria-label="Application" @change="changeApplication"><el-option v-for="app in applications" :key="app.id" :label="app.name" :value="app.id" /></el-select></div><div class="short-links-field"><label for="short-links-key">API Key</label><el-select id="short-links-key" v-model="selectedKeyId" aria-label="API Key" :disabled="keyLoading || !keys.length" @change="changeKey"><el-option v-if="!keys.length" :value="0" :label="keyLoading ? '正在加载…' : '此应用没有 API Key'" /><el-option v-for="key in keys" :key="key.id" :label="`${key.name} · ${key.keyPreview}`" :value="key.id" /></el-select></div><p>仅显示当前 Key 创建的短链</p><div class="selector-pages"><ListPagination v-if="applicationTotal > 10" :current="applicationCurrent" :size="10" :total="applicationTotal" :show-size="false" @page-change="changeApplicationPage" /><ListPagination v-if="keyTotal > 10" :current="keyCurrent" :size="10" :total="keyTotal" :show-size="false" @page-change="changeKeyPage" /></div></div>
      <div class="short-links-toolbar"><div><h2>{{ selectedKey ? `${selectedKey.name}的短链接` : '短链接列表' }} <small v-if="selectedKey && !listLoading">{{ linkTotal }} 条</small></h2><p>短链归创建它的 Key；切换 Key 后显示对应列表。</p></div><RouterLink class="short-links-create" :to="createTarget"><el-icon aria-hidden="true"><Plus /></el-icon>去调试台创建短链</RouterLink></div>
      <el-alert v-if="selectedApplication?.status === 1 || selectedKey?.status === 1" class="short-links-parent-warning" type="warning" :closable="false" title="所属 Application 或 API Key 已禁用；即使短链自身启用，也暂时无法跳转。" />
      <div v-if="listError" class="short-links-card short-links-error" role="alert"><strong>暂时无法加载列表</strong><p>{{ listError }}</p><el-button :icon="Refresh" @click="loadLinks()">重试</el-button></div>
      <div v-else-if="listLoading || keyLoading" class="short-links-card" aria-busy="true"><el-skeleton :rows="5" animated /></div>
      <div v-else-if="!selectedKey" class="short-links-card short-links-empty"><el-icon aria-hidden="true"><Link /></el-icon><h3>先选择一枚 API Key</h3><p>创建 Key 后即可按归属查看短链。</p></div>
      <div v-else-if="links.length === 0" class="short-links-card short-links-empty"><el-icon aria-hidden="true"><Link /></el-icon><h3>还没有短链接</h3><p>使用这枚 Key 在 API 调试台创建第一条短链。</p><RouterLink :to="createTarget">前往调试台 <el-icon aria-hidden="true"><ArrowRight /></el-icon></RouterLink></div>
      <template v-else><el-scrollbar class="short-links-card short-links-table-wrap"><table class="short-links-table"><thead><tr><th>名称与短地址</th><th>原始地址</th><th>可用状态</th><th>到期时间</th><th>操作</th></tr></thead><tbody><tr v-for="link in links" :key="link.id"><td><strong>{{ link.name }}</strong><code>{{ publicShortLinkUrl(link.shortCode) }}</code><small>创建于 {{ formatDate(link.createdAt) }} · ID {{ link.id }}</small></td><td class="short-links-target" :title="link.originalUrl">{{ link.originalUrl }}</td><td><span class="short-links-status" :class="availability(link).kind">{{ availability(link).label }}</span></td><td>{{ formatDate(link.expiresAt) }}</td><td><div class="short-links-actions"><el-button link :aria-label="`复制 ${link.name} 的短地址`" @click="copyLink(link)">复制</el-button><el-button link :disabled="actionBusy" :aria-label="`改名 ${link.name}`" @click="rename(link)">改名</el-button><el-button v-if="!expired(link)" link :disabled="actionBusy" :aria-label="`${link.status === 0 ? '禁用' : '启用'} ${link.name}`" @click="toggle(link)">{{ link.status === 0 ? '禁用' : '启用' }}</el-button><el-button link :disabled="actionBusy" :aria-label="`删除 ${link.name}`" @click="remove(link)">删除</el-button></div></td></tr></tbody></table></el-scrollbar>
        <div class="short-links-mobile-list"><article v-for="link in links" :key="link.id" class="short-links-card short-links-mobile-card"><div class="short-links-mobile-heading"><h3>{{ link.name }}</h3><span class="short-links-status" :class="availability(link).kind">{{ availability(link).label }}</span></div><code>{{ publicShortLinkUrl(link.shortCode) }}</code><p>目标：{{ link.originalUrl }}<br />到期：{{ formatDate(link.expiresAt) }}</p><div class="short-links-actions"><el-button link @click="copyLink(link)">复制</el-button><el-button link :disabled="actionBusy" @click="rename(link)">改名</el-button><el-button v-if="!expired(link)" link :disabled="actionBusy" @click="toggle(link)">{{ link.status === 0 ? '禁用' : '启用' }}</el-button><el-button link :disabled="actionBusy" @click="remove(link)">删除</el-button></div></article></div></template>
      <ListPagination v-if="selectedKey && linkTotal > 0" :current="linkCurrent" :size="linkSize" :total="linkTotal" @page-change="changeLinkPage" @size-change="changeLinkSize" />
      <div class="short-links-identity-note"><el-icon aria-hidden="true"><CopyDocument /></el-icon><span><strong>创建与管理使用不同身份。</strong> 创建短链使用完整 API Key；此列表及改名、禁用、删除使用当前登录账号的 JWT。已到期的短链仍保留记录，但不能恢复跳转。</span></div>
    </template>
  </section>
</template>
