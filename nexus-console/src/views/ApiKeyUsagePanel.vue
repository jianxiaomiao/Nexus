<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { queryUsage, usageErrorMessage, type UsageSummary, type UsageTimeRange } from '@/api/usage'

const props = defineProps<{ applicationId: number; apiKeyId: number }>()

const rangeOptions: { value: UsageTimeRange; label: string }[] = [
  { value: 'TODAY', label: '今天' },
  { value: 'YESTERDAY', label: '昨天' },
  { value: 'LAST_7_DAYS', label: '近7天' },
  { value: 'LAST_30_DAYS', label: '近30天' },
  { value: 'THIS_MONTH', label: '本月' },
  { value: 'LAST_MONTH', label: '上月' },
  { value: 'CUSTOM', label: '自定义' },
]

const timeRange = ref<UsageTimeRange>('LAST_7_DAYS')
const customStartTime = ref('')
const customEndTime = ref('')
const summary = ref<UsageSummary | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const requestFailed = ref(false)
let loadVersion = 0

const selectedRangeLabel = computed(() => rangeOptions.find((option) => option.value === timeRange.value)?.label ?? '所选时段')
const maxDailyCount = computed(() => Math.max(0, ...(summary.value?.dailyCounts.map((day) => day.count) ?? [])))
const chartMaximum = computed(() => {
  if (maxDailyCount.value === 0) return 1
  const magnitude = 10 ** Math.floor(Math.log10(maxDailyCount.value))
  return Math.ceil(maxDailyCount.value / 4 / magnitude) * magnitude * 4
})
const numberFormatter = new Intl.NumberFormat('zh-CN')

function formatCount(count: number): string {
  return numberFormatter.format(count)
}

function shortDate(date: string): string {
  return date.slice(5).replace('-', '/')
}

function formatPeriod(data: UsageSummary): string {
  const zone = data.timeZone || 'Asia/Shanghai'
  const start = new Date(data.periodStart)
  const end = new Date(data.periodEndExclusive)
  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) return ''
  if (timeRange.value === 'CUSTOM') {
    const format = new Intl.DateTimeFormat('zh-CN', { timeZone: zone, dateStyle: 'short', timeStyle: 'short' })
    return `${format.format(start)} 至 ${format.format(end)}（结束时刻不计入）`
  }
  const format = new Intl.DateTimeFormat('zh-CN', { timeZone: zone, dateStyle: 'short' })
  return `${format.format(start)} 至 ${format.format(new Date(end.getTime() - 1))}`
}

function percentage(count: number): string {
  if (!summary.value?.periodCount) return '0%'
  return `${((count / summary.value.periodCount) * 100).toFixed(1).replace(/\.0$/, '')}%`
}

function barHeight(count: number): string {
  return `${count === 0 ? 0 : Math.max(2, (count / chartMaximum.value) * 100)}%`
}

function validateCustomRange(): string {
  if (!customStartTime.value || !customEndTime.value) return '请选择开始和结束时间'
  // datetime-local 是无时区的北京时间墙上时间；用 UTC 解析只为比较两个本地时间值。
  const start = Date.parse(`${customStartTime.value}Z`)
  const end = Date.parse(`${customEndTime.value}Z`)
  if (!Number.isFinite(start) || !Number.isFinite(end)) return '时间格式无效，请重新选择'
  if (start >= end) return '结束时间必须晚于开始时间'
  if (end - start > 90 * 24 * 60 * 60 * 1000) return '自定义时间范围不能超过 90 天'
  return ''
}

async function loadUsage() {
  const version = ++loadVersion
  loading.value = false
  errorMessage.value = ''
  requestFailed.value = false
  summary.value = null
  if (timeRange.value === 'CUSTOM') {
    const validationError = validateCustomRange()
    if (validationError) {
      errorMessage.value = validationError
      return
    }
  }

  loading.value = true
  try {
    const data = await queryUsage({
      applicationId: props.applicationId,
      apiKeyId: props.apiKeyId,
      timeRange: timeRange.value,
      ...(timeRange.value === 'CUSTOM' && {
        customStartTime: `${customStartTime.value}:00`,
        customEndTime: `${customEndTime.value}:00`,
      }),
    })
    if (version === loadVersion) summary.value = data
  } catch (error) {
    if (version === loadVersion) {
      errorMessage.value = usageErrorMessage(error)
      requestFailed.value = true
    }
  } finally {
    if (version === loadVersion) loading.value = false
  }
}

function changeRange() {
  if (timeRange.value === 'CUSTOM') {
    ++loadVersion
    loading.value = false
    summary.value = null
    errorMessage.value = ''
    requestFailed.value = false
  } else {
    void loadUsage()
  }
}

