<template>
	<text class="nexus-badge" :class="'nexus-badge--' + tone">{{ label }}</text>
</template>

<script>
export default {
	props: {
		label: { type: String, required: true },
		tone: {
			type: String,
			default: 'neutral',
			validator: (value) =>
				['success', 'warning', 'danger', 'neutral'].includes(value),
		},
	},
}
</script>

<style scoped>
.nexus-badge {
	display: inline-block;
	padding: 2px 8px;
	border-radius: var(--nexus-radius-small);
	background: var(--nexus-neutral-surface);
	color: var(--nexus-neutral-ink);
	font-size: var(--nexus-font-small);
	font-weight: 600;
	line-height: 1.7;
}
.nexus-badge--success {
	color: var(--nexus-success-ink);
	background: var(--nexus-success-surface);
}
.nexus-badge--warning {
	color: var(--nexus-warning-ink);
	background: var(--nexus-warning-surface);
}
.nexus-badge--danger {
	color: var(--nexus-danger-ink);
	background: var(--nexus-danger-surface);
}
</style>
