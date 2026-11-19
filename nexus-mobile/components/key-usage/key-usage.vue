<template>
	<view class="key-usage">
		<view class="management-actions"
			><nexus-button
				v-for="range in ranges"
				:key="range.value"
				compact
				:variant="timeRange === range.value ? 'primary' : 'text'"
				@press="selectRange(range.value)"
				>{{ range.label }}</nexus-button
			></view
		>
		<view v-if="timeRange === 'CUSTOM'" class="management-section">
			<text class="management-help"
				>自定义范围使用北京时间，开始时间包含、结束时间不包含，最多90天。</text
			>
			<view class="date-row"
				><text>开始</text
				><picker
					mode="date"
					:value="startDate"
					@change="customChanged('startDate', $event.detail.value)"
					><view class="date-picker">{{ startDate }}</view></picker
				><picker
					mode="time"
					:value="startTime"
					@change="customChanged('startTime', $event.detail.value)"
					><view class="date-picker">{{ startTime }}</view></picker
				></view
			>
			<view class="date-row"
				><text>结束</text
				><picker
					mode="date"
					:value="endDate"
					@change="customChanged('endDate', $event.detail.value)"
					><view class="date-picker">{{ endDate }}</view></picker
				><picker
					mode="time"
					:value="endTime"
					@change="customChanged('endTime', $event.detail.value)"
					><view class="date-picker">{{ endTime }}</view></picker
				></view
			>
			<nexus-button
				variant="secondary"
				:loading="loading"
				@press="loadSummary"
				>查询时间范围</nexus-button
			>
		</view>
		<view class="management-actions"
			><nexus-button
				variant="text"
				compact
				:loading="loading || eventsLoading"
				@press="refreshAll"
				>刷新统计和记录</nexus-button
			></view
		>
		<nexus-state
			v-if="error"
			kind="error"
			title="统计暂不可用"
			:description="error"
			><template #action
				><nexus-button @press="loadSummary"
					>重试统计</nexus-button
				></template
			></nexus-state
		>
		<nexus-state
			v-else-if="loading"
			kind="loading"
			title="正在查询调用统计"
		/>
		<view v-else-if="summary">
			<view class="management-section"
				><nexus-card title="调用次数"
					><view class="summary-metrics"
						><view
							><text class="management-label">累计调用</text
							><text class="count all-time-count">{{
								summary.allTimeCount
							}}</text></view
						><view
							><text class="management-label">选中时段</text
							><text class="count period-count">{{
								summary.periodCount
							}}</text></view
						></view
					><text class="management-subtitle"
						>{{ time(summary.periodStart) }} 至
						{{
							time(summary.periodEndExclusive)
						}}（结束时刻不包含）</text
					><text class="management-subtitle"
						>统计时区：{{ summary.timeZone }}</text
					></nexus-card
				></view
			>
			<view class="management-section"
				><nexus-card title="每日调用">
					<nexus-state
						v-if="summary.periodCount === 0"
						title="该时段暂无调用记录"
						description="累计次数保留真实历史值。"
					/>
					<scroll-view
						v-else
						scroll-x
						class="daily-chart"
						aria-label="每日调用次数，从零开始，左右滑动查看更多日期"
					>
						<view
							class="daily-track"
							:style="{
								width: summary.dailyCounts.length * 54 + 'px',
							}"
							><view
								v-for="day in summary.dailyCounts"
								:key="day.date"
								class="daily-column"
								:aria-label="day.date + '：' + day.count + '次'"
								><text class="day-count">{{ day.count }}</text
								><view class="daily-space"
									><view
										class="daily-bar"
										:style="{
											height:
												(maxDaily > 0
													? (day.count / maxDaily) *
														120
													: 0) + 'px',
										}" /></view
								><text class="day-date">{{
									day.date.slice(5)
								}}</text></view
							></view
						>
					</scroll-view>
				</nexus-card></view
			>
			<view class="management-section"
				><nexus-card title="接口分布"
					><text
						v-if="!summary.apiCounts.length"
						class="management-help"
						>该时段没有接口调用。</text
					><view
						v-for="row in summary.apiCounts"
						:key="row.apiCode"
						class="api-count-row"
						><view
							><text class="management-value">{{
								row.apiName
							}}</text
							><text class="management-subtitle nexus-mono">{{
								row.apiCode
							}}</text></view
						><text
							>{{ row.count }} 次 ·
							{{
								summary.periodCount > 0
									? Math.round(
											(row.count / summary.periodCount) *
												1000,
										) / 10
									: 0
							}}%</text
						></view
					></nexus-card
				></view
			>
		</view>
		<view class="management-section"
			><nexus-card title="最近调用记录">
				<text class="management-help"
					>此处显示该 Key 的全部调用记录，不随上方时段筛选。</text
				>
				<nexus-state
					v-if="eventsError"
					kind="error"
					title="记录暂不可用"
					:description="eventsError"
					><template #action
						><nexus-button @press="loadEvents"
							>重试记录</nexus-button
						></template
					></nexus-state
				>
				<nexus-state
					v-else-if="eventsLoading"
					kind="loading"
					title="正在查询记录"
				/>
				<nexus-state
					v-else-if="!events.length"
					title="还没有调用记录"
				/>
				<view
					v-else
					v-for="(event, index) in events"
					:key="event.occurredAt + '-' + index"
					class="event-row"
					><view class="management-row"
						><text class="nexus-mono">{{ event.apiCode }}</text
						><nexus-badge
							:tone="
								event.httpStatusCode < 400
									? 'success'
									: 'danger'
							"
							:label="'HTTP ' + event.httpStatusCode" /></view
					><text class="management-subtitle"
						>{{ time(event.occurredAt) }}（北京时间） ·
						{{ event.durationMs }} ms</text
					></view
				>
				<view v-if="eventTotal > 10" class="management-pagination"
					><nexus-button
						variant="text"
						compact
						:disabled="eventsLoading || eventPage <= 1"
						@press="changeEventPage(-1)"
						>上一页</nexus-button
					><text
						>{{ eventPage }} /
						{{ Math.ceil(eventTotal / 10) }}</text
					><nexus-button
						variant="text"
						compact
						:disabled="
							eventsLoading || eventPage * 10 >= eventTotal
						"
						@press="changeEventPage(1)"
						>下一页</nexus-button
					></view
				>
			</nexus-card></view
		>
	</view>
