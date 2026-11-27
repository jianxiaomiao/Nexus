<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { isCancel } from 'axios'
import { listAdminApplications, updateAdminApplicationStatus, type AdminApplicationListItem } from '@/api/admin'
import ListPagination from '@/components/ListPagination.vue'
import ApplicationQuotaDisplay from '@/components/ApplicationQuotaDisplay.vue'
import { formatBeijingTime } from '@/utils/beijingTime'

const applications = ref<AdminApplicationListItem[]>([])
const current = ref(1)
const size = ref(20)
const total = ref(0)
const ownerUserIdInput = ref('')
const loading = ref(false)
const updatingId = ref<number | null>(null)

async function load() {
  loading.value = true
  try {
    const trimmed = ownerUserIdInput.value.trim()
    const ownerUserId = trimmed && /^[1-9][0-9]*$/.test(trimmed) ? Number(trimmed) : undefined
    const page = await listAdminApplications({
      current: current.value,
      size: size.value,
      ownerUserId,
    })
    applications.value = page.records
    total.value = page.total
  } catch (error) {
    if (!isCancel(error)) {
      ElMessage.error('加载全局应用失败，请稍后重试')
    }
  } finally {
    loading.value = false
  }
}

function search() {
  current.value = 1
  void load()
}

async function toggleStatus(app: AdminApplicationListItem) {
  if (updatingId.value !== null) return
  const nextStatus: 0 | 1 = app.status === 0 ? 1 : 0
  const actionLabel = nextStatus === 1 ? '禁用' : '启用'
  try {
    await ElMessageBox.confirm(
      `确认${actionLabel}应用「${app.name}」（所属：${app.ownerEmail}）？`,
      `${actionLabel}应用`,
      { confirmButtonText: `确认${actionLabel}`, cancelButtonText: '取消', type: nextStatus === 1 ? 'warning' : 'info' },
    )
  } catch {
    return
  }

  updatingId.value = app.id
  try {
    await updateAdminApplicationStatus(app.id, nextStatus)
    ElMessage.success(`应用已${actionLabel}`)
    await load()
  } catch {
    ElMessage.error(`${actionLabel}失败，请稍后重试`)
  } finally {
    updatingId.value = null
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <section class="admin-page" aria-labelledby="admin-apps-title">
    <div class="page-heading">
      <div>
        <p class="eyebrow">管理后台</p>
        <h1 id="admin-apps-title">全局应用</h1>
        <p class="page-description">查看所有用户创建的 Application、配额使用情况并执行启用或禁用。</p>
      </div>
      <div class="search-bar">
        <el-input v-model="ownerUserIdInput" placeholder="按用户 ID 筛选" clearable style="width: 200px" @keyup.enter="search" />
        <el-button type="primary" @click="search">筛选</el-button>
      </div>
    </div>

    <el-table :data="applications" v-loading="loading" border stripe>
      <el-table-column prop="id" label="应用 ID" width="90" />
      <el-table-column prop="name" label="应用名称" min-width="160" />
      <el-table-column prop="ownerEmail" label="所属用户" min-width="200" />
      <el-table-column label="配额" min-width="180">
        <template #default="{ row }">
          <ApplicationQuotaDisplay v-if="row.quota" :quota="row.quota" compact />
          <span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 0 ? 'success' : 'warning'" size="small">
            {{ row.status === 0 ? '启用' : '已禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" min-width="170">
        <template #default="{ row }">{{ formatBeijingTime(row.createdAt, true) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button
            size="small"
            :type="row.status === 0 ? 'danger' : 'primary'"
            :loading="updatingId === row.id"
            @click="toggleStatus(row)"
          >
            {{ row.status === 0 ? '禁用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <ListPagination
      v-if="total > 0"
      :current="current"
      :size="size"
      :total="total"
      @update:current="(v) => { current = v; void load() }"
      @update:size="(v) => { size = v; current = 1; void load() }"
    />
  </section>
</template>

<style scoped>
.admin-page { display: flex; flex-direction: column; gap: 20px; }
.page-heading { display: flex; justify-content: space-between; align-items: flex-end; gap: 16px; flex-wrap: wrap; }
.eyebrow { font-size: 12px; color: var(--el-text-color-secondary); margin: 0 0 4px; }
.page-description { margin: 4px 0 0; color: var(--el-text-color-secondary); }
.search-bar { display: flex; gap: 8px; align-items: center; }
</style>
