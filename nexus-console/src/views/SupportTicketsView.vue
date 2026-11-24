<script setup lang="ts">
import { isAxiosError } from 'axios'
import { computed, onUnmounted, ref, watch } from 'vue'
import { useUserStore } from '@/stores/userStore'
import { fetchSupportTickets, submitSupportTicket, type SupportSubmission, type SupportTicket } from '@/api/supportTickets'

const store = useUserStore()
const title = ref('')
const description = ref('')
const rows = ref<SupportTicket[]>([])
const selected = ref<SupportTicket | null>(null)
const page = ref(1)
const total = ref(0)
const pageSize = 10
const loading = ref(false)
const sending = ref(false)
const listError = ref('')
const submitError = ref('')
const notice = ref('')
const pending = ref<{ key: string; input: SupportSubmission } | null>(null)
const canSubmit = computed(() => !sending.value && Boolean(pending.value || (title.value.trim() && description.value.trim())))
const labels = { PENDING: '待同步', DELIVERED: '已送达', BLOCKED: '同步待处理' } as const
let listRequest: AbortController | undefined
let disposed = false
let submissionVersion = 0

function showDetail(id: string) {
  selected.value = rows.value.find((item) => item.id === id) ?? null
}

async function load() {
  listRequest?.abort()
  const request = new AbortController()
  listRequest = request
  listError.value = ''
  loading.value = true
  try {
    const result = await fetchSupportTickets(page.value, pageSize, request.signal)
    if (request.signal.aborted) return
    rows.value = result.records
    total.value = result.total
    if (selected.value) {
      selected.value = result.records.find((item) => item.id === selected.value?.id) ?? null
    }
  } catch {
    if (!request.signal.aborted) listError.value = '暂时无法加载工单，请刷新重试。'
  } finally {
    if (!request.signal.aborted) loading.value = false
  }
}

async function submit() {
  if (!canSubmit.value) return
  const version = ++submissionVersion
  const attempt = pending.value ?? {
    key: crypto.randomUUID(),
    input: { title: title.value.trim(), description: description.value.trim() },
  }
  pending.value = attempt
  sending.value = true
  submitError.value = ''
  notice.value = ''
  try {
    const ticket = await submitSupportTicket(attempt.input, attempt.key)
    if (disposed || version !== submissionVersion) return
    pending.value = null
    title.value = ''
    description.value = ''
    notice.value = `工单已保存（${ticket.id}）。`
    if (page.value === 1) await load()
    else page.value = 1
  } catch (error) {
    if (disposed || version !== submissionVersion) return
    const status = isAxiosError(error) ? error.response?.status : undefined
    if (status === 400 || status === 409 || status === 404) {
      pending.value = null
      submitError.value = status === 409 ? '提交标识发生冲突，请先刷新核对已有工单。' : '提交资料未通过校验，请检查后重试。'
    } else {
      submitError.value = '暂时无法确认提交结果。重试会使用同一次提交，请保留此页面。'
    }
  } finally {
    if (version === submissionVersion) sending.value = false
  }
}

watch(page, () => void load(), { immediate: true })
watch(() => store.operationVersion, () => {
  submissionVersion++
  listRequest?.abort()
  pending.value = null
  selected.value = null
  rows.value = []
  total.value = 0
  loading.value = false
  sending.value = false
  title.value = ''
  description.value = ''
  notice.value = ''
  submitError.value = ''
  listError.value = ''
  if (page.value === 1) void load()
  else page.value = 1
})
onUnmounted(() => {
  disposed = true
  listRequest?.abort()
})
</script>

<template>
  <section class="support-tickets" aria-labelledby="support-tickets-title">
    <h1 id="support-tickets-title">我的工单</h1>
    <el-card shadow="never">
      <form class="support-form" @submit.prevent="submit">
        <h2>提交工单</h2>
        <label for="support-title">标题</label>
        <el-input id="support-title" v-model="title" maxlength="200" :disabled="Boolean(pending)" />
        <label for="support-description">问题描述</label>
        <el-input id="support-description" v-model="description" type="textarea" :rows="4"
          maxlength="8000" :disabled="Boolean(pending)" />
        <el-alert v-if="submitError" :title="submitError" type="error" :closable="false" />
        <p v-if="notice" role="status">{{ notice }}</p>
        <div>
          <el-button type="primary" native-type="submit" :loading="sending" :disabled="!canSubmit">
            {{ pending && !sending ? '重试同一次提交' : '提交工单' }}
          </el-button>
        </div>
      </form>
    </el-card>
    <el-card shadow="never">
      <div class="support-header">
        <h2>提交记录</h2>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>
      <el-alert v-if="listError" :title="listError" type="error" :closable="false" />
      <el-table v-loading="loading" :data="rows" empty-text="暂无工单">
        <el-table-column prop="title" label="标题" min-width="180">
          <template #default="{ row }"><el-button link type="primary" @click="showDetail(row.id)">{{ row.title }}</el-button></template>
        </el-table-column>
        <el-table-column label="投递状态" width="110">
          <template #default="{ row }">{{ labels[row.deliveryStatus as SupportTicket['deliveryStatus']] }}</template>
        </el-table-column>
        <el-table-column prop="flowOpsTicketNo" label="FlowOps 工单号" min-width="165" />
      </el-table>
      <el-pagination v-if="total > pageSize" v-model:current-page="page" :page-size="pageSize"
        :total="total" layout="prev, pager, next" />
    </el-card>
    <el-dialog :model-value="Boolean(selected)" :title="selected?.title" width="min(620px, 92vw)"
      @close="selected = null">
      <template v-if="selected">
        <p>{{ labels[selected.deliveryStatus] }} · {{ selected.flowOpsTicketNo ?? selected.id }}</p>
        <p class="support-description">{{ selected.description }}</p>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.support-tickets, .support-form { display: grid; gap: 1rem; }
.support-tickets h1, .support-tickets h2 { margin: 0; }
.support-header { display: flex; align-items: center; justify-content: space-between; gap: 1rem; }
.support-description { white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