onMounted(() => { void loadUsage() })
onUnmounted(() => { ++loadVersion })
</script>

<template>
  <section class="usage-panel" aria-labelledby="usage-title">
    <div class="usage-head">
      <div>
        <h2 id="usage-title">调用统计</h2>
        <p>查看这枚 API Key 的接口调用情况。</p>
      </div>
      <div class="usage-filters">
        <label class="range-label" for="usage-range">时间范围</label>
        <el-select id="usage-range" v-model="timeRange" class="range-select" aria-label="时间范围" @change="changeRange">
          <el-option v-for="option in rangeOptions" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
        <span class="zone-note">北京时间 UTC+8</span>
      </div>
    </div>

    <form v-if="timeRange === 'CUSTOM'" class="custom-range" @submit.prevent="loadUsage">
      <label>开始时间（北京时间）<input v-model="customStartTime" type="datetime-local" required /></label>
      <label>结束时间（北京时间，不含）<input v-model="customEndTime" type="datetime-local" required /></label>
      <el-button native-type="submit" type="primary" :loading="loading">查询</el-button>
    </form>

    <div v-if="loading" class="usage-loading" aria-busy="true"><el-skeleton :rows="7" animated /></div>
    <div v-else-if="errorMessage" class="usage-notice is-error" role="alert">
      <p>{{ errorMessage }}</p>
      <el-button v-if="requestFailed" @click="loadUsage">重试</el-button>
    </div>
    <template v-else-if="summary">
      <p class="period-note">统计区间：{{ formatPeriod(summary) }}</p>
      <div class="usage-metrics">
        <div class="metric-card"><span>累计调用</span><strong>{{ formatCount(summary.allTimeCount) }}</strong><span class="metric-unit">次</span></div>
        <div class="metric-card"><span>{{ selectedRangeLabel }}调用</span><strong>{{ formatCount(summary.periodCount) }}</strong><span class="metric-unit">次</span></div>
      </div>

      <section class="usage-card" aria-labelledby="daily-title">
        <h3 id="daily-title">每日调用次数</h3>
        <div v-if="summary.periodCount === 0" class="usage-empty">该时段暂无调用记录</div>
        <div v-else class="chart-scroll">
          <div class="daily-chart" :style="{ minWidth: `${Math.max(350, summary.dailyCounts.length * 36 + 42)}px` }">
            <div class="chart-axis" aria-hidden="true"><span>{{ formatCount(chartMaximum) }}</span><span>{{ formatCount(chartMaximum / 2) }}</span><span>0</span></div>
            <div class="chart-plot" role="list" aria-label="每日调用次数">
              <div v-for="day in summary.dailyCounts" :key="day.date" class="chart-day" role="listitem" :aria-label="`${day.date}，${day.count} 次调用`" :title="`${day.date} · ${formatCount(day.count)} 次`">
                <div class="bar-track"><div class="day-bar" :style="{ height: barHeight(day.count) }" /></div>
                <span class="day-label">{{ shortDate(day.date) }}</span>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section class="usage-card" aria-labelledby="api-title">
        <div class="api-card-head"><h3 id="api-title">按接口查看</h3><span>合计 {{ formatCount(summary.periodCount) }} 次</span></div>
        <div v-if="summary.apiCounts.length === 0" class="usage-empty">该时段暂无接口调用</div>
        <div v-else class="api-list" role="list">
          <div v-for="item in summary.apiCounts" :key="item.apiCode" class="api-row" role="listitem">
            <div class="api-name"><strong>{{ item.apiName }}</strong><code>{{ item.apiCode }}</code></div>
            <div class="api-bar" aria-hidden="true"><span :style="{ width: percentage(item.count) }" /></div>
            <div class="api-count"><strong>{{ formatCount(item.count) }} 次</strong><span>{{ percentage(item.count) }}</span></div>
          </div>
        </div>
      </section>
    </template>
    <p v-else class="usage-notice">选择开始和结束时间后查询。</p>
  </section>
</template>

