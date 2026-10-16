<script setup lang="ts">
import { computed } from 'vue'
import type { ApplicationQuota } from '@/api/applicationQuota'
import { formatBeijingTime } from '@/utils/beijingTime'

const props = defineProps<{ quota: ApplicationQuota; compact?: boolean }>()
const percentage = computed(() => props.quota.quotaLimit === 0 ? 0
  : Math.min(100, Math.max(0, Math.round(props.quota.usedCount / props.quota.quotaLimit * 1000) / 10)))
const state = computed(() => props.quota.restCount === 0 ? 'exhausted' : percentage.value >= 90 ? 'warning' : 'normal')
const stateLabel = computed(() => props.quota.quotaLimit === 0 ? '无可用额度'
  : props.quota.restCount === 0 ? '额度已用尽' : percentage.value >= 90 ? '接近上限' : '')
const color = computed(() => state.value === 'exhausted' ? '#ba6258' : state.value === 'warning' ? '#c7973e' : '#247b70')
</script>

<template>
  <div class="quota-display" :class="[{ compact }, state]">
    <div class="quota-metrics">
      <div class="quota-consumption">
        <div class="quota-amount" aria-label="已用次数与配额上限"><strong>{{ quota.usedCount }}</strong><span> / {{ quota.quotaLimit }} 次</span></div>
        <div class="quota-progress">
          <el-progress :percentage="percentage" :show-text="false" :stroke-width="compact ? 7 : 10" :color="color" :aria-label="`配额已使用 ${percentage}%`" />
          <span>{{ compact ? '' : '已使用 ' }}{{ percentage }}%</span>
        </div>
      </div>
      <div class="quota-remaining"><span>剩余{{ compact ? '' : '次数' }}</span><strong>{{ quota.restCount }}</strong><span v-if="compact">次</span></div>
    </div>
    <p v-if="stateLabel" class="quota-state">{{ stateLabel }}</p>
    <div v-if="!compact" class="quota-period">
      <div><span>当前周期</span><p><time>{{ formatBeijingTime(quota.periodStart) }}</time><span class="period-separator">至</span><time>{{ formatBeijingTime(quota.periodEnd) }}</time></p></div>
      <div><span>下次恢复</span><p><time>{{ formatBeijingTime(quota.periodEnd) }}</time>（北京时间）</p></div>
    </div>
    <p v-else class="quota-recovery"><time>{{ formatBeijingTime(quota.periodEnd) }}</time> 恢复 · 北京时间</p>
  </div>
</template>

<style scoped>
.quota-metrics { display: grid; grid-template-columns: minmax(0, 1.5fr) minmax(140px, 1fr); gap: 32px; align-items: center; }
.quota-consumption { min-width: 0; }
.quota-amount { color: var(--nexus-ink); font-variant-numeric: tabular-nums; }
.quota-amount strong { font-size: clamp(36px, 4vw, 52px); letter-spacing: -.04em; }
.quota-amount > span { color: var(--nexus-muted); font-size: 25px; font-weight: 600; }
.quota-progress { display: flex; align-items: center; gap: 14px; margin-top: 12px; }
.quota-progress :deep(.el-progress) { flex: 1; min-width: 0; }
.quota-progress :deep(.el-progress-bar__outer) { background: #e7ece7; }
.quota-progress > span { flex: none; color: var(--nexus-muted); font-size: 13px; }
.quota-remaining { display: flex; flex-direction: column; gap: 6px; padding-left: 32px; border-left: 1px solid var(--nexus-line); color: var(--nexus-muted); font-size: 13px; }
.quota-remaining strong { color: var(--nexus-ink); font-size: 36px; font-variant-numeric: tabular-nums; }
.quota-state { margin: 14px 0 0; color: var(--nexus-warning-ink); font-size: 13px; font-weight: 600; }
.exhausted .quota-state { color: #a44d44; }
.quota-period { display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr); gap: 24px; margin-top: 24px; padding-top: 20px; border-top: 1px solid var(--nexus-line); font-size: 13px; }
.quota-period > div > span { color: var(--nexus-muted); }
.quota-period p { display: flex; flex-wrap: wrap; gap: 6px; margin: 8px 0 0; line-height: 1.7; }
.period-separator { color: var(--nexus-muted); }
.compact .quota-metrics { display: flex; flex-wrap: wrap; align-items: baseline; gap: 6px 12px; }
.compact .quota-consumption { display: contents; }
.compact .quota-amount { flex: 1; white-space: nowrap; }
.compact .quota-amount strong, .compact .quota-amount > span { font-size: 16px; font-weight: 600; letter-spacing: normal; }
.compact .quota-progress { order: 3; width: 100%; margin-top: 4px; }
.compact .quota-remaining { flex-direction: row; flex-wrap: wrap; gap: 4px; padding: 0; border: 0; }
.compact .quota-remaining strong { color: inherit; font-size: inherit; font-weight: 400; }
.compact .quota-state { margin-top: 10px; }
.quota-recovery { margin: 12px 0 0; color: var(--nexus-muted); font-size: 12px; line-height: 1.8; }
@media (max-width: 650px) { .quota-metrics, .quota-period { grid-template-columns: 1fr; gap: 20px; } .quota-remaining { flex-direction: row; align-items: baseline; gap: 12px; padding-left: 0; border-left: 0; } .quota-amount > span { font-size: 20px; } }
</style>
