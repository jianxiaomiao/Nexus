<template>
	<button
		class="nexus-button"
		:class="[
			'nexus-button--' + variant,
			{ 'nexus-button--block': block, 'nexus-button--compact': compact },
		]"
		:disabled="disabled || loading"
		:loading="loading"
		:aria-label="label || undefined"
		:aria-busy="loading"
		:aria-disabled="disabled || loading"
		:tabindex="disabled || loading ? -1 : 0"
		role="button"
		hover-class="nexus-button--pressed"
		@tap="handleTap"
		@keydown="handleKeydown"
		@keyup="handleKeyup"
	>
		<slot />
	</button>
</template>

<script>
export default {
	inheritAttrs: false,
	props: {
		variant: {
			type: String,
			default: 'primary',
			validator: (value) =>
				['primary', 'secondary', 'text', 'danger'].includes(value),
		},
		disabled: Boolean,
		loading: Boolean,
		block: Boolean,
		compact: Boolean,
		label: { type: String, default: '' },
	},
	emits: ['press'],
	methods: {
		handleTap(event) {
			if (!this.disabled && !this.loading) this.$emit('press', event)
		},
		handleKeydown(event) {
			if (event.key !== 'Enter' && event.key !== ' ') return
			event.preventDefault?.()
		},
		handleKeyup(event) {
			if (event.key === 'Enter' || event.key === ' ')
				this.handleTap(event)
		},
	},
}
</script>

<style scoped>
.nexus-button {
	display: inline-flex;
	box-sizing: border-box;
	min-width: var(--nexus-touch-size);
	min-height: var(--nexus-control-height);
	align-items: center;
	justify-content: center;
	margin: 0;
	padding: 8px 18px;
	border: 1px solid transparent;
	border-radius: var(--nexus-radius-control);
	background: var(--nexus-teal);
	color: var(--nexus-on-primary);
	font-size: var(--nexus-font-body);
	font-weight: 600;
	line-height: 1.5;
	white-space: normal;
	overflow-wrap: anywhere;
}
.nexus-button::after {
	border: 0;
}
.nexus-button--block {
	display: flex;
	width: 100%;
}
.nexus-button--compact {
	min-height: var(--nexus-touch-size);
	padding: 6px 12px;
	font-size: var(--nexus-font-label);
}
.nexus-button--secondary {
	border-color: var(--nexus-line);
	background: var(--nexus-surface);
	color: var(--nexus-teal);
}
.nexus-button--text {
	background: transparent;
	color: var(--nexus-teal);
}
.nexus-button--danger {
	background: var(--nexus-danger-ink);
	color: var(--nexus-on-primary);
}
.nexus-button--pressed {
	background: var(--nexus-teal-pressed);
}
.nexus-button--secondary.nexus-button--pressed,
.nexus-button--text.nexus-button--pressed {
	background: var(--nexus-sage);
}
.nexus-button--danger.nexus-button--pressed {
	background: var(--nexus-danger-pressed);
}
.nexus-button[disabled] {
	border-color: transparent;
	background: var(--nexus-disabled-surface);
	color: var(--nexus-disabled-ink);
}
/* #ifdef H5 */
.nexus-button:focus-visible {
	outline: 2px solid var(--nexus-teal);
	outline-offset: 3px;
}
/* #endif */
</style>
