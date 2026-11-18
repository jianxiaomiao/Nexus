<template>
	<view class="login-page">
		<view class="login-content">
			<view class="brand-bar">
				<view class="brand-lockup">
					<text class="brand-name">Nexus</text>
					<image
						class="brand-spark"
						src="/static/brand/brand-spark.svg"
						mode="aspectFit"
						alt=""
						aria-hidden="true"
					/>
				</view>
				<text class="brand-caption">开发者控制台</text>
			</view>
			<view class="brand-world">
				<LoginPlanet :active="planetActive" />
				<text class="brand-tagline">一片属于你的开发者小宇宙</text>
			</view>
			<view class="form-panel">
				<nexus-card>
					<text class="form-eyebrow">欢迎回来</text>
					<text class="form-title">登录 Nexus</text>
					<text class="form-description"
						>继续管理你的 Application 和 API Key。</text
					>
					<form @submit="handleSubmit">
						<nexus-input
							v-model="email"
							input-id="login-email"
							label="邮箱"
							placeholder="请输入邮箱"
							inputmode="email"
							:maxlength="254"
							:error="errors.email"
							:disabled="submitting || restoring"
							confirm-type="next"
							@blur="onBlur('email')"
							@input="onInput('email')"
							@confirm="focusPassword = true"
						/>
						<nexus-input
							v-model="password"
							input-id="login-password"
							label="密码"
							placeholder="请输入密码"
							:password="!showPassword"
							:focus="focusPassword"
							:maxlength="64"
							:error="errors.password"
							:disabled="submitting || restoring"
							confirm-type="go"
							@blur="onBlur('password')"
							@input="onInput('password')"
							@confirm="handleSubmit"
						>
							<template #suffix>
								<nexus-button
									variant="text"
									compact
									:label="
										showPassword ? '隐藏密码' : '显示密码'
									"
									:disabled="submitting || restoring"
									@press="showPassword = !showPassword"
								>
									{{ showPassword ? '隐藏' : '显示' }}
								</nexus-button>
							</template>
						</nexus-input>
						<view v-if="notice" class="form-notice">
							<nexus-notice tone="warning" :message="notice" />
						</view>
						<nexus-button
							block
							:loading="submitting || restoring"
							@press="handleSubmit"
						>
							{{
								restoring
									? '恢复登录中'
									: submitting
										? '正在登录'
										: '登录'
							}}
						</nexus-button>
					</form>
					<view class="register-prompt">
						<text>还没有账号？</text>
						<nexus-button
							variant="text"
							compact
							@press="showRegistrationNotice"
							>创建账号</nexus-button
						>
					</view>
				</nexus-card>
			</view>
			<text class="page-footer">把想法连接起来，让创造发生。</text>
		</view>
	</view>
</template>

<script>
import LoginPlanet from '../../components/LoginPlanet.vue'
import { getAuth, authMessage, goToApplications } from '../../auth/runtime.js'
export default {
	components: { LoginPlanet },
	data() {
		return {
			email: '',
			password: '',
			showPassword: false,
			focusPassword: false,
			planetActive: true,
			errors: { email: '', password: '' },
			notice: '',
			submitting: false,
			restoring: false,
			pageVisible: true,
		}
	},
	onShow() {
		this.planetActive = true
		this.pageVisible = true
		this.restoreSession()
	},
	onHide() {
		this.password = ''
		this.planetActive = false
		this.pageVisible = false
	},
	onUnload() {
		this.planetActive = false
		this.pageVisible = false
		this.password = ''
	},
	methods: {
		async restoreSession() {
			if (this.restoring || this.submitting) return
			this.restoring = true
			try {
				const auth = await getAuth()
				const state = await auth.initialize()
				if (state.authenticated && this.pageVisible) goToApplications()
				else if (state.revocationUnconfirmed)
					this.notice = '上次已在本机退出，但服务端撤销尚未确认。'
				else if (state.notice)
					this.notice = authMessage({ code: state.notice })
			} catch (error) {
				if (error.code !== 'UNSUPPORTED_PLATFORM')
					this.notice = authMessage(error)
			} finally {
				this.restoring = false
			}
		},
		validateField(field) {
			if (field === 'email') {
				const email = this.email.trim()
				this.errors.email = !email
					? '请输入邮箱'
					: email.length > 254
						? '邮箱不能超过 254 个字符'
						: !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)
							? '请输入有效的邮箱地址'
							: ''
			} else {
				this.errors.password = !this.password.trim()
					? '请输入密码'
					: this.password.length > 64
						? '密码不能超过 64 个字符'
						: ''
			}
			return !this.errors[field]
		},
		onBlur(field) {
			if (field === 'password') this.focusPassword = false
			this.validateField(field)
		},
		onInput(field) {
			this.errors[field] = ''
			this.notice = ''
		},
		async handleSubmit() {
			if (this.submitting || this.restoring) return
			const emailValid = this.validateField('email')
			const passwordValid = this.validateField('password')
			this.notice = ''
			if (!emailValid || !passwordValid) return

			this.submitting = true
			try {
				const auth = await getAuth()
				await auth.login(this.email, this.password)
				if (this.pageVisible) goToApplications()
			} catch (error) {
				this.notice = authMessage(error)
			} finally {
				this.submitting = false
				this.password = ''
			}
		},
		showRegistrationNotice() {
			if (!this.submitting && !this.restoring)
				uni.navigateTo({ url: '/pages/register/register' })
		},
	},
}
</script>

