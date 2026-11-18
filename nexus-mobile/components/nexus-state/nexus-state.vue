<template>
	<view
		class="nexus-state"
		:class="'nexus-state--' + kind"
		:role="kind === 'error' ? 'alert' : 'status'"
		:aria-busy="kind === 'loading'"
	>
		<image
			v-if="illustration"
			class="nexus-state__illustration"
			:src="illustration"
			mode="aspectFit"
			alt=""
			aria-hidden="true"
		/>
		<text class="nexus-state__title">{{ title }}</text>
		<text v-if="description" class="nexus-state__description">{{
			description
		}}</text>
		<view v-if="$slots.action" class="nexus-state__action"
			><slot name="action"
		/></view>
	</view>
</template>

<script>
export default {
	props: {
		kind: {
			type: String,
			default: 'empty',
			validator: (value) => ['empty', 'loading', 'error'].includes(value),
		},
		title: { type: String, required: true },
		description: { type: String, default: '' },
		illustration: { type: String, default: '' },
	},
}
</script>

<style scoped>
.nexus-state {
	padding: var(--nexus-space-6) var(--nexus-space-3);
	text-align: center;
}
.nexus-state__illustration {
	display: block;
	width: 140px;
	height: 116px;
	margin: 0 auto var(--nexus-space-3);
}
.nexus-state__title {
	display: block;
	color: var(--nexus-ink);
	font-size: 18px;
	font-weight: 600;
	overflow-wrap: anywhere;
}
.nexus-state__description {
	display: block;
	margin-top: var(--nexus-space-2);
	color: var(--nexus-muted);
	font-size: var(--nexus-font-label);
	line-height: 1.8;
	overflow-wrap: anywhere;
}
.nexus-state--error .nexus-state__title {
	color: var(--nexus-danger-ink);
}
.nexus-state__action {
	margin-top: var(--nexus-space-4);
}
</style>
