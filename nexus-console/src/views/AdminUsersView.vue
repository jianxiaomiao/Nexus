<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { isCancel } from 'axios'
import { listAdminUsers, updateAdminUserStatus, type AdminUserListItem } from '@/api/admin'
import { useUserStore } from '@/stores/userStore'
import ListPagination from '@/components/ListPagination.vue'
import { formatBeijingTime } from '@/utils/beijingTime'

const userStore = useUserStore()
const users = ref<AdminUserListItem[]>([])
const current = ref(1)
const size = ref(20)
const total = ref(0)
const emailKeyword = ref('')
const loading = ref(false)
const updatingId = ref<number | null>(null)

async function load() {
  loading.value = true
  try {
    const page = await listAdminUsers({
      current: current.value,
      size: size.value,
      email: emailKeyword.value.trim() || undefined,
    })
    users.value = page.records
    total.value = page.total
  } catch (error) {
    if (!isCancel(error)) {
      ElMessage.error('加载用户列表失败，请稍后重试')
    }
  } finally {
    loading.value = false
  }
}

function search() {
  current.value = 1
  void load()
}

function changePage(value: number) {
  current.value = value
  void load()
}

function changeSize(value: number) {
  size.value = value
  current.value = 1
  void load()
}

async function toggleStatus(user: AdminUserListItem) {
  if (updatingId.value !== null) return
  const nextStatus: 0 | 1 = user.status === 0 ? 1 : 0
  const actionLabel = nextStatus === 1 ? '封禁' : '解封'
  try {
    await ElMessageBox.confirm(
      `确认${actionLabel}用户「${user.email}」？${nextStatus === 1 ? '封禁后该用户现有会话将立即失效。' : ''}`,
      `${actionLabel}用户`,
      { confirmButtonText: `确认${actionLabel}`, cancelButtonText: '取消', type: nextStatus === 1 ? 'warning' : 'info' },
    )
  } catch {
    return
  }

  updatingId.value = user.id
  try {
    await updateAdminUserStatus(user.id, nextStatus)
    ElMessage.success(`已${actionLabel}用户`)
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
  <section class="admin-page" aria-labelledby="admin-users-title">
    <div class="page-heading">
      <div>
        <p class="eyebrow">管理后台</p>
        <h1 id="admin-users-title">用户管理</h1>
        <p class="page-description">查看平台注册用户、名下应用数量，并控制账号启用或封禁状态。</p>
      </div>
      <div class="search-bar">
        <el-input v-model="emailKeyword" placeholder="按邮箱关键词搜索" clearable style="width: 240px" @keyup.enter="search" />
        <el-button type="primary" @click="search">搜索</el-button>
      </div>
    </div>

    <el-table :data="users" v-loading="loading" border stripe>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="email" label="邮箱" min-width="200" />
      <el-table-column prop="displayName" label="昵称" min-width="140" />
      <el-table-column label="角色" width="100">
        <template #default="{ row }">
          <el-tag :type="row.role === 'ADMIN' ? 'danger' : 'info'" size="small">{{ row.role }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="applicationCount" label="应用数" width="90" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 0 ? 'success' : 'warning'" size="small">
            {{ row.status === 0 ? '正常' : '已封禁' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="注册时间" min-width="170">
        <template #default="{ row }">{{ formatBeijingTime(row.createdAt, true) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.id !== userStore.profile?.id"
            size="small"
            :type="row.status === 0 ? 'danger' : 'primary'"
            :loading="updatingId === row.id"
            @click="toggleStatus(row as AdminUserListItem)"
          >
            {{ row.status === 0 ? '封禁' : '解封' }}
          </el-button>
          <span v-else class="self-hint">当前账号</span>
        </template>
      </el-table-column>
    </el-table>

    <ListPagination
      v-if="total > 0"
      :current="current"
      :size="size"
      :total="total"
      @update:current="changePage"
      @update:size="changeSize"
    />
  </section>
</template>

<style scoped>
.admin-page { display: flex; flex-direction: column; gap: 20px; }
.page-heading { display: flex; justify-content: space-between; align-items: flex-end; gap: 16px; flex-wrap: wrap; }
.eyebrow { font-size: 12px; color: var(--el-text-color-secondary); margin: 0 0 4px; }
.page-description { margin: 4px 0 0; color: var(--el-text-color-secondary); }
.search-bar { display: flex; gap: 8px; align-items: center; }
.self-hint { font-size: 12px; color: var(--el-text-color-secondary); }
</style>