</template>
<script>
import { usage, usageEvents, showError } from '../../api/management.js'
import { beijingTime, customRange } from '../../utils/time.js'
const ranges = [
	{ value: 'TODAY', label: '今天' },
	{ value: 'YESTERDAY', label: '昨天' },
	{ value: 'LAST_7_DAYS', label: '近7天' },
	{ value: 'LAST_30_DAYS', label: '近30天' },
	{ value: 'THIS_MONTH', label: '本月' },
	{ value: 'LAST_MONTH', label: '上月' },
	{ value: 'CUSTOM', label: '自定义' },
]
export default {
	props: {
		applicationId: { type: Number, required: true },
		apiKeyId: { type: Number, required: true },
		active: Boolean,
	},
	data() {
		return {
			ranges,
			timeRange: 'LAST_7_DAYS',
			summary: null,
			loading: false,
			error: '',
			summaryVersion: 0,
			eventVersion: 0,
			events: [],
			eventTotal: 0,
			eventPage: 1,
			eventsLoading: false,
			eventsError: '',
			startDate: new Date(Date.now() + 8 * 3600000).toISOString().slice(0, 10),
			endDate: new Date(Date.now() + 8 * 3600000 + 86400000).toISOString().slice(0, 10),
			startTime: '00:00',
			endTime: '00:00',
		}
	},
	computed: {
		maxDaily() {
			return Math.max(0, ...(this.summary?.dailyCounts || []).map((day) => day.count))
		},
	},
	mounted() {
		if (this.active) this.refreshAll()
	},
	beforeUnmount() {
		this.summaryVersion++
		this.eventVersion++
	},
	watch: {
		active(value) {
			if (value) this.refreshAll()
			else {
				this.summaryVersion++
				this.eventVersion++
				this.summary = null
				this.events = []
				this.loading = false
				this.eventsLoading = false
			}
		},
	},
	methods: {
		customChanged(field, value) {
			this[field] = value
			this.summaryVersion++
			this.loading = false
			this.summary = null
			this.error = ''
		},
		time: beijingTime,
		selectRange(value) {
			this.timeRange = value
			this.loading = false
			this.summaryVersion++
			this.summary = null
			this.error = ''
			if (value !== 'CUSTOM') this.loadSummary()
		},
		refreshAll() {
			this.loadSummary()
			this.loadEvents()
		},
		async loadSummary() {
			const version = ++this.summaryVersion
			this.error = ''
			this.summary = null
			this.loading = false
			const start = this.startDate + 'T' + this.startTime,
				end = this.endDate + 'T' + this.endTime
			if (this.timeRange === 'CUSTOM') {
				this.error = customRange(start, end)
				if (this.error) return
			}
			this.loading = true
			try {
				const data = await usage({
					applicationId: this.applicationId,
					apiKeyId: this.apiKeyId,
					timeRange: this.timeRange,
					...(this.timeRange === 'CUSTOM'
						? {
								customStartTime: start + ':00',
								customEndTime: end + ':00',
							}
						: {}),
				})
				if (this.active && version === this.summaryVersion) {
					if (
						!Array.isArray(data?.dailyCounts) ||
						!Array.isArray(data?.apiCounts) ||
						!Number.isSafeInteger(data.allTimeCount) ||
						!Number.isSafeInteger(data.periodCount)
					)
						throw { code: 'INVALID_RESPONSE' }
					this.summary = data
				}
			} catch (e) {
				if (this.active && version === this.summaryVersion) {
					const message = await showError(e)
					if (this.active && version === this.summaryVersion) this.error = message
				}
			} finally {
				if (version === this.summaryVersion) this.loading = false
			}
		},
		async loadEvents() {
			const version = ++this.eventVersion
			this.eventsLoading = true
			this.eventsError = ''
			try {
				const data = await usageEvents({
					applicationId: this.applicationId,
					apiKeyId: this.apiKeyId,
					current: this.eventPage,
					size: 10,
				})
				if (this.active && version === this.eventVersion) {
					const last = Math.max(1, Math.ceil(data.total / 10))
					if (this.eventPage > last) {
						this.eventPage = last
						this.loadEvents()
						return
					}
					this.events = data.records
					this.eventTotal = data.total
				}
			} catch (e) {
				if (this.active && version === this.eventVersion) {
					const message = await showError(e)
					if (this.active && version === this.eventVersion) this.eventsError = message
				}
			} finally {
				if (version === this.eventVersion) this.eventsLoading = false
			}
		},
		changeEventPage(delta) {
			if (this.eventsLoading) return
			this.eventPage += delta
			this.loadEvents()
		},
	},
}
</script>
<style scoped>
.summary-metrics {
	display: flex;
	flex-wrap: wrap;
	justify-content: space-between;
	gap: 24rpx;
}
.count {
	display: block;
	color: var(--nexus-ink);
	font-size: 32px;
	font-weight: 700;
	font-variant-numeric: tabular-nums;
}
.daily-chart {
	width: 100%;
}
.daily-track {
	display: flex;
	align-items: flex-end;
	padding: 24rpx 0;
}
.daily-column {
	width: 54px;
	flex-shrink: 0;
	text-align: center;
}
.daily-space {
	display: flex;
	align-items: flex-end;
	justify-content: center;
	height: 120px;
	border-bottom: 1px solid var(--nexus-line);
}
.daily-bar {
	width: 18px;
	background: var(--nexus-teal);
	border-radius: 4px 4px 0 0;
}
.day-count,
.day-date {
	display: block;
	color: var(--nexus-muted);
	font-size: 11px;
	margin: 8rpx 0;
}
.api-count-row,
.event-row {
	padding: 24rpx 0;
	border-bottom: 1px solid var(--nexus-line);
	font-size: 14px;
}
.api-count-row {
	display: flex;
	flex-wrap: wrap;
	justify-content: space-between;
	gap: 16rpx;
}
.date-row {
	display: flex;
	flex-wrap: wrap;
	gap: 16rpx;
	align-items: center;
	margin: 16rpx 0;
}
.date-picker {
	display: flex;
	align-items: center;
	min-height: 44px;
	padding: 0 12px;
	background: var(--nexus-surface);
	border: 1px solid var(--nexus-line);
	border-radius: 12px;
	font-size: 14px;
}
</style>
