<template>
	<view class="nexus-card">
		<view v-if="title || $slots.header" class="nexus-card__header">
			<slot name="header"
				><text class="nexus-card__title">{{ title }}</text></slot
			>
		</view>
		<slot />
		<view v-if="$slots.footer" class="nexus-card__footer"
			><slot name="footer"
		/></view>
	</view>
</template>

<script>
export default { props: { title: { type: String, default: '' } } }
</script>

<style scoped>
.nexus-card {
	box-sizing: border-box;
	min-width: 0;
	padding: var(--nexus-space-4);
	border: 1px solid var(--nexus-line);
	border-radius: var(--nexus-radius-card);
	background: var(--nexus-surface);
	box-shadow: var(--nexus-card-shadow);
}
.nexus-card__header {
	margin-bottom: var(--nexus-space-4);
}
.nexus-card__title {
	color: var(--nexus-ink);
	font-size: 18px;
	font-weight: 600;
	overflow-wrap: anywhere;
}
.nexus-card__footer {
	margin-top: var(--nexus-space-4);
	padding-top: var(--nexus-space-3);
	border-top: 1px solid var(--nexus-line);
}
</style>
