<template>
	<view class="welcome-card">
		<text class="welcome-title">{{ title }}</text>

		<view class="welcome-action">
			<nexus-button block @press="handleTap">点我打个招呼</nexus-button>
		</view>
	</view>
</template>

<script>
export default {
	props: {
		title: {
			type: String,
			required: true,
		},
	},

	emits: ['greet'],

	methods: {
		handleTap() {
			this.$emit('greet', this.title)
		},
	},
}
</script>

<style scoped>
.welcome-card {
	display: flex;
	flex-direction: column;
	align-items: center;
}

.welcome-title {
	font-size: 36rpx;
	color: var(--nexus-ink);
}

.welcome-action {
	width: 480rpx;
	max-width: 100%;
	margin-top: 32rpx;
}
</style>
