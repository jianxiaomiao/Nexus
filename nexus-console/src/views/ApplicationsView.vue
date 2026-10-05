<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { ArrowRight, MoreFilled, Plus } from '@element-plus/icons-vue'
import brandSpark from '@/assets/icons/brand-spark.svg'
import loginIllustration from '@/assets/login_page.png'
import {
  createApplication,
  deleteApplication,
  listApplications,
  updateApplication,
  type Application,
} from '@/api/applications'
import { applicationErrorMessage } from '@/api/applicationErrors'

const applications = ref<Application[]>([])
const loading = ref(false)
const loadError = ref('')
const submitting = ref(false)
const dialogOpen = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()
const form = reactive({ name: '' })

const rules: FormRules<typeof form> = {
  name: [
    { required: true, whitespace: true, message: '请输入应用名称', trigger: 'blur' },
    { max: 64, message: '应用名称不能超过 64 个字符', trigger: 'blur' },
  ],
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    applications.value = await listApplications()
  } catch (error) {
    loadError.value = applicationErrorMessage(error, '加载应用失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  form.name = ''
  formRef.value?.clearValidate()
  dialogOpen.value = true
}

function openEdit(application: Application) {
  editingId.value = application.id
  form.name = application.name
  formRef.value?.clearValidate()
  dialogOpen.value = true
}

async function submitForm() {
  if (submitting.value || !formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }

  submitting.value = true
  try {
    const name = form.name.trim()
    if (editingId.value === null) {
      await createApplication({ name })
      ElMessage.success('应用已创建')
    } else {
      await updateApplication({ id: editingId.value, name })
      ElMessage.success('应用名称已更新')
    }
    dialogOpen.value = false
    await load()
  } catch (error) {
    ElMessage.error(applicationErrorMessage(error, '保存失败，请稍后重试'))
  } finally {
    submitting.value = false
  }
}

async function toggleStatus(application: Application) {
  const disabling = application.status === 0
  try {
    await ElMessageBox.confirm(
      disabling
        ? `禁用「${application.name}」后，所属 API Key 将无法调用开放 API。确定继续吗？`
        : `确定重新启用「${application.name}」吗？`,
      disabling ? '禁用应用' : '启用应用',
      { type: disabling ? 'warning' : 'info', confirmButtonText: disabling ? '确认禁用' : '确认启用', cancelButtonText: '取消' },
    )
  } catch {
    return
  }

  try {
    await updateApplication({ id: application.id, status: disabling ? 1 : 0 })
    ElMessage.success(disabling ? '应用已禁用' : '应用已启用')
    await load()
  } catch (error) {
    ElMessage.error(applicationErrorMessage(error, '修改状态失败，请稍后重试'))
  }
}

async function remove(application: Application) {
  try {
    await ElMessageBox.prompt(
      `删除后，应用及所属 API Key 将无法继续使用。请输入「${application.name}」以确认。`,
      '删除应用',
      {
        type: 'warning',
        inputPlaceholder: '输入应用名称',
        inputValidator: (value) => value === application.name || '名称不一致，请重新输入',
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
      },
    )
  } catch {
    return
  }

  try {
    await deleteApplication(application.id)
    ElMessage.success('应用已删除')
    await load()
  } catch (error) {
    ElMessage.error(applicationErrorMessage(error, '删除失败，请稍后重试'))
  }
}

function formatDate(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium' }).format(date)
}

onMounted(() => { void load() })
</script>

<template>
  <section class="applications-page" aria-labelledby="applications-title">
    <div class="page-heading">
      <div>
        <p class="eyebrow">你的开发者空间 <img :src="brandSpark" alt="" aria-hidden="true" /></p>
        <h1 id="applications-title">Applications</h1>
        <p class="page-description">管理你的应用，接入 Nexus 提供的开放 API 能力。</p>
      </div>
      <el-button type="primary" size="large" :icon="Plus" @click="openCreate">创建 Application</el-button>
    </div>

    <div v-if="loadError" class="notice error-notice" role="alert">
      <div><strong>暂时无法加载应用</strong><p>{{ loadError }}</p></div>
      <el-button @click="load">重试</el-button>
    </div>

    <div v-if="loading" class="application-grid" aria-busy="true">
      <div v-for="item in 3" :key="item" class="application-card skeleton-card">
        <el-skeleton :rows="3" animated />
      </div>
    </div>

    <div v-else-if="!loadError && applications.length === 0" class="empty-state">
      <img class="empty-illustration" :src="loginIllustration" alt="橘猫坐在绿色星球上仰望星星" />
      <h2>从第一个 Application 开始</h2>
      <p>创建应用后，就可以继续为它配置 API Key。</p>
      <el-button type="primary" :icon="Plus" @click="openCreate">创建 Application</el-button>
    </div>

    <div v-else-if="!loadError" class="application-grid">
      <article v-for="application in applications" :key="application.id" class="application-card">
        <div class="card-head">
          <span class="card-emblem" aria-hidden="true"><img :src="brandSpark" alt="" /></span>
        </div>
        <h2>{{ application.name }}</h2>
        <p class="card-description">{{ application.status === 0 ? '可继续配置 API Key 与访问设置。' : '应用已暂停开放 API 调用。' }}</p>
        <span class="status-pill" :class="application.status === 0 ? 'is-active' : 'is-disabled'">
          <span class="status-dot" aria-hidden="true"></span>
          {{ application.status === 0 ? '运行中' : '已禁用' }}
        </span>
        <p class="card-date">创建时间 {{ formatDate(application.createdAt) }}</p>
        <div class="card-actions">
          <RouterLink class="details-link" :to="{ name: 'application-detail', params: { id: application.id } }">
            查看详情 <el-icon :size="16" aria-hidden="true"><ArrowRight /></el-icon>
          </RouterLink>
          <el-dropdown trigger="click">
            <el-button text :aria-label="`更多操作：${application.name}`"><el-icon :size="18" aria-hidden="true"><MoreFilled /></el-icon></el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="openEdit(application)">编辑名称</el-dropdown-item>
                <el-dropdown-item @click="toggleStatus(application)">{{ application.status === 0 ? '禁用应用' : '启用应用' }}</el-dropdown-item>
                <el-dropdown-item divided @click="remove(application)">删除应用</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </article>
    </div>

    <el-dialog v-model="dialogOpen" :title="editingId === null ? '创建 Application' : '编辑 Application'" width="min(440px, 92vw)" destroy-on-close>
      <p class="dialog-hint">一个清晰的名称，方便你以后找到它。</p>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submitForm">
        <el-form-item label="应用名称" prop="name">
          <el-input v-model="form.name" maxlength="64" show-word-limit placeholder="例如：我的第一个应用" autocomplete="off" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitForm">{{ editingId === null ? '创建应用' : '保存修改' }}</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.page-heading { display: flex; align-items: end; justify-content: space-between; gap: 24px; margin-bottom: 44px; }
.eyebrow { margin: 0 0 14px; color: var(--nexus-teal); font-size: 12px; font-weight: 700; letter-spacing: .1em; }
.eyebrow img { width: 14px; height: 14px; vertical-align: -2px; }
h1 { margin: 0; color: var(--nexus-ink); font-family: Georgia, 'Times New Roman', serif; font-size: clamp(36px, 3.4vw, 52px); letter-spacing: -.04em; }
.page-description { margin: 12px 0 0; color: var(--nexus-muted); font-size: 15px; line-height: 1.7; }
.page-heading :deep(.el-button--primary) { min-height: 46px; padding-inline: 22px; border-radius: 10px; box-shadow: 0 7px 14px #247b701b; }
.application-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(100%, 320px), 1fr)); gap: 20px; }
.application-card {
  display: flex;
  flex-direction: column;
  min-height: 360px;
  padding: 30px;
  border: 1px solid var(--nexus-line);
  border-radius: 16px;
  background: var(--nexus-surface);
  box-shadow: var(--nexus-card-shadow);
}
.card-head { display: flex; align-items: center; justify-content: space-between; }
.card-emblem { display: grid; width: 56px; height: 56px; place-items: center; border-radius: 14px; background: #f1f5ee; }
.card-emblem img { width: 30px; height: 30px; }
.application-card h2 { overflow-wrap: anywhere; margin: 24px 0 8px; color: var(--nexus-ink); font-size: 23px; line-height: 1.35; }
.card-description { min-height: 20px; margin: 0 0 18px; color: var(--nexus-muted); font-size: 13px; line-height: 1.5; }
.status-pill { display: inline-flex; align-items: center; align-self: flex-start; gap: 8px; padding: 5px 12px; border-radius: 999px; font-size: 13px; font-weight: 650; }
.status-pill.is-active { background: var(--nexus-success-surface); color: var(--nexus-success-ink); }
.status-pill.is-disabled { background: var(--nexus-warning-surface); color: var(--nexus-warning-ink); }
.status-dot { width: 8px; height: 8px; border-radius: 50%; background: currentColor; }
.card-date { margin: auto 0 18px; padding-top: 20px; color: #7b8b82; font-size: 12px; }
.card-actions { display: flex; align-items: center; justify-content: space-between; padding-top: 14px; border-top: 1px solid #e8ece5; }
.card-actions :deep(.el-button) { color: var(--nexus-muted); }
.card-actions :deep(.el-button .el-icon) { margin-left: 6px; }
.details-link { display: inline-flex; align-items: center; gap: 6px; color: var(--nexus-teal); font-size: 13px; font-weight: 700; text-decoration: none; }
.details-link:hover { text-decoration: underline; }
.empty-state { display: grid; justify-items: center; min-height: 520px; padding: 55px 24px 65px; border: 1px solid var(--nexus-line); border-radius: 16px; background: var(--nexus-surface); box-shadow: var(--nexus-card-shadow); text-align: center; }
.empty-illustration { width: 180px; height: 200px; object-fit: contain; }
.empty-state h2 { margin: 8px 0 0; font-size: clamp(21px, 2vw, 28px); }
.empty-state p { margin: 8px 0 18px; color: var(--nexus-muted); }
.empty-state :deep(.el-button--primary) { min-height: 44px; padding-inline: 22px; border-radius: 10px; }
.notice { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 24px; padding: 18px 20px; border-radius: 12px; }
.error-notice { border: 1px solid #efd8d2; background: #fff8f5; color: #8a3c33; }
.notice p { margin: 4px 0 0; font-size: 13px; }
.dialog-hint { margin: -4px 0 22px; color: #74867e; font-size: 13px; }
.skeleton-card { justify-content: center; }
@media (max-width: 650px) { .page-heading { align-items: stretch; flex-direction: column; } .page-heading :deep(.el-button) { align-self: flex-start; } }
</style>
