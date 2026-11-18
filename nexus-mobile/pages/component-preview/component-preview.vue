<template>
	<view class="nexus-page preview-page">
		<text class="preview-title">Nexus 组件预览</text>
		<text class="preview-description"
			>暖白纸面、青绿操作和清楚的状态反馈。此页仅演示组件，不发送业务请求。</text
		>
		<view class="preview-section">
			<nexus-card title="按钮">
				<view class="preview-buttons">
					<nexus-button @press="pressCount++">主操作</nexus-button>
					<nexus-button variant="secondary" @press="pressCount++"
						>次要操作</nexus-button
					>
					<nexus-button variant="text" @press="pressCount++"
						>文字操作</nexus-button
					>
					<nexus-button variant="danger" @press="pressCount++"
						>危险样式</nexus-button
					>
					<nexus-button disabled @press="pressCount++"
						>不可用</nexus-button
					>
					<nexus-button loading @press="pressCount++"
						>处理中</nexus-button
					>
				</view>
				<text class="preview-caption"
					>已收到 {{ pressCount }} 次 press
					事件；危险按钮这里只演示样式。</text
				>
			</nexus-card>
		</view>
		<view class="preview-section">
			<nexus-card title="输入与双向绑定">
				<nexus-input
					v-model="name"
					input-id="preview-name"
					label="名称"
					placeholder="试着输入一些文字"
					:maxlength="64"
					:error="inputError"
					hint="离开字段后由页面检查，输入框负责显示错误。"
					@input="inputError = ''"
					@blur="inputError = name.trim() ? '' : '请输入名称'"
				>
					<template #suffix
						><nexus-button variant="text" compact @press="name = ''"
							>清空</nexus-button
						></template
					>
				</nexus-input>
				<text class="preview-value"
					>父页面收到：{{ name || '（空）' }}</text
				>
				<nexus-input
					model-value="不可编辑的内容"
					input-id="preview-disabled"
					label="不可用输入框"
					disabled
				/>
			</nexus-card>
		</view>
		<view class="preview-section">
			<nexus-card title="状态标签">
				<view class="preview-buttons">
					<nexus-badge tone="success" label="已启用" />
					<nexus-badge tone="warning" label="已禁用" />
					<nexus-badge tone="danger" label="失败" />
					<nexus-badge label="已删除" />
				</view>
				<template #footer
					><text class="preview-caption"
						>状态始终有文字；成功色与青绿色主操作区分。</text
					></template
				>
			</nexus-card>
		</view>
		<view class="preview-section preview-notices">
			<nexus-notice
				tone="info"
				title="操作提示"
				message="完整 API Key 仅在创建时显示一次。"
			/>
			<nexus-notice
				tone="success"
				title="已保存"
				message="可以继续当前操作。"
			/>
			<nexus-notice
				tone="warning"
				title="确认影响"
				message="禁用 Application 后，其 API Key 将无法调用。"
			/>
			<nexus-notice
				tone="danger"
				title="暂时无法保存"
				message="请稍后重试。"
				code="EXAMPLE_ERROR"
			>
				<template #action
					><nexus-button
						variant="secondary"
						compact
						@press="pressCount++"
						>重试样式</nexus-button
					></template
				>
			</nexus-notice>
		</view>
		<view class="preview-section">
			<nexus-card>
				<nexus-state
					title="从第一个 Application 开始"
					description="空状态在内容区域说明下一步。"
					illustration="/static/auth/login-planet.png"
				>
					<template #action
						><nexus-button @press="pressCount++"
							>操作样式</nexus-button
						></template
					>
				</nexus-state>
			</nexus-card>
		</view>
		<view class="preview-section">
			<nexus-card
				><nexus-state
					kind="loading"
					title="正在加载"
					description="加载状态保留明确文字。"
			/></nexus-card>
		</view>
		<view class="preview-section">
			<nexus-card
				><nexus-state
					kind="error"
					title="暂时无法加载"
					description="错误显示在原位置，可提供重试操作。"
					><template #action
						><nexus-button variant="secondary" @press="pressCount++"
							>重试样式</nexus-button
						></template
					></nexus-state
				></nexus-card
			>
		</view>
	</view>
</template>

<script>
export default {
	data() {
		return { pressCount: 0, name: '', inputError: '' }
	},
}
</script>

<style scoped>
.preview-page {
	max-width: 760px;
	margin: 0 auto;
}
.preview-title {
	display: block;
	color: var(--nexus-ink);
	font-size: var(--nexus-font-title);
	font-weight: 700;
}
.preview-description {
	display: block;
	margin-top: var(--nexus-space-2);
	color: var(--nexus-muted);
	font-size: var(--nexus-font-label);
}
.preview-section {
	margin-top: var(--nexus-space-4);
}
.preview-buttons {
	display: flex;
	flex-wrap: wrap;
	align-items: center;
	gap: var(--nexus-space-2);
}
.preview-caption {
	display: block;
	margin-top: var(--nexus-space-2);
	color: var(--nexus-muted);
	font-size: var(--nexus-font-small);
}
.preview-value {
	display: block;
	margin-bottom: var(--nexus-space-4);
	overflow-wrap: anywhere;
	font-size: var(--nexus-font-label);
}
.preview-notices {
	display: flex;
	flex-direction: column;
	gap: var(--nexus-space-3);
}
</style>
