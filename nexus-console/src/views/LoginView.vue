<script setup lang="ts">
import LoginPlanetScene from '@/components/LoginPlanetScene.vue'
import loginIllustration from '@/assets/login_page.png'
import brandSpark from '@/assets/icons/brand-spark.svg'
import { RouterLink, useRouter } from 'vue-router'
import { reactive, ref, onBeforeUnmount } from 'vue'
import { isAxiosError } from 'axios'
import type { ApiResponse } from '@/api/http'
import { login, type LoginRequest, type LoginResponse } from '@/api/auth'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useUserStore } from '@/stores/userStore.ts'

const userStore = useUserStore()
const router = useRouter()
const loginForm = reactive({
  email: '',
  password: '',
})

const formRef = ref<FormInstance>()
const submitting = ref(false)
let disposed = false
let pendingIntent: number | undefined
let loginCompleted = false
onBeforeUnmount(() => {
  disposed = true
  if (submitting.value && !loginCompleted && pendingIntent === userStore.operationVersion)
    userStore.beginLogin()
  loginForm.password = ''
})

const rules: FormRules<typeof loginForm> = {
  email: [
    { required: true, whitespace: true, message: '邮箱不能为空', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' },
    { max: 254, message: '邮箱长度不能超过254个字符', trigger: 'blur' },
  ],
  password: [
    { required: true, whitespace: true, message: '密码不能为空', trigger: 'blur' },
    { max: 64, message: '密码不能超过64个字符', trigger: 'blur' },
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

  const req: LoginRequest = {
    email: loginForm.email.trim(),
    password: loginForm.password,
  }

  submitting.value = true
  const attempt = userStore.beginLogin()
  pendingIntent = attempt.operationVersion
  try {
    if (attempt.blocked) {
      ElMessage.error('尚未确认会话清理，请先重试清理。')
      return
    }
    const res: ApiResponse<LoginResponse> = await login(
      req,
      attempt.mainRevision,
      () => attempt.operationVersion === userStore.operationVersion && !userStore.clearing,
    )
    if (res.code !== 'SUCCESS') {
      if (attempt.operationVersion !== userStore.operationVersion) return
      ElMessage.error(res.message || '登录失败，请稍后重试')
      return
    }
    const completion = await userStore.completeLogin(attempt, res.data)
    if (completion.status !== 'ok' && attempt.operationVersion !== userStore.operationVersion)
      return
    if (completion.status === 'stale') return
    if (completion.status === 'failed') {
      ElMessage.error(
        completion.code === 'SESSION_CHANGED'
          ? '登录会话已变化，请刷新后重新登录'
          : '无法保存登录会话，请刷新后重新登录',
      )
      return
    }
    if (disposed || completion.operationVersion !== userStore.operationVersion) return
    loginCompleted = true
    ElMessage.success('登录成功')
    await router.replace({ name: 'applications' })
  } catch (error) {
    if (attempt.operationVersion !== userStore.operationVersion) return
    if (
      isAxiosError<ApiResponse<unknown>>(error) &&
      error.response?.status === 401 &&
      error.response.data?.code === 'AUTH_INVALID_CREDENTIALS'
    ) {
      ElMessage.error('邮箱或密码错误')
    } else if (
      isAxiosError<ApiResponse<unknown>>(error) &&
      error.response?.status === 403 &&
      error.response.data?.code === 'AUTH_ACCOUNT_FORBIDDEN'
    ) {
      ElMessage.error('账号已封禁')
    } else if (
      isAxiosError<ApiResponse<unknown>>(error) &&
      error.response?.data?.code === 'SESSION_CHANGED'
    ) {
      ElMessage.error('登录会话已变化，请刷新后重新登录')
    } else if (isAxiosError(error) && error.response?.status === 400) {
      ElMessage.error('请检查邮箱和密码')
    } else if (isAxiosError(error) && error.response?.status === 429) {
      ElMessage.error('操作过于频繁，请稍后再试')
    } else if (isAxiosError(error) && !error.response) {
      ElMessage.error('无法连接服务器，请确认后端已启动')
    } else {
      ElMessage.error('登录失败，请稍后重试')
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <div class="login-landscape" aria-hidden="true"><i></i><i></i></div>
    <header class="brand-mark"><img :src="brandSpark" alt="" aria-hidden="true" /><span>Nexus</span></header>
    <div class="login-layout">
      <div class="brand-panel">
        <LoginPlanetScene :fallback-src="loginIllustration" />
        <p class="brand-footer">一片属于你的开发者小宇宙</p>
      </div>
      <div class="form-panel">
        <section class="form-wrap" aria-labelledby="login-title">
          <p class="form-eyebrow">欢迎回来</p>
          <h2 id="login-title">登录 Nexus</h2>
          <p class="form-description">继续管理你的 Application 和 API Key。</p>

          <el-alert v-if="userStore.sessionNotice" :title="userStore.sessionNotice" type="warning" :closable="false" show-icon />
          <el-form
            :disabled="submitting"
            ref="formRef"
            :model="loginForm"
            :rules="rules"
            label-position="top"
            size="large"
            class="login-form"
            @submit.prevent="onSubmit"
          >
            <el-form-item label="邮箱" prop="email">
              <el-input v-model="loginForm.email" type="email" placeholder="请输入邮箱" autocomplete="email" :maxlength="254" />
            </el-form-item>
            <el-form-item label="密码" prop="password">
              <el-input
                v-model="loginForm.password"
                type="password"
                placeholder="请输入密码"
                autocomplete="current-password"
                show-password
                :maxlength="64"
              />
            </el-form-item>
            <el-form-item class="submit-item">
              <el-button native-type="submit" type="primary" size="large" class="login-button" :loading="submitting">登录</el-button>
            </el-form-item>
          </el-form>

          <p class="register-prompt">还没有账号？<RouterLink :to="{ name: 'register' }">创建账号</RouterLink></p>
        </section>
      </div>
    </div>
  </main>
</template>

<style scoped>
.login-page { position: relative; isolation: isolate; min-height: 100dvh; background: radial-gradient(ellipse at 31% 44%,#fffaf0 4%,transparent 59%),linear-gradient(126deg,#f8f2e5,#fbf7ee 60%,#f6efe4); color: var(--nexus-ink); }
.login-landscape { position: absolute; inset: 0; z-index: -1; overflow: hidden; pointer-events: none; }
.login-landscape i { position: absolute; width: 70%; height: 15%; bottom: -10%; left: -12%; border-radius: 50%; transform: rotate(8deg); background: #dbe2c8; }
.login-landscape i + i { left: auto; right: -15%; bottom: -8%; background: #e6e8d6; transform: rotate(-12deg); }
.brand-mark { position: absolute; top: clamp(22px,4.7vh,54px); left: clamp(25px,5.3vw,100px); display: flex; align-items: center; gap: 10px; font-size: clamp(29px,2.5vw,42px); line-height: 1; font-weight: 750; letter-spacing: -.045em; }
.brand-mark img { width: 24px; height: 24px; order: 1; }
.login-layout { max-width: 1610px; margin: auto; min-height: 100dvh; display: grid; grid-template-columns: minmax(0,1.4fr) minmax(320px,1fr); align-items: center; gap: clamp(18px,3.4vw,68px); padding: 88px clamp(32px,7.5vw,134px) 64px clamp(22px,3.8vw,72px); }
.brand-panel, .form-panel { min-width: 0; }
.brand-footer { margin: 5px 0 0; text-align: center; color: var(--nexus-muted); font-size: 12px; }
.form-wrap { width: 100%; max-width: 500px; margin: auto; border: 1px solid #fffdf6; border-radius: 30px; background: linear-gradient(135deg,#fffdf6ee,#fcf6eceb); box-shadow: 0 24px 70px -28px #77582742,0 3px 10px #785a3110,inset 0 0 0 1px #eadfcf79; padding: clamp(28px,3.7vw,58px); }
.form-eyebrow { margin: 0 0 12px; color: var(--nexus-teal); font-size: 13px; font-weight: 700; letter-spacing: .1em; }
.form-wrap h2 { margin: 0; color: var(--nexus-ink); font-size: clamp(30px,2.7vw,42px); line-height: 1.3; }
.form-description { margin: 12px 0 30px; color: var(--nexus-muted); font-size: 14px; line-height: 1.6; }
.login-form :deep(.el-form-item__label) { color: var(--nexus-ink); font-weight: 600; }
.login-form :deep(.el-form-item) { margin-bottom: 24px; }
.login-form :deep(.el-input__wrapper) { min-height: 48px; border-radius: 12px; background: #fffdf8; box-shadow: 0 0 0 1px #c9c5b6 inset; }
.login-form :deep(.el-input__inner) { font-size: 16px; }
.login-form :deep(.el-input__wrapper.is-focus) { box-shadow: 0 0 0 2px var(--nexus-teal) inset; }
.login-form :deep(.submit-item) { margin-top: 28px; margin-bottom: 0; }
.login-button { width: 100%; min-height: 48px; border-radius: 12px; font-weight: 600; }
.register-prompt { margin: 22px 0 0; color: var(--nexus-muted); font-size: 14px; text-align: center; }
.register-prompt a { color: var(--nexus-teal); text-decoration: none; }
.register-prompt a:hover { text-decoration: underline; }
@media (max-width: 760px) {
  .brand-mark { position: relative; inset: auto; justify-content: center; padding: 20px 0 8px; font-size: 29px; }
  .login-layout { min-height: 0; display: flex; flex-direction: column; gap: 12px; padding: 0 22px 26px; }
  .brand-panel { width: 100%; max-width: 490px; }
  .brand-footer { display: none; }
  .form-panel { width: 100%; max-width: 400px; }
  .form-wrap { padding: 0; border: 0; border-radius: 0; background: transparent; box-shadow: none; }
  .form-eyebrow { display: none; }
  .form-wrap h2 { font-size: 30px; text-align: center; }
  .form-description { text-align: center; font-size: 13px; margin: 8px 0 16px; }
  .login-form :deep(.el-form-item) { margin-bottom: 20px; }
  .login-form :deep(.submit-item) { margin-top: 23px; margin-bottom: 0; }
  .register-prompt { margin-top: 17px; }
}
@media (min-width: 761px) and (max-height: 610px) {
  .login-layout { padding-top: 70px; padding-bottom: 24px; }
  .form-wrap { padding-block: 24px; }
}
</style>
