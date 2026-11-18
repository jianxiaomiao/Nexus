<template>
	<view class="content">
		<image class="logo" src="/static/logo.png"></image>
		<WelcomeCard :title="title" @greet="showWelcome"/>
	</view>
</template>

<script>
	import WelcomeCard from '../../components/WelcomeCard.vue'
	export default {
		components: {
		    WelcomeCard
		  },

		data() {
			return {
				title: 'Nexus mobile'
			}
		},
		onLoad() {

		},
		methods: {
		  showWelcome(receivedTitle) {
		      uni.showToast({
		        title: '欢迎使用 ' + receivedTitle,
		        icon: 'none'
		      })
		    }
		}
	}
</script>

<style>
	.content {
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
	}

	.logo {
		height: 200rpx;
		width: 200rpx;
		margin-top: 200rpx;
		margin-left: auto;
		margin-right: auto;
		margin-bottom: 50rpx;
	}

</style>
