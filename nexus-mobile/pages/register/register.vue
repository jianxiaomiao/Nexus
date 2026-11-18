<template>
	<view class="nexus-page management-page register-page">
		<text class="management-title">创建 Nexus 账号</text
		><text class="management-subtitle"
			>同一账号管理你的应用与调用凭据。</text
		>
		<view class="management-section"
			><nexus-card>
				<nexus-input
					v-model="displayName"
					input-id="register-name"
					label="昵称"
					:maxlength="64"
					:error="errors.displayName"
					:disabled="submitting"
					@blur="validate('displayName')"
					@input="errors.displayName = ''"
				/>
				<nexus-input
					v-model="email"
					input-id="register-email"
					label="邮箱"
					inputmode="email"
					:maxlength="254"
					:error="errors.email"
					:disabled="submitting"
					@blur="validate('email')"
					@input="errors.email = ''"
				/>
				<nexus-input
					v-model="password"
					input-id="register-password"
					label="密码"
					password
					:maxlength="64"
					hint="8至64个字符"
					:error="errors.password"
					:disabled="submitting"
					@blur="validate('password')"
					@input="errors.password = ''"
				/>
				<nexus-input
					v-model="confirmation"
					input-id="register-confirmation"
					label="确认密码"
					password
					:maxlength="64"
					:error="errors.confirmation"
					:disabled="submitting"
					@blur="validate('confirmation')"
					@input="errors.confirmation = ''"
					@confirm="submit"
				/>
				<view v-if="error" class="management-section"
					><nexus-notice tone="warning" :message="error"
				/></view>
				<view class="management-actions"
					><nexus-button
						block
						:loading="submitting"
						:disabled="uncertain"
						@press="submit"
						>注册账号</nexus-button
					><nexus-button
						block
						variant="text"
						:disabled="submitting"
						@press="backToLogin"
						>已有账号，返回登录</nexus-button
					></view
				>
			</nexus-card></view
		>
	</view>
</template>
<script>
import { registerAccount, message } from '../../api/management.js'
import { goToLogin } from '../../auth/runtime.js'
export default {
	data() {
		return {
			displayName: '',
			email: '',
			password: '',
			confirmation: '',
			errors: {
				displayName: '',
				email: '',
				password: '',
				confirmation: '',
			},
			error: '',
			submitting: false,
			uncertain: false,
			visible: false,
		}
	},
	onShow() {
		this.visible = true
	},
	onHide() {
		this.clear()
	},
	onUnload() {
		this.clear()
	},
	onBackPress() {
		return this.submitting
	},
	methods: {
		clear() {
			this.visible = false
			this.password = ''
			this.confirmation = ''
		},
		backToLogin() {
			if (!this.submitting) goToLogin()
		},
		validate(field) {
			if (field === 'displayName')
				this.errors[field] = !this.displayName.trim()
					? '请输入昵称'
					: this.displayName.trim().length > 64
						? '昵称最多64个字符'
						: ''
			if (field === 'email')
				this.errors[field] = !this.email.trim()
					? '请输入邮箱'
					: this.email.trim().length > 254 ||
						  !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(this.email.trim())
						? '请输入有效的邮箱地址'
						: ''
			if (field === 'password')
				this.errors[field] = !this.password.trim()
					? '请输入密码'
					: this.password.length < 8 || this.password.length > 64
						? '密码需要8至64个字符'
						: ''
			if (field === 'confirmation')
				this.errors[field] =
					this.confirmation !== this.password || !this.confirmation
						? '两次密码输入不一致'
						: ''
			return !this.errors[field]
		},
		async submit() {
			if (this.submitting || this.uncertain) return
			const valid = Object.keys(this.errors)
				.map((field) => this.validate(field))
				.every(Boolean)
			if (!valid) return
			this.submitting = true
			this.error = ''
			try {
				await registerAccount({
					displayName: this.displayName.trim(),
					email: this.email.trim(),
					password: this.password,
				})
				if (this.visible) {
					goToLogin()
					uni.showToast({ title: '注册成功，请登录', icon: 'none' })
				}
			} catch (e) {
				this.uncertain =
					['NETWORK_UNAVAILABLE', 'REQUEST_UNAVAILABLE'].includes(
						e.code,
					) || e.status >= 500
				this.error = this.uncertain
					? '注册结果尚未确认，请先尝试登录或稍后核对，不要重复提交。'
					: message(e)
			} finally {
				this.submitting = false
				this.password = ''
				this.confirmation = ''
			}
		},
	},
}
</script>