<style scoped>
.usage-panel { padding: clamp(20px, 3vw, 32px); border: 1px solid var(--nexus-line); border-radius: 16px; background: var(--nexus-surface); box-shadow: var(--nexus-card-shadow); }
.usage-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 24px; }
.usage-head h2 { margin: 0 0 8px; font-size: 22px; }
.usage-head p { margin: 0; color: var(--nexus-muted); font-size: 14px; }
.usage-filters { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.range-label { color: var(--nexus-muted); font-size: 13px; }
.range-select { width: 145px; }
.zone-note, .period-note { color: var(--nexus-muted); font-size: 12px; }
.period-note { margin: 18px 0 0; }
.custom-range { display: flex; flex-wrap: wrap; align-items: end; gap: 12px; margin-top: 20px; }
.custom-range label { display: grid; gap: 6px; color: var(--nexus-muted); font-size: 13px; }
.custom-range input { min-height: 40px; padding: 8px 10px; border: 1px solid var(--nexus-line); border-radius: 8px; background: var(--nexus-surface); color: var(--nexus-ink); font: inherit; }
.custom-range input:focus-visible { outline: 2px solid var(--nexus-teal); outline-offset: 2px; }
.usage-loading, .usage-notice { margin-top: 24px; padding: 24px; border: 1px solid var(--nexus-line); border-radius: 12px; }
.usage-notice { color: var(--nexus-muted); }
.usage-notice p { margin: 0 0 14px; }
.usage-notice.is-error { border-color: #e4aaa2; color: #8a3c33; }
.usage-metrics { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; margin-top: 22px; }
.metric-card { min-height: 112px; padding: 20px 24px; border: 1px solid var(--nexus-line); border-radius: 12px; }
.metric-card > span:first-child { display: block; margin-bottom: 10px; color: var(--nexus-muted); font-size: 13px; }
.metric-card strong { font-size: clamp(28px, 3vw, 36px); font-variant-numeric: tabular-nums; }
.metric-unit { margin-left: 6px; font-size: 13px; }
.usage-card { margin-top: 16px; padding: 20px 22px; border: 1px solid var(--nexus-line); border-radius: 12px; }
.usage-card h3 { margin: 0; font-size: 16px; }
.usage-empty { padding: 44px 16px; color: var(--nexus-muted); text-align: center; font-size: 14px; }
.chart-scroll { overflow-x: auto; padding-top: 18px; }
.daily-chart { display: flex; width: 100%; height: 230px; }
.chart-axis { display: flex; width: 42px; flex: none; flex-direction: column; justify-content: space-between; padding: 0 8px 25px 0; color: var(--nexus-muted); text-align: right; font-size: 11px; }
.chart-plot { display: flex; flex: 1; align-items: stretch; justify-content: space-around; gap: 5px; border-bottom: 1px solid var(--nexus-line); background: repeating-linear-gradient(to bottom, var(--nexus-line) 0 1px, transparent 1px 50%); }
.chart-day { display: flex; min-width: 28px; flex: 1; flex-direction: column; align-items: center; }
.bar-track { display: flex; width: 100%; max-width: 40px; flex: 1; flex-direction: column; justify-content: flex-end; }
.day-bar { min-height: 0; border-radius: 4px 4px 0 0; background: var(--nexus-teal); }
.chart-day:hover .day-bar { background: #1b625a; }
.day-label { height: 25px; padding-top: 6px; color: var(--nexus-muted); white-space: nowrap; font-size: 11px; }
.api-card-head { display: flex; justify-content: space-between; gap: 12px; align-items: center; }
.api-card-head span { color: var(--nexus-muted); font-size: 12px; }
.api-list { margin-top: 14px; border-top: 1px solid var(--nexus-line); }
.api-row { display: grid; grid-template-columns: minmax(160px, 1.4fr) minmax(100px, 1fr) 88px; align-items: center; gap: 20px; min-height: 60px; padding: 10px 4px; border-bottom: 1px solid var(--nexus-line); }
.api-row:last-child { border-bottom: 0; }
.api-name { display: grid; gap: 4px; min-width: 0; }
.api-name strong { font-size: 13px; }
.api-name code { overflow-wrap: anywhere; color: var(--nexus-muted); font-size: 11px; }
.api-bar { height: 8px; overflow: hidden; border-radius: 999px; background: var(--nexus-sage); }
.api-bar span { display: block; height: 100%; border-radius: inherit; background: var(--nexus-teal); }
.api-count { display: grid; gap: 3px; text-align: right; font-variant-numeric: tabular-nums; }
.api-count strong { font-size: 13px; }
.api-count span { color: var(--nexus-muted); font-size: 11px; }
@media (max-width: 650px) {
  .usage-head { flex-direction: column; }
  .usage-filters { width: 100%; }
  .usage-metrics { gap: 10px; }
  .metric-card { min-height: 92px; padding: 14px; }
  .usage-card { padding: 16px; }
  .api-row { grid-template-columns: minmax(0, 1fr) 78px; gap: 8px; }
  .api-bar { grid-column: 1 / -1; grid-row: 2; }
  .api-count { grid-column: 2; grid-row: 1; }
}
</style>
