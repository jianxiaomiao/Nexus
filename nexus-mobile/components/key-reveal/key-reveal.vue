<template>
	<nexus-dialog :open="!!value" title="保存完整 API Key">
		<nexus-notice
			tone="warning"
			message="完整凭据只显示这一次。关闭或将 App 切到后台后无法再次查看，请现在妥善保存。"
		/>
		<text class="full-key nexus-mono" selectable>{{ value }}</text>
		<text v-if="copyError" class="copy-error">{{ copyError }}</text>
		<checkbox-group @change="saved = $event.detail.value.includes('saved')">
			<label class="save-check"
				><checkbox
					value="saved"
					:checked="saved"
					color="#247b70"
				/><text>我已经保存这枚 API Key</text></label
			>
		</checkbox-group>
		<template #actions>
			<nexus-button variant="secondary" @press="copy">{{
				copied ? '已复制' : '复制凭据'
			}}</nexus-button>
			<nexus-button :disabled="!saved" @press="$emit('close')"
				>完成</nexus-button
			>
		</template>
	</nexus-dialog>
</template>
<script>
export default {
	props: { value: { type: String, default: '' } },
	emits: ['close'],
	data() {
		return { saved: false, copied: false, copyError: '', copyVersion: 0, alive: true }
	},
	watch: {
		value() {
			this.copyVersion++
			this.saved = false
			this.copied = false
			this.copyError = ''
		},
	},
	beforeUnmount() {
		this.alive = false
		this.copyVersion++
	},
	methods: {
		copy() {
			if (!this.value) return
			const version = ++this.copyVersion
			uni.setClipboardData({
				data: this.value,
				showToast: false,
				success: () => {
					if (!this.alive || version !== this.copyVersion || !this.value) return
					this.copied = true
				},
				fail: () => {
					if (!this.alive || version !== this.copyVersion || !this.value) return
					this.copyError = '复制失败，请手动选择并保存。'
				},
			})
		},
	},
}
</script>
<style scoped>
.full-key {
	display: block;
	padding: 24rpx;
	margin: 24rpx 0;
	border: 1px solid var(--nexus-line);
	border-radius: 12px;
	background: var(--nexus-paper);
	font-size: 14px;
	overflow-wrap: anywhere;
	word-break: break-all;
}
.save-check {
	display: flex;
	min-height: 44px;
	align-items: center;
	gap: 12rpx;
	font-size: 14px;
}
.copy-error {
	color: var(--nexus-danger-ink);
	font-size: 14px;
}
</style>
