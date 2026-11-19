<script setup lang="ts">
import LoginPlanetScene from '@/components/LoginPlanetScene.vue'
import loginIllustration from '@/assets/login_page.png'
import brandSpark from '@/assets/icons/brand-spark.svg'
import { reactive, ref, onBeforeUnmount } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { isAxiosError } from 'axios'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { register, type RegisterRequest, type RegisterResponse } from '@/api/auth'
import type { ApiResponse } from '@/api/http'

const registerForm = reactive({
  email: '',
  displayName: '',
  password: '',
  confirmPassword: '',
})

const formRef = ref<FormInstance>()
const submitting = ref(false)
let disposed = false
onBeforeUnmount(() => {
  disposed = true
  registerForm.password = ''
  registerForm.confirmPassword = ''
})
const router = useRouter()

const rules: FormRules<typeof registerForm> = {
  email: [
    { required: true, whitespace: true, message: '邮箱不能为空', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' },
    { max: 254, message: '邮箱长度不能超过254个字符', trigger: 'blur' },
  ],
  displayName: [
    { required: true, whitespace: true, message: '显示名称不能为空', trigger: 'blur' },
    { max: 64, message: '显示名称不能超过64个字符', trigger: 'blur' },
  ],
  password: [
    { required: true, whitespace: true, message: '密码不能为空', trigger: 'blur' },
    { min: 8, max: 64, message: '密码长度必须在8到64个字符之间', trigger: 'blur' },
  ],
  confirmPassword: [
    {
      validator: (_rule, value: string, callback) => {
        if (!value) callback(new Error('请再次输入密码'))
        else if (value !== registerForm.password) callback(new Error('两次输入的密码不一致'))
        else callback()
      },
      trigger: 'blur',
    },
  ],
}

const onSubmit = async () => {
  if (submitting.value || !formRef.value) return

  submitting.value = true
  try {
    await formRef.value.validate()
  } catch {
    submitting.value = false
    return
  }
  if (disposed) {
    submitting.value = false
    return
  }

  const request: RegisterRequest = {
    email: registerForm.email.trim(),
    displayName: registerForm.displayName.trim(),
    password: registerForm.password,
  }

  submitting.value = true
  let response: ApiResponse<RegisterResponse>
  try {
    response = await register(request)
  } catch (error) {
    if (disposed) return
    if (
      isAxiosError<ApiResponse<unknown>>(error) &&
      error.response?.status === 409 &&
      error.response.data?.code === 'AUTH_EMAIL_ALREADY_REGISTERED'
    ) {
      ElMessage.error('该邮箱已被注册，请直接登录')
    } else if (isAxiosError(error) && error.response?.status === 429) {
      ElMessage.error('操作过于频繁，请稍后再试')
    } else if (isAxiosError(error) && !error.response) {
      ElMessage.error('无法连接服务器，请确认后端已启动')
    } else {
      ElMessage.error('注册失败，请稍后重试')
    }
    return
  } finally {
    submitting.value = false
  }

  if (disposed) return
  if (response.code !== 'SUCCESS') {
    ElMessage.error(response.message || '注册失败，请稍后重试')
    return
  }

  ElMessage.success('注册成功，请登录')
  await router.push({ name: 'login' })
}
</script>

<template>
  <main class="register-page">
    <div class="register-landscape" aria-hidden="true"><i></i><i></i></div>
    <header class="brand-mark"><img :src="brandSpark" alt="" aria-hidden="true" /><span>Nexus</span></header>
    <div class="register-layout">
      <div class="brand-panel">
        <LoginPlanetScene :fallback-src="loginIllustration" />
        <p class="brand-footer">一片属于你的开发者小宇宙</p>
      </div>
      <div class="form-panel">
        <section class="form-wrap" aria-labelledby="register-title">
          <p class="form-eyebrow">从这里开始</p>
          <h2 id="register-title">创建 Nexus 账号</h2>
          <p class="form-description">用邮箱注册，开启你的开发者空间。</p>

          <el-form
            :disabled="submitting"
            ref="formRef"
            :model="registerForm"
            :rules="rules"
            label-position="top"
            size="large"
            class="register-form"
            @submit.prevent="onSubmit"
          >
            <el-form-item label="邮箱" prop="email">
              <el-input v-model="registerForm.email" type="email" placeholder="请输入常用邮箱" autocomplete="email" :maxlength="254" />
              <p class="field-hint">此邮箱将用于登录。</p>
            </el-form-item>
            <el-form-item label="显示名称" prop="displayName">
              <el-input v-model="registerForm.displayName" placeholder="给自己取个名字" :maxlength="64" />
              <p class="field-hint">用于界面展示，不作为登录账号。</p>
            </el-form-item>
            <el-form-item label="密码" prop="password">
              <el-input
                v-model="registerForm.password"
                type="password"
                placeholder="设置密码"
                autocomplete="new-password"
                show-password
                :maxlength="64"
              />
              <p class="field-hint">密码长度为 8–64 个字符。</p>
            </el-form-item>
            <el-form-item label="再次输入密码" prop="confirmPassword">
              <el-input
                v-model="registerForm.confirmPassword"
                type="password"
                placeholder="请再次输入密码"
                autocomplete="new-password"
                show-password
              />
            </el-form-item>
            <el-form-item class="submit-item">
              <el-button native-type="submit" type="primary" size="large" class="submit-button" :loading="submitting">创建账号</el-button>
            </el-form-item>
          </el-form>

          <p class="login-prompt">已有账号？<RouterLink :to="{ name: 'login' }">登录</RouterLink></p>
        </section>
      </div>
    </div>
  </main>
</template>

<style scoped>
.register-page { position: relative; isolation: isolate; min-height: 100dvh; background: radial-gradient(ellipse at 31% 44%,#fffaf0 4%,transparent 59%),linear-gradient(126deg,#f8f2e5,#fbf7ee 60%,#f6efe4); color: var(--nexus-ink); }
.register-landscape { position: absolute; inset: 0; z-index: -1; overflow: hidden; pointer-events: none; }
.register-landscape i { position: absolute; width: 70%; height: 15%; bottom: -10%; left: -12%; border-radius: 50%; transform: rotate(8deg); background: #dbe2c8; }
.register-landscape i + i { left: auto; right: -15%; bottom: -8%; background: #e6e8d6; transform: rotate(-12deg); }
.brand-mark { position: absolute; top: clamp(22px,4.7vh,54px); left: clamp(25px,5.3vw,100px); display: flex; align-items: center; gap: 10px; font-size: clamp(29px,2.5vw,42px); line-height: 1; font-weight: 750; letter-spacing: -.045em; }
.brand-mark img { width: 24px; height: 24px; order: 1; }
.register-layout { max-width: 1610px; margin: auto; min-height: 100dvh; display: grid; grid-template-columns: minmax(0,1.4fr) minmax(320px,1fr); align-items: center; gap: clamp(18px,3.4vw,68px); padding: 88px clamp(32px,7.5vw,134px) 64px clamp(22px,3.8vw,72px); }
.brand-panel, .form-panel { min-width: 0; }
.brand-footer { margin: 5px 0 0; text-align: center; color: var(--nexus-muted); font-size: 12px; }
.form-wrap { width: 100%; max-width: 500px; margin: auto; border: 1px solid #fffdf6; border-radius: 30px; background: linear-gradient(135deg,#fffdf6ee,#fcf6eceb); box-shadow: 0 24px 70px -28px #77582742,0 3px 10px #785a3110,inset 0 0 0 1px #eadfcf79; padding: clamp(28px,3.7vw,58px); }
.form-eyebrow { margin: 0 0 12px; color: var(--nexus-teal); font-size: 13px; font-weight: 700; letter-spacing: .1em; }
.form-wrap h2 { margin: 0; color: var(--nexus-ink); font-size: clamp(30px,2.7vw,42px); line-height: 1.3; }
.form-description { margin: 12px 0 30px; color: var(--nexus-muted); font-size: 14px; line-height: 1.6; }
.register-form :deep(.el-form-item__label) { color: var(--nexus-ink); font-weight: 600; }
.register-form :deep(.el-form-item) { margin-bottom: 24px; }
.register-form :deep(.el-input__wrapper) { min-height: 48px; border-radius: 12px; background: #fffdf8; box-shadow: 0 0 0 1px #c9c5b6 inset; }
.register-form :deep(.el-input__inner) { font-size: 16px; }
.register-form :deep(.el-input__wrapper.is-focus) { box-shadow: 0 0 0 2px var(--nexus-teal) inset; }
.register-form :deep(.submit-item) { margin-top: 28px; margin-bottom: 0; }
.submit-button { width: 100%; min-height: 48px; border-radius: 12px; font-weight: 600; }
.login-prompt { margin: 22px 0 0; color: var(--nexus-muted); font-size: 14px; text-align: center; }
.login-prompt a { color: var(--nexus-teal); text-decoration: none; }
.login-prompt a:hover { text-decoration: underline; }
@media (max-width: 760px) {
  .brand-mark { position: relative; inset: auto; justify-content: center; padding: 20px 0 8px; font-size: 29px; }
  .register-layout { min-height: 0; display: flex; flex-direction: column; gap: 12px; padding: 0 22px 26px; }
  .brand-panel { width: 100%; max-width: 490px; }
  .brand-footer { display: none; }
  .form-panel { width: 100%; max-width: 400px; }
  .form-wrap { padding: 0; border: 0; border-radius: 0; background: transparent; box-shadow: none; }
  .form-eyebrow { display: none; }
  .form-wrap h2 { font-size: 30px; text-align: center; }
  .form-description { text-align: center; font-size: 13px; margin: 8px 0 16px; }
  .register-form :deep(.el-form-item) { margin-bottom: 20px; }
  .register-form :deep(.submit-item) { margin-top: 23px; margin-bottom: 0; }
  .login-prompt { margin-top: 17px; }
}
@media (min-width: 761px) and (max-height: 610px) {
  .register-layout { padding-top: 70px; padding-bottom: 24px; }
  .form-wrap { padding-block: 24px; }
}
.field-hint { width: 100%; margin: 6px 0 0; color: var(--nexus-muted); font-size: 12px; line-height: 1.5; }
</style>
