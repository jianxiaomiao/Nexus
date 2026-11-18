<template>
	<view class="nexus-page management-page application-detail-page">
		<nexus-state
			v-if="error"
			kind="error"
			title="无法打开 Application"
			:description="error"
			><template #action
				><nexus-button @press="load">重试</nexus-button></template
			></nexus-state
		>
		<nexus-state
			v-else-if="!app"
			kind="loading"
			title="正在加载 Application"
		/>
		<view v-else>
			<view class="management-row"
				><text class="management-title">{{ app.name }}</text
				><nexus-badge
					:label="statusLabel(app.status)"
					:tone="statusTone(app.status)"
			/></view>
			<view class="management-actions"
				><nexus-button
					variant="secondary"
					compact
					:disabled="busy || loading"
					@press="openAction('rename')"
					>编辑名称</nexus-button
				><nexus-button
					variant="secondary"
					compact
					:disabled="busy || loading || ![0, 1].includes(app.status)"
					@press="openAction('status')"
					>{{
						app.status === 0 ? '禁用应用' : '启用应用'
					}}</nexus-button
				><nexus-button
					variant="danger"
					compact
					:disabled="busy || loading"
					@press="openAction('delete')"
					>删除应用</nexus-button
				></view
			>
			<view v-if="notice" class="management-section"
				><nexus-notice tone="warning" :message="notice"
			/></view>
			<view class="management-tabs"
				><nexus-button
					:variant="tab === 'overview' ? 'primary' : 'text'"
					compact
					@press="tab = 'overview'"
					>概览</nexus-button
				><nexus-button
					:variant="tab === 'keys' ? 'primary' : 'text'"
					compact
					@press="selectKeys"
					>API Keys</nexus-button
				></view
			>
			<view v-if="tab === 'overview'">
				<view class="management-section"
					><nexus-card title="调用配额">
						<nexus-state
							v-if="quotaError"
							kind="error"
							title="配额暂不可用"
							:description="quotaError"
							><template #action
								><nexus-button variant="text" @press="loadQuota"
									>重试配额</nexus-button
								></template
							></nexus-state
						>
						<application-quota
							v-else-if="currentQuota"
							:quota="currentQuota"
						/>
						<nexus-state
							v-else
							kind="loading"
							title="正在查询配额"
						/>
						<template #footer
							><nexus-button
								variant="text"
								compact
								:loading="quotaLoading"
								@press="loadQuota"
								>刷新配额</nexus-button
							></template
						>
					</nexus-card></view
				>
				<view class="management-section"
					><nexus-card title="应用信息"
						><text class="management-label">名称</text
						><text class="management-value">{{ app.name }}</text
						><text class="management-label">状态</text
						><text class="management-value">{{
							statusLabel(app.status)
						}}</text
						><text class="management-label"
							>创建时间（北京时间）</text
						><text class="management-value">{{
							time(app.createdAt)
						}}</text
						><text class="management-label"
							>更新时间（北京时间）</text
						><text class="management-value">{{
							time(app.updatedAt)
						}}</text></nexus-card
					></view
				>
			</view>
			<view v-else>
				<view class="management-actions"
					><nexus-button
						:disabled="
							app.status !== 0 ||
							uncertain ||
							loading ||
							busy ||
							loading ||
							keysLoading
						"
						@press="openKeyCreate"
						>创建 API Key</nexus-button
					><nexus-button
						variant="text"
						:loading="keysLoading"
						@press="load"
						>刷新 Key 列表</nexus-button
					></view
				>
				<view v-if="app.status !== 0" class="management-section"
					><nexus-notice
						tone="warning"
						message="应用已禁用，其 Key 无法调用开放 API，也不能创建新 Key；仍可查看历史。"
				/></view>
				<nexus-state
					v-if="keysError"
					kind="error"
					title="暂时无法加载 API Key"
					:description="keysError"
					><template #action
						><nexus-button @press="load"
							>重试</nexus-button
						></template
					></nexus-state
				>
				<nexus-state
					v-else-if="keysLoading && !keyItems.length"
					kind="loading"
					title="正在加载 API Key"
				/>
				<nexus-state
					v-else-if="!keysLoading && !keyItems.length"
					title="还没有 API Key"
					description="创建后，完整调用凭据只显示一次。"
				/>
				<view
					v-for="item in keyItems"
					:key="item.id"
					class="management-section"
					><nexus-card>
						<view class="management-row"
							><text class="management-name">{{ item.name }}</text
							><nexus-badge
								:label="statusLabel(item.status)"
								:tone="statusTone(item.status)"
						/></view>
						<text class="management-label">遮蔽预览</text
						><text class="management-value nexus-mono">{{
							item.keyPreview
						}}</text
						><text class="management-subtitle"
							>创建于 {{ time(item.createdAt, true) }}</text
						>
						<template #footer
							><nexus-button
								variant="text"
								compact
								@press="openKey(item.id)"
								>查看 Key</nexus-button
							></template
						>
					</nexus-card></view
				>
				<view v-if="keyTotal > 10" class="management-pagination"
					><nexus-button
						variant="text"
						compact
						:disabled="keysLoading || keyPage <= 1"
						@press="changeKeyPage(-1)"
						>上一页</nexus-button
					><text>{{ keyPage }} / {{ Math.ceil(keyTotal / 10) }}</text
					><nexus-button
						variant="text"
						compact
						:disabled="keysLoading || keyPage * 10 >= keyTotal"
						@press="changeKeyPage(1)"
						>下一页</nexus-button
					></view
				>
			</view>
		</view>
		<nexus-dialog :open="!!action" :title="actionTitle">
			<nexus-input
				v-if="action === 'rename' || action === 'create-key'"
				v-model="formName"
				input-id="application-action-name"
				:label="action === 'rename' ? '应用名称' : 'Key 名称'"
				:maxlength="64"
				:error="fieldError"
				:disabled="busy || loading"
				@blur="validateName"
				@input="fieldError = ''"
				@confirm="submitAction"
			/>
			<text v-else class="management-help">{{
				action === 'delete'
					? '删除后该应用及其 API Key 均不可继续使用，无法撤销。'
					: app?.status === 0
						? '禁用后，该应用的所有 API Key 将无法调用开放 API。'
						: '启用后，未禁用的 API Key 可以恢复调用。'
			}}</text>
			<nexus-input
				v-if="action === 'delete'"
				v-model="deleteName"
				input-id="delete-application-name"
				label="输入完整应用名称确认删除"
				:hint="app?.name"
				:disabled="busy || loading"
			/>
			<text v-if="action === 'create-key'" class="management-help"
				>完整凭据只在创建成功时显示一次，请及时保存。</text
			>
			<text v-if="actionError" class="management-error">{{
				actionError
			}}</text>
			<template #actions
				><nexus-button
					variant="text"
					:disabled="busy || loading"
					@press="action = ''"
					>取消</nexus-button
				><nexus-button
					:variant="action === 'delete' ? 'danger' : 'primary'"
					:loading="busy"
					:disabled="
						uncertain ||
						loading ||
						(action === 'delete' && deleteName !== app?.name)
					"
					@press="submitAction"
					>{{
						action === 'delete'
							? '确认删除'
							: action === 'create-key'
								? '创建 Key'
								: '确认保存'
					}}</nexus-button
				></template
			>
		</nexus-dialog>
		<key-reveal :value="secret" @close="secret = ''" />
	</view>
