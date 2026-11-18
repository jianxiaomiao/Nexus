<template>
	<view class="nexus-page management-page applications-page">
		<view class="management-row"
			><view
				><text class="management-subtitle">Nexus · 开发者控制台</text
				><text class="management-title">Application</text></view
			><nexus-button
				variant="text"
				compact
				:disabled="creating || loggingOut"
				@press="logoutOpen = true"
				>退出登录</nexus-button
			></view
		>
		<view class="management-actions"
			><nexus-button :disabled="uncertain || loading" @press="openCreate"
				>创建 Application</nexus-button
			></view
		>
		<view v-if="warning" class="management-section"
			><nexus-notice tone="warning" :message="warning"
		/></view>
		<view class="management-row toolbar management-section"
			><text class="management-subtitle"
				>共 {{ total }} 个 Application</text
			><nexus-button
				variant="text"
				compact
				:loading="loading"
				@press="load"
				>{{ loading ? '加载中' : '刷新' }}</nexus-button
			></view
		>
		<nexus-state
			v-if="error"
			kind="error"
			title="暂时无法加载"
			:description="error"
			><template #action
				><nexus-button variant="secondary" @press="load"
					>重试</nexus-button
				></template
			></nexus-state
		>
		<nexus-state
			v-else-if="loading && !items.length"
			kind="loading"
			title="正在加载 Application"
		/>
		<nexus-state
			v-else-if="!loading && !items.length"
			title="从第一个 Application 开始"
			description="创建应用，再为它添加调用凭据。"
			illustration="/static/auth/login-planet.png"
			><template #action
				><nexus-button :disabled="uncertain" @press="openCreate"
					>创建首个 Application</nexus-button
				></template
			></nexus-state
		>
		<view v-for="item in items" :key="item.id" class="management-section">
			<nexus-card>
				<view class="management-row"
					><text class="management-name application-name">{{
						item.name
					}}</text
					><nexus-badge
						:label="statusLabel(item.status)"
						:tone="statusTone(item.status)"
				/></view>
				<text class="management-subtitle"
					>创建于 {{ time(item.createdAt, true) }}</text
				>
				<application-quota
					v-if="item.quota"
					:quota="item.quota"
					compact
				/>
				<template #footer
					><nexus-button
						variant="text"
						compact
						@press="openDetail(item.id)"
						>查看详情</nexus-button
					></template
				>
			</nexus-card>
		</view>
		<view v-if="total > size" class="management-pagination"
			><nexus-button
				variant="text"
				compact
				:disabled="loading || current <= 1"
				@press="changePage(-1)"
				>上一页</nexus-button
			><text
				>{{ current }} /
				{{ Math.max(1, Math.ceil(total / size)) }}</text
			><nexus-button
				variant="text"
				compact
				:disabled="loading || current * size >= total"
				@press="changePage(1)"
				>下一页</nexus-button
			></view
		>
		<nexus-dialog :open="createOpen" title="创建 Application">
			<nexus-input
				v-model="newName"
				input-id="create-application-name"
				label="应用名称"
				:maxlength="64"
				:disabled="creating"
				:error="nameProblem"
				@blur="validateName"
				@input="nameProblem = ''"
				@confirm="submitCreate"
			/>
			<text v-if="createError" class="management-error">{{
				createError
			}}</text>
			<template #actions
				><nexus-button
					variant="text"
					:disabled="creating"
					@press="createOpen = false"
					>取消</nexus-button
				><nexus-button
					:loading="creating"
					:disabled="uncertain"
					@press="submitCreate"
					>创建</nexus-button
				></template
			>
		</nexus-dialog>
		<nexus-dialog :open="logoutOpen" title="退出登录"
			><text class="management-help">退出后，下次打开需要重新登录。</text
			><template #actions
				><nexus-button
					variant="text"
					:disabled="loggingOut"
					@press="logoutOpen = false"
					>取消</nexus-button
				><nexus-button :loading="loggingOut" @press="logout"
					>确认退出</nexus-button
				></template
			></nexus-dialog
		>
	</view>
</template>
<script>
import { getAuth, goToLogin } from '../../auth/runtime.js'
import {
	applications,
	createApplication,
	showError,
	message,
	nameError,
	statusLabel,
	statusTone,
} from '../../api/management.js'
import { beijingTime } from '../../utils/time.js'
export default {
	data() {
		return {
			items: [],
			total: 0,
			current: 1,
			size: 10,
			loading: false,
			loggingOut: false,
			error: '',
			warning: '',
			visible: false,
			loadVersion: 0,
			createOpen: false,
			newName: '',
			nameProblem: '',
			creating: false,
			createError: '',
			uncertain: false,
			logoutOpen: false,
		}
	},
	onShow() {
		this.visible = true
		this.load()
	},
	onHide() {
		this.visible = false
		this.loadVersion++
		this.loading = false
	},
	onUnload() {
		this.visible = false
		this.loadVersion++
	},
	onBackPress() {
		if (this.creating || this.loggingOut) return true
		if (this.createOpen) {
			this.createOpen = false
			return true
		}
		if (this.logoutOpen) {
			this.logoutOpen = false
			return true
		}
		return false
	},
	methods: {
		time: beijingTime,
		statusLabel,
		statusTone,
		async load() {
			if (this.loading || this.loggingOut) return
			const version = ++this.loadVersion
			this.loading = true
			this.error = ''
			try {
				const data = await applications(this.current)
				if (!this.visible || version !== this.loadVersion) return
				if (this.current > 1 && !data.records.length) {
					this.current = Math.max(
						1,
						Math.ceil(data.total / this.size),
					)
					this.loading = false
					return this.load()
				}
				this.items = data.records
				this.total = data.total
				this.uncertain = false
				const auth = await getAuth()
				this.warning = auth.state().revocationUnconfirmed
					? '上次退出的服务端撤销未确认。'
					: ''
			} catch (error) {
				if (this.visible && version === this.loadVersion)
					this.error = await showError(error)
			} finally {
				if (version === this.loadVersion) this.loading = false
			}
		},
		changePage(delta) {
			if (this.loading) return
			this.current += delta
			this.load()
		},
		openDetail(id) {
			uni.navigateTo({
				url: '/pages/application-detail/application-detail?id=' + id,
			})
		},
		openCreate() {
			if (this.uncertain) return
			this.newName = ''
			this.nameProblem = ''
			this.createError = ''
			this.createOpen = true
		},
		validateName() {
			this.nameProblem = nameError(this.newName)
			return !this.nameProblem
		},
		async submitCreate() {
			if (this.creating || this.uncertain || !this.validateName()) return
			this.creating = true
			this.createError = ''
			try {
				await createApplication(this.newName)
				if (!this.visible) {
					this.uncertain = true
					return
				}
				this.createOpen = false
				this.current = 1
				uni.showToast({ title: 'Application 已创建', icon: 'success' })
				await this.load()
			} catch (error) {
				this.uncertain = error.code === 'MUTATION_RESULT_UNKNOWN'
				this.createError = await showError(error)
				if (this.uncertain) this.warning = message(error)
			} finally {
				this.creating = false
			}
		},
		async logout() {
			if (this.loggingOut) return
			this.loggingOut = true
			this.items = []
			this.total = 0
			try {
				await (await getAuth()).logout()
				goToLogin()
			} catch (error) {
				this.logoutOpen = false
				this.error = message(error)
			} finally {
				this.loggingOut = false
			}
		},
	},
}
</script>
