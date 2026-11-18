<template>
	<view class="quota-display">
		<view class="quota-row"
			><text class="quota-label">当前30天配额</text
			><nexus-badge v-if="stateLabel" :label="stateLabel" :tone="tone"
		/></view>
		<view class="quota-row"
			><text class="quota-amount"
				>{{ quota.usedCount }} / {{ quota.quotaLimit }} 次</text
			><text class="quota-label">剩余 {{ quota.restCount }}</text></view
		>
		<view
			class="quota-track"
			role="progressbar"
			:aria-valuenow="percentage"
			aria-valuemin="0"
			aria-valuemax="100"
			:aria-label="'配额已使用 ' + percentage + '%'"
		>
			<view
				class="quota-fill"
				:class="'quota-fill--' + tone"
				:style="{ width: percentage + '%' }"
			/>
		</view>
		<text class="quota-label"
			>已用 {{ percentage }}% ·
			{{ time(quota.periodEnd) }} 恢复（北京时间）</text
		>
		<view v-if="!compact" class="quota-note"
			><text class="quota-label"
				>周期：{{ time(quota.periodStart) }} 至
				{{ time(quota.periodEnd) }}</text
			><text class="quota-label"
				>所有 API Key
				共用；进入执行后的失败请求也计次。刷新只查询，不重置额度。</text
			></view
		>
	</view>
</template>
<script>
import { beijingTime } from '../../utils/time.js'
export default {
	props: { quota: { type: Object, required: true }, compact: Boolean },
	computed: {
		percentage() {
			return this.quota.quotaLimit > 0
				? Math.min(
						100,
						Math.max(
							0,
							Math.round(
								(this.quota.usedCount / this.quota.quotaLimit) *
									1000,
							) / 10,
						),
					)
				: 0
		},
		tone() {
			return this.quota.restCount <= 0
				? 'danger'
				: this.percentage >= 90
					? 'warning'
					: 'neutral'
		},
		stateLabel() {
			return this.quota.quotaLimit === 0
				? '无可用额度'
				: this.quota.restCount <= 0
					? '额度已用尽'
					: this.percentage >= 90
						? '接近上限'
						: ''
		},
	},
	methods: { time: beijingTime },
}
</script>
<style scoped>
.quota-display {
	padding-top: 16rpx;
}
.quota-row {
	display: flex;
	flex-wrap: wrap;
	gap: 12rpx;
	justify-content: space-between;
	align-items: center;
	margin-bottom: 12rpx;
}
.quota-label {
	display: block;
	font-size: 12px;
	color: var(--nexus-muted);
	line-height: 1.8;
}
.quota-amount {
	font-size: 16px;
	font-weight: 600;
	font-variant-numeric: tabular-nums;
}
.quota-track {
	height: 8px;
	margin: 16rpx 0;
	border-radius: 8px;
	overflow: hidden;
	background: var(--nexus-neutral-surface);
}
.quota-fill {
	height: 100%;
	background: var(--nexus-teal);
}
.quota-fill--warning {
	background: var(--nexus-warning-ink);
}
.quota-fill--danger {
	background: var(--nexus-danger-ink);
}
.quota-note {
	border-top: 1px solid var(--nexus-line);
	margin-top: 20rpx;
	padding-top: 20rpx;
}
</style>
