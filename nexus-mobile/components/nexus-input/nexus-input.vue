<template>
	<view class="nexus-field">
		<label v-if="label" class="nexus-field__label" :for="inputId">{{
			label
		}}</label>
		<view
			class="nexus-field__shell"
			:class="{
				'nexus-field__shell--focused': focused,
				'nexus-field__shell--error': error,
				'nexus-field__shell--disabled': disabled,
			}"
		>
			<input
				:id="inputId"
				class="nexus-field__input"
				:value="modelValue"
				:type="type"
				:password="password"
				:placeholder="placeholder"
				placeholder-class="nexus-field__placeholder"
				:maxlength="maxlength"
				:disabled="disabled"
				:focus="focus"
				:inputmode="inputmode"
				:confirm-type="confirmType"
				:cursor-spacing="24"
				:aria-label="label || placeholder"
				:aria-invalid="!!error"
				:aria-describedby="
					error || hint ? inputId + '-message' : undefined
				"
				@input="handleInput"
				@focus="handleFocus"
				@blur="handleBlur"
				@confirm="$emit('confirm', $event)"
			/>
			<slot name="suffix" />
		</view>
		<text
			v-if="error || hint"
			:id="inputId + '-message'"
			class="nexus-field__message"
			:class="{ 'nexus-field__message--error': error }"
			:role="error ? 'alert' : undefined"
			>{{ error || hint }}</text
		>
	</view>
</template>

<script>
export default {
	props: {
		modelValue: { type: String, default: '' },
		inputId: { type: String, required: true },
		label: { type: String, default: '' },
		type: { type: String, default: 'text' },
		password: Boolean,
		placeholder: { type: String, default: '' },
		maxlength: { type: Number, default: 140 },
		disabled: Boolean,
		focus: Boolean,
		inputmode: { type: String, default: 'text' },
		confirmType: { type: String, default: 'done' },
		error: { type: String, default: '' },
		hint: { type: String, default: '' },
	},
	emits: ['update:modelValue', 'input', 'focus', 'blur', 'confirm'],
	data() {
		return { focused: false }
	},
	methods: {
		handleInput(event) {
			const value = event.detail.value
			this.$emit('update:modelValue', value)
			this.$emit('input', value)
		},
		handleFocus(event) {
			this.focused = true
			this.$emit('focus', event)
		},
		handleBlur(event) {
			this.focused = false
			this.$emit('blur', event)
		},
	},
}
</script>

<style scoped>
.nexus-field {
	margin-bottom: var(--nexus-space-4);
}
.nexus-field__label {
	display: block;
	margin-bottom: var(--nexus-space-2);
	color: var(--nexus-ink);
	font-size: var(--nexus-font-label);
	font-weight: 600;
}
.nexus-field__shell {
	display: flex;
	box-sizing: border-box;
	min-height: var(--nexus-control-height);
	align-items: center;
	padding: 0 12px;
	border: 1px solid var(--nexus-input-line);
	border-radius: var(--nexus-radius-control);
	background: var(--nexus-surface);
}
.nexus-field__shell--focused {
	border-color: var(--nexus-teal);
	box-shadow: 0 0 0 3px var(--nexus-focus-ring);
}
.nexus-field__shell--error {
	border-color: var(--nexus-danger-ink);
}
.nexus-field__shell--disabled {
	background: var(--nexus-disabled-surface);
}
.nexus-field__input {
	flex: 1;
	width: 0;
	min-width: 0;
	height: 46px;
	color: var(--nexus-ink);
	font-size: var(--nexus-font-body);
}
.nexus-field__placeholder {
	color: var(--nexus-muted);
	font-size: var(--nexus-font-label);
}
.nexus-field__message {
	display: block;
	margin-top: var(--nexus-space-1);
	color: var(--nexus-muted);
	font-size: var(--nexus-font-small);
	line-height: 1.6;
	overflow-wrap: anywhere;
}
.nexus-field__message--error {
	color: var(--nexus-danger-ink);
}
</style>