</template>
<script>
import * as api from '../../api/management.js'
import { beijingTime } from '../../utils/time.js'
export default {
	data() {
		return {
			id: 0,
			app: null,
			error: '',
			notice: '',
			visible: false,
			viewVersion: 0,
			loading: false,
			tab: 'overview',
			currentQuota: null,
			quotaLoading: false,
			quotaError: '',
			keyItems: [],
			keyTotal: 0,
			keyPage: 1,
			keysLoading: false,
			keysError: '',
			keyVersion: 0,
			action: '',
			formName: '',
			deleteName: '',
			fieldError: '',
			actionError: '',
			busy: false,
			uncertain: false,
			secret: '',
		}
	},
	computed: {
		actionTitle() {
			return (
				{
					rename: '编辑 Application',
					status:
						this.app?.status === 0
							? '禁用 Application'
							: '启用 Application',
					delete: '删除 Application',
					'create-key': '创建 API Key',
				}[this.action] || '确认操作'
			)
		},
	},
	onLoad(query) {
		try {
			this.id = api.positiveId(query.id)
		} catch (e) {
			this.error = api.message(e)
		}
	},
	onShow() {
		this.visible = true
		if (this.id && !this.busy) this.load()
	},
	onHide() {
		this.leave()
	},
	onUnload() {
		this.leave()
	},
	onBackPress() {
		if (this.secret) {
			uni.showToast({ title: '请先保存凭据并点击完成', icon: 'none' })
			return true
		}
		if (this.busy) return true
		if (this.action) {
			this.action = ''
			return true
		}
		return false
	},
	methods: {
		time: beijingTime,
		statusLabel: api.statusLabel,
		statusTone: api.statusTone,
		leave() {
			this.visible = false
			this.viewVersion++
			this.keyVersion++
			this.loading = false
			this.keysLoading = false
			if (this.secret) {
				this.secret = ''
				this.notice = '完整 Key 已在切换页面时隐藏，无法再次查看。'
			}
		},
		async load() {
			const version = ++this.viewVersion
			this.loading = true
			this.error = ''
			try {
				await api.requireLogin()
				const app = await api.application(this.id)
				if (!this.visible || version !== this.viewVersion) return
				this.app = app
				this.currentQuota = app.quota
				this.uncertain = false
				if (!app.quota) this.loadQuota()
				if (this.tab === 'keys') this.loadKeys()
			} catch (error) {
				if (this.visible && version === this.viewVersion) {
					this.app = null
					this.currentQuota = null
					this.keyItems = []
					this.error = await api.showError(error)
				}
			} finally {
				if (version === this.viewVersion) this.loading = false
			}
		},
		async loadQuota() {
			if (this.quotaLoading) return
			const version = this.viewVersion
			this.quotaLoading = true
			this.quotaError = ''
			try {
				const result = await api.quota(this.id)
				if (this.visible && version === this.viewVersion)
					this.currentQuota = result
			} catch (e) {
				if (this.visible && version === this.viewVersion)
					this.quotaError = await api.showError(e)
			} finally {
				this.quotaLoading = false
			}
		},
		selectKeys() {
			this.tab = 'keys'
			this.loadKeys()
		},
		async loadKeys() {
			if (this.keysLoading) return
			const version = ++this.keyVersion
			this.keysLoading = true
			this.keysError = ''
			try {
				const data = await api.keys(this.id, this.keyPage)
				if (!this.visible || version !== this.keyVersion) return
				if (this.keyPage > 1 && !data.records.length) {
					this.keyPage = Math.max(1, Math.ceil(data.total / 10))
					this.keysLoading = false
					return this.loadKeys()
				}
				this.keyItems = data.records
				this.keyTotal = data.total
			} catch (e) {
				if (this.visible && version === this.keyVersion)
					this.keysError = await api.showError(e)
			} finally {
				if (version === this.keyVersion) this.keysLoading = false
			}
		},
		changeKeyPage(delta) {
			if (this.keysLoading) return
			this.keyPage += delta
			this.loadKeys()
		},
		openKey(id) {
			uni.navigateTo({
				url:
					'/pages/key-detail/key-detail?applicationId=' +
					this.id +
					'&id=' +
					id,
			})
		},
		openAction(kind) {
			if (this.busy || this.loading || this.secret) return
			this.action = kind
			this.formName = this.app.name
			this.deleteName = ''
			this.actionError = ''
			this.fieldError = ''
		},
		openKeyCreate() {
			if (
				this.app.status !== 0 ||
				this.busy ||
				this.loading ||
				this.keysLoading ||
				this.uncertain
			)
				return
			this.openAction('create-key')
			this.formName = ''
		},
		validateName() {
			this.fieldError = api.nameError(this.formName)
			return !this.fieldError
		},
		async submitAction() {
			if (this.busy || this.loading || this.uncertain || !this.app) return
			const kind = this.action,
				version = this.viewVersion
			if (['rename', 'create-key'].includes(kind) && !this.validateName())
				return
			if (kind === 'delete' && this.deleteName !== this.app.name) return
			if (!['rename', 'status', 'delete', 'create-key'].includes(kind))
				return
			this.busy = true
			this.actionError = ''
			try {
				if (kind === 'delete') {
					await api.deleteApplication(this.id)
					this.action = ''
					this.busy = false
					if (this.visible) uni.navigateBack()
					return
				}
				if (kind === 'create-key') {
					const created = await api.createKey(this.id, this.formName)
					if (!this.visible || version !== this.viewVersion) {
						this.notice =
							'创建结果请在列表核对，完整 Key 未保留；必要时轮换或删除。'
						this.uncertain = true
						return
					}
					this.secret = created.apiKey
					this.keyPage = 1
					this.action = ''
					await this.loadKeys()
				} else {
					const updated = await api.updateApplication(
						this.id,
						kind === 'rename'
							? { name: this.formName.trim() }
							: { status: this.app.status === 0 ? 1 : 0 },
					)
					if (!this.visible || version !== this.viewVersion) return
					this.app = { ...this.app, ...updated }
					this.action = ''
					uni.showToast({ title: '已保存', icon: 'success' })
				}
			} catch (error) {
				this.uncertain = error.code === 'MUTATION_RESULT_UNKNOWN'
				this.actionError = await api.showError(error)
				if (this.uncertain) this.notice = this.actionError
			} finally {
				this.busy = false
				if (this.visible && version !== this.viewVersion) {
					this.action = ''
					this.load()
				}
			}
		},
	},
}
</script>
