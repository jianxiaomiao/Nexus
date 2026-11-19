<template>
	<view class="nexus-page management-page key-detail-page">
		<nexus-state
			v-if="error"
			kind="error"
			title="无法打开 API Key"
			:description="error"
			><template #action
				><nexus-button @press="load">重试</nexus-button></template
			></nexus-state
		>
		<nexus-state
			v-else-if="!record || !app"
			kind="loading"
			title="正在加载 API Key"
		/>
		<view v-else>
			<view class="management-row"
				><text class="management-title">{{ record.name }}</text
				><nexus-badge
					:label="statusLabel(record.status)"
					:tone="statusTone(record.status)"
			/></view>
			<text class="management-subtitle">所属应用：{{ app.name }}</text>
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
					:disabled="
						busy || loading || ![0, 1].includes(record.status)
					"
					@press="openAction('status')"
					>{{
						record.status === 0 ? '禁用 Key' : '启用 Key'
					}}</nexus-button
				><nexus-button
					variant="danger"
					compact
					:disabled="busy || loading"
					@press="openAction('delete')"
					>删除 Key</nexus-button
				></view
			>
			<view v-if="app.status !== 0" class="management-section"
				><nexus-notice
					tone="warning"
					message="所属应用已禁用，此 Key 无法调用；仍可查看资料和历史用量。"
			/></view>
			<view v-if="notice" class="management-section"
				><nexus-notice tone="warning" :message="notice"
			/></view>
			<view class="management-tabs"
				><nexus-button
					compact
					:variant="tab === 'info' ? 'primary' : 'text'"
					@press="tab = 'info'"
					>密钥信息</nexus-button
				><nexus-button
					compact
					:variant="tab === 'usage' ? 'primary' : 'text'"
					@press="tab = 'usage'"
					>调用统计</nexus-button
				></view
			>
			<view v-if="tab === 'info'">
				<view class="management-section"
					><nexus-card title="密钥信息">
						<text class="management-label">名称</text
						><text class="management-value">{{ record.name }}</text
						><text class="management-label">遮蔽预览</text
						><text
							class="management-value nexus-mono key-preview"
							>{{ record.keyPreview }}</text
						><text class="management-label">Public ID</text
						><text class="management-value nexus-mono public-id">{{
							record.publicId
						}}</text
						><text class="management-label"
							>创建时间（北京时间）</text
						><text class="management-value">{{
							time(record.createdAt)
						}}</text
						><text class="management-label"
							>更新时间（北京时间）</text
						><text class="management-value">{{
							time(record.updatedAt)
						}}</text>
						<template #footer
							><nexus-notice
								message="完整 API Key 仅在创建或轮换成功时显示一次，此处无法查看或找回 Secret。"
						/></template> </nexus-card
				></view>
				<view class="management-section"
					><nexus-card title="轮换调用凭据"
						><text class="management-help"
							>轮换会立即让旧的完整 Key
							失效。新凭据仍只显示一次，请先安排调用方更新。</text
						><nexus-button
							variant="secondary"
							:disabled="
								busy || loading || uncertain || app.status !== 0
							"
							@press="openAction('rotate')"
							>轮换 API Key</nexus-button
						><nexus-button
							variant="text"
							:loading="loading"
							@press="load"
							>刷新资料</nexus-button
						></nexus-card
					></view
				>
			</view>
			<key-usage
				v-else
				:application-id="applicationId"
				:api-key-id="id"
				:active="visible"
			/>
		</view>
		<nexus-dialog :open="!!action" :title="actionTitle">
			<nexus-input
				v-if="action === 'rename'"
				v-model="formName"
				input-id="key-action-name"
				label="Key 名称"
				:maxlength="64"
				:error="fieldError"
				:disabled="busy || loading"
				@blur="validateName"
				@input="fieldError = ''"
				@confirm="submitAction"
			/>
			<text v-else class="management-help">{{
				action === 'delete'
					? '删除后此 Key 将立即不可用，无法恢复。'
					: action === 'rotate'
						? '确认轮换后旧凭据立即失效。新凭据只显示一次，务必保存并更新调用方。'
						: record?.status === 0
							? '禁用后该 Key 无法调用开放 API。'
							: '启用后仍受所属 Application 状态和配额限制。'
			}}</text>
			<nexus-input
				v-if="action === 'delete'"
				v-model="deleteName"
				input-id="delete-key-name"
				label="输入 Key 名称确认删除"
				:hint="record?.name"
				:disabled="busy || loading"
			/>
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
						(action === 'delete' && deleteName !== record?.name)
					"
					@press="submitAction"
					>{{
						action === 'delete'
							? '确认删除'
							: action === 'rotate'
								? '确认轮换'
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
			applicationId: 0,
			app: null,
			record: null,
			error: '',
			notice: '',
			visible: false,
			viewVersion: 0,
			loading: false,
			tab: 'info',
			action: '',
			actionUpdatedAt: undefined,
			formName: '',
			deleteName: '',
			fieldError: '',
			actionError: '',
			busy: false,
			uncertain: false,
			secret: '',
			rotationPublicId: '',
		}
	},
	computed: {
		actionTitle() {
			return (
				{
					rename: '编辑 API Key',
					status: this.record?.status === 0 ? '禁用 API Key' : '启用 API Key',
					delete: '删除 API Key',
					rotate: '轮换 API Key',
				}[this.action] || '确认操作'
			)
		},
	},
	onLoad(query) {
		try {
			this.id = api.positiveId(query.id)
			this.applicationId = api.positiveId(query.applicationId)
		} catch (e) {
			this.error = api.message(e)
		}
	},
	onShow() {
		this.visible = true
		if (this.id && this.applicationId && !this.busy) this.load()
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
			this.loading = false
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
				const app = await api.application(this.applicationId)
				const record = await api.key(this.applicationId, this.id)
				if (!this.visible || version !== this.viewVersion) return
				this.app = app
				this.record = record
				this.uncertain = false
			} catch (e) {
				if (this.visible && version === this.viewVersion) {
					this.record = null
					this.app = null
					const message = await api.showError(e)
					if (this.visible && version === this.viewVersion) this.error = message
				}
			} finally {
				if (version === this.viewVersion) this.loading = false
			}
		},
		openAction(kind) {
			if (this.busy || this.loading || this.secret || this.uncertain) return
			this.action = kind
			this.actionUpdatedAt = this.record.updatedAt
			this.formName = this.record.name
			this.deleteName = ''
			this.fieldError = ''
			this.actionError = ''
			this.rotationPublicId = this.record.publicId
		},
		validateName() {
			this.fieldError = api.nameError(this.formName)
			return !this.fieldError
		},
		async submitAction() {
			if (this.busy || this.loading || this.uncertain || !this.record) return
			const kind = this.action,
				version = this.viewVersion
			if (kind === 'rename' && !this.validateName()) return
			if (kind === 'delete' && this.deleteName !== this.record.name) return
			if (!['rename', 'status', 'delete', 'rotate'].includes(kind)) return
			this.busy = true
			this.actionError = ''
			try {
				if (kind === 'delete') {
					await api.deleteKey(this.applicationId, this.id)
					this.action = ''
					this.busy = false
					if (this.visible && version === this.viewVersion) {
						this.record = null
						this.app = null
						uni.navigateBack({
							fail: () => {
								if (this.visible)
									uni.reLaunch({
										url: '/pages/application-detail/application-detail?id=' + this.applicationId,
										fail: () => {
											this.error = '已删除，但未能返回列表，请重新打开管理页。'
										},
									})
							},
						})
					}
					return
				}
				if (kind === 'rotate') {
					const result = await api.rotateKey(this.id, this.rotationPublicId)
					if (!this.visible || version !== this.viewVersion) {
						this.uncertain = true
						this.notice = '轮换结果请刷新核对，新完整 Key 未保留。'
						return
					}
					this.secret = result.apiKey
					this.action = ''
					await this.load()
				} else {
					const result = await api.updateKey(
						this.applicationId,
						this.id,
						kind === 'rename'
							? { name: this.formName.trim(), expectedUpdatedAt: this.actionUpdatedAt }
							: {
									status: this.record.status === 0 ? 1 : 0,
									expectedUpdatedAt: this.actionUpdatedAt,
								},
					)
					if (!this.visible || version !== this.viewVersion) return
					this.record = { ...this.record, ...result }
					this.action = ''
					uni.showToast({ title: '已保存', icon: 'success' })
				}
			} catch (e) {
				this.uncertain =
					e.code === 'MUTATION_RESULT_UNKNOWN' ||
					e.code === 'API_KEY_ROTATION_CONFLICT' ||
					e.code === 'RESOURCE_CHANGED'
				const message = await api.showError(e)
				if (!this.visible || version !== this.viewVersion) return
				this.actionError = message
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
