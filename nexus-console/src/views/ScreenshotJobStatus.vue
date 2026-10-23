<script setup lang="ts">
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import type { ScreenshotJob } from '@/api/openApi'

const props = defineProps<{
  job: ScreenshotJob
  polling: boolean
  pollError: string
  retryable: boolean
}>()

defineEmits<{ retry: [] }>()

const statusLabel = computed(() => ({
  QUEUED: '排队中', RUNNING: '生成中', SUCCEEDED: '已完成', FAILED: '执行失败', EXPIRED: '结果已过期',
})[props.job.status])

const currentStep = computed(() => {
  if (props.job.status === 'QUEUED') return 1
  if (props.job.status === 'RUNNING') return 2
  return 3
})

const terminalError = computed(() => props.job.status === 'FAILED' || props.job.status === 'EXPIRED')

function formatTime(value: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('zh-CN')
}

async function copyJobId() {
  try { await navigator.clipboard.writeText(props.job.jobId); ElMessage.success('Job ID 已复制') }
  catch { ElMessage.error('复制失败，请手动选择 Job ID') }
}
</script>

<template>
  <div class="playground-job-result">
    <div class="playground-job-card" :class="{ 'is-error': terminalError, 'is-success': job.status === 'SUCCEEDED' }">
      <div class="playground-job-heading">
        <div><p class="playground-eyebrow">SCREENSHOT JOB</p><h3>{{ job.status === 'SUCCEEDED' ? '截图任务已完成' : '截图任务' }}</h3><p>{{ polling ? '正在自动查询本次任务。' : '当前任务已停止自动查询。' }}</p></div>
        <el-tag :type="job.status === 'SUCCEEDED' ? 'success' : terminalError ? 'danger' : job.status === 'RUNNING' ? 'primary' : 'warning'" effect="light">{{ statusLabel }}</el-tag>
      </div>
      <div class="playground-job-id"><span>Job ID</span><code>{{ job.jobId }}</code><el-button text type="primary" @click="copyJobId">复制</el-button></div>
    </div>

    <div class="playground-job-steps" aria-label="截图任务进度">
      <div v-for="(label, index) in ['已提交', '排队中', '生成 PNG', '可下载']" :key="label" class="playground-job-step" :class="{ 'is-done': index < currentStep || job.status === 'SUCCEEDED', 'is-current': index === currentStep && job.status !== 'SUCCEEDED' && !terminalError }"><span></span>{{ label }}</div>
    </div>

    <div class="playground-job-details">
      <div><span>尝试次数</span><strong>{{ job.attemptCount }} / 2</strong></div>
      <div><span>创建时间</span><strong>{{ formatTime(job.createdAt) }}</strong></div>
      <div v-if="job.startedAt"><span>开始时间</span><strong>{{ formatTime(job.startedAt) }}</strong></div>
      <div v-if="job.resultExpiresAt"><span>结果过期</span><strong>{{ formatTime(job.resultExpiresAt) }}</strong></div>
      <div v-if="job.failureCode"><span>失败代码</span><strong class="is-code">{{ job.failureCode }}</strong></div>
    </div>

    <el-alert v-if="pollError" class="playground-job-poll-error" type="warning" show-icon :closable="false" :title="pollError">
      <el-button v-if="retryable" type="primary" plain size="small" @click="$emit('retry')">继续查询</el-button>
    </el-alert>
    <p v-else-if="polling" class="playground-job-polling">每 2 秒查询一次；上一次请求完成后才会计时，不会叠加请求。</p>
  </div>
</template>
