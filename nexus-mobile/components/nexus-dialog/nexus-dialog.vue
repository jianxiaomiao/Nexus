<template>
	<view v-if="open" class="dialog-mask" @touchmove.stop.prevent>
		<view
			class="dialog-panel"
			role="dialog"
			aria-modal="true"
			:aria-label="title"
		>
			<text class="dialog-title">{{ title }}</text>
			<scroll-view scroll-y class="dialog-body" @touchmove.stop
				><slot
			/></scroll-view>
			<view class="dialog-actions"><slot name="actions" /></view>
		</view>
	</view>
</template>
<script>
export default {
	props: { open: Boolean, title: { type: String, required: true } },
}
</script>
<style scoped>
.dialog-mask {
	position: fixed;
	z-index: 1000;
	top: 0;
	right: 0;
	bottom: 0;
	left: 0;
	display: flex;
	align-items: center;
	justify-content: center;
	padding: 24rpx;
	background: rgba(20, 40, 35, 0.45);
}
.dialog-panel {
	box-sizing: border-box;
	width: 100%;
	max-width: 480px;
	max-height: 85vh;
	padding: 32rpx;
	border: 1px solid var(--nexus-line);
	border-radius: var(--nexus-radius-card);
	background: var(--nexus-surface);
	box-shadow: var(--nexus-card-shadow);
}
.dialog-title {
	display: block;
	margin-bottom: 24rpx;
	color: var(--nexus-ink);
	font-size: 20px;
	font-weight: 700;
}
.dialog-body {
	max-height: 55vh;
}
.dialog-actions {
	display: flex;
	flex-wrap: wrap;
	justify-content: flex-end;
	gap: 16rpx;
	margin-top: 24rpx;
}
</style>