<style scoped>
.login-page {
	box-sizing: border-box;
	min-height: 100vh;
	padding: var(--nexus-space-4) var(--nexus-space-5) var(--nexus-space-5);
	padding-top: calc(var(--nexus-space-4) + var(--status-bar-height, 0px));
	padding-bottom: calc(var(--nexus-space-5) + env(safe-area-inset-bottom));
	background: linear-gradient(
		155deg,
		var(--nexus-paper) 55%,
		var(--nexus-sage)
	);
	color: var(--nexus-ink);
}
.login-content {
	width: 100%;
	max-width: 460px;
	margin: 0 auto;
}
.brand-bar {
	display: flex;
	align-items: center;
	justify-content: space-between;
}
.brand-lockup {
	display: flex;
	align-items: center;
}
.brand-name {
	font-size: var(--nexus-font-title);
	line-height: 1.2;
	font-weight: 750;
	letter-spacing: -1px;
}
.brand-spark {
	width: 20px;
	height: 20px;
	margin-left: var(--nexus-space-2);
}
.brand-caption {
	color: var(--nexus-muted);
	font-size: var(--nexus-font-small);
}
.brand-world {
	margin-top: var(--nexus-space-3);
	text-align: center;
}
.brand-tagline {
	display: block;
	margin-top: var(--nexus-space-1);
	color: var(--nexus-muted);
	font-size: var(--nexus-font-small);
	letter-spacing: 1px;
}
.form-panel {
	margin-top: var(--nexus-space-4);
}
.form-eyebrow {
	display: block;
	color: var(--nexus-teal);
	font-size: var(--nexus-font-small);
	font-weight: 600;
	letter-spacing: 2px;
}
.form-title {
	display: block;
	margin-top: var(--nexus-space-1);
	font-size: var(--nexus-font-title);
	font-weight: 700;
	line-height: 1.35;
}
.form-description {
	display: block;
	margin-top: var(--nexus-space-2);
	margin-bottom: var(--nexus-space-4);
	color: var(--nexus-muted);
	font-size: var(--nexus-font-label);
	line-height: 1.7;
}
.form-notice {
	margin-bottom: var(--nexus-space-3);
}
.register-prompt {
	display: flex;
	flex-wrap: wrap;
	margin-top: var(--nexus-space-2);
	align-items: center;
	justify-content: center;
	color: var(--nexus-muted);
	font-size: var(--nexus-font-label);
}
.page-footer {
	display: block;
	margin-top: var(--nexus-space-4);
	color: var(--nexus-muted);
	font-size: var(--nexus-font-small);
	line-height: 1.7;
	text-align: center;
}
@media screen and (max-width: 360px) {
	.login-page {
		padding-left: var(--nexus-space-3);
		padding-right: var(--nexus-space-3);
	}
}
</style>
