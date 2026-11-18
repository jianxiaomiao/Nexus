<template>
	<view
		class="nexus-notice"
		:class="'nexus-notice--' + tone"
		:role="tone === 'danger' ? 'alert' : 'status'"
	>
		<text v-if="title" class="nexus-notice__title">{{ title }}</text>
		<text v-if="message" class="nexus-notice__message">{{ message }}</text>
		<slot />
		<text v-if="code" class="nexus-notice__code">错误码：{{ code }}</text>
		<view v-if="$slots.action" class="nexus-notice__action"
			><slot name="action"
		/></view>
	</view>
</template>

<script>
export default {
	props: {
		tone: {
			type: String,
			default: 'info',
			validator: (value) =>
				['info', 'success', 'warning', 'danger'].includes(value),
		},
		title: { type: String, default: '' },
		message: { type: String, default: '' },
		code: { type: String, default: '' },
	},
}
</script>

<style scoped>
.nexus-notice {
	padding: var(--nexus-space-3);
	border-radius: var(--nexus-radius-control);
	background: var(--nexus-sage);
	color: var(--nexus-ink);
	font-size: var(--nexus-font-label);
	line-height: 1.7;
	overflow-wrap: anywhere;
}
.nexus-notice--success {
	color: var(--nexus-success-ink);
	background: var(--nexus-success-surface);
}
.nexus-notice--warning {
	color: var(--nexus-warning-ink);
	background: var(--nexus-warning-surface);
}
.nexus-notice--danger {
	color: var(--nexus-danger-ink);
	background: var(--nexus-danger-surface);
}
.nexus-notice__title {
	display: block;
	font-weight: 600;
}
.nexus-notice__message {
	display: block;
}
.nexus-notice__code {
	display: block;
	margin-top: var(--nexus-space-1);
	font-family: var(--nexus-font-mono);
	font-size: var(--nexus-font-small);
}
.nexus-notice__action {
	margin-top: var(--nexus-space-2);
}
</style>
