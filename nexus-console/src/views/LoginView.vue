<script setup lang="ts">
import loginIllustration from '@/assets/login_page.png'
import brandSpark from '@/assets/icons/brand-spark.svg'
import { RouterLink, useRouter } from 'vue-router'
import { reactive, ref } from 'vue'
import { isAxiosError } from 'axios'
import type { ApiResponse } from '@/api/http'
import { login, type LoginRequest, type LoginResponse } from '@/api/auth'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import {useUserStore} from "@/stores/userStore.ts";

const userStore = useUserStore()
const router = useRouter()
const loginForm = reactive({
  email: '',
  password: '',
})

const formRef = ref<FormInstance>()
const submitting = ref(false)

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

  try {
    await formRef.value.validate()
  } catch {
    return
  }

  const req: LoginRequest = {
    email: loginForm.email.trim(),
    password: loginForm.password,
  }

  submitting.value = true
  try {
    const res: ApiResponse<LoginResponse> = await login(req)
    if (res.code !== 'SUCCESS') {
      ElMessage.error(res.message || '登录失败，请稍后重试')
      return
    }
    userStore.setSession(res.data)
    ElMessage.success('登录成功')
    await router.replace({ name: 'applications' })
  } catch (error) {
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
    } else if (isAxiosError(error) && error.response?.status === 400) {
      ElMessage.error('请检查邮箱和密码')
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
    <el-row class="login-layout">
      <el-col :xs="24" :md="10" class="brand-panel">
        <div class="brand-mark"><img :src="brandSpark" alt="" aria-hidden="true" /><span>Nexus</span></div>

        <div class="brand-story">
          <p class="eyebrow">你的开发者空间</p>
          <h1>让每一次连接，<br />都有清晰的起点。</h1>
          <p class="brand-description">管理你的 Application、API Key 和开放 API 调用。</p>
        </div>

        <div class="illustration">
          <img :src="loginIllustration" alt="橘猫坐在绿色星球上仰望月亮" />
        </div>

        <p class="brand-footer">一片属于你的开发者小宇宙</p>
      </el-col>

      <el-col :xs="24" :md="14" class="form-panel">
        <section class="form-wrap" aria-labelledby="login-title">
          <p class="form-eyebrow">欢迎回来</p>
          <h2 id="login-title">登录 Nexus</h2>
          <p class="form-description">继续管理你的 Application 和 API Key。</p>

          <el-form
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
      </el-col>
    </el-row>
  </main>
</template>

<style scoped>
.login-page,
.login-layout {
  min-height: 100vh;
  min-height: 100dvh;
}

.login-page {
  display: grid;
  place-items: center;
  padding: clamp(16px, 3vw, 40px);
  background: radial-gradient(circle at 3% 7%, #edf2e9 0, transparent 27%), var(--nexus-paper);
}

.login-layout {
  width: min(1380px, 100%);
  min-height: min(760px, calc(100dvh - 48px));
  overflow: hidden;
  border: 1px solid var(--nexus-line);
  border-radius: 22px;
  background: var(--nexus-surface);
  box-shadow: 0 18px 54px rgba(32, 59, 55, 0.08);
}

.brand-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: clamp(32px, 4vw, 64px);
  overflow: hidden;
  background: linear-gradient(145deg, #edf2e9, var(--nexus-sage) 65%, #e7eee5);
  color: var(--nexus-ink);
}

.brand-mark {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 23px;
  font-weight: 700;
  letter-spacing: 0.02em;
}

.brand-mark img {
  width: 20px;
  height: 20px;
}

.brand-story {
  margin-top: clamp(46px, 7vh, 88px);
}

.eyebrow,
.form-eyebrow {
  margin: 0 0 16px;
  color: var(--nexus-teal);
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.1em;
}

.brand-story h1 {
  margin: 0;
  font-size: clamp(30px, 2.7vw, 43px);
  line-height: 1.3;
  letter-spacing: -0.03em;
}

.brand-description {
  max-width: 390px;
  margin: 20px 0 0;
  color: var(--nexus-muted);
  font-size: 15px;
  line-height: 1.8;
}

.illustration {
  display: grid;
  flex: 1;
  min-height: 260px;
  place-items: center;
}

.illustration img {
  display: block;
  width: min(100%, 365px);
  max-height: min(43vh, 390px);
  object-fit: contain;
}

.brand-footer {
  margin: 0;
  color: #6a7d72;
  font-size: 13px;
}

.form-panel {
  display: grid;
  min-width: 0;
  grid-template-columns: minmax(0, 1fr);
  min-height: 0;
  padding: 56px clamp(32px, 6vw, 88px);
  place-items: center;
  background: var(--nexus-surface);
}

.form-wrap {
  width: min(100%, 430px);
  min-width: 0;
}

.form-wrap h2 {
  margin: 0;
  color: var(--nexus-ink);
  font-size: clamp(30px, 2.5vw, 38px);
  line-height: 1.3;
}

.form-description {
  margin: 12px 0 40px;
  color: var(--nexus-muted);
  font-size: 15px;
}

.login-form :deep(.el-form-item__label) {
  color: var(--nexus-ink);
  font-weight: 600;
}

.login-form :deep(.el-form-item) {
  margin-bottom: 24px;
}

.login-form :deep(.el-input__wrapper) {
  border-radius: 10px;
  box-shadow: 0 0 0 1px #d6e0d7 inset;
}

.login-form :deep(.submit-item) {
  margin-top: 32px;
  margin-bottom: 0;
}

.login-button {
  width: 100%;
  min-height: 46px;
  border-radius: 10px;
  font-weight: 600;
}

.register-prompt {
  margin: 24px 0 0;
  color: var(--nexus-muted);
  font-size: 14px;
  text-align: center;
}

.register-prompt a {
  color: var(--nexus-teal);
  text-decoration: none;
}

.register-prompt a:hover {
  text-decoration: underline;
}

@media (max-width: 991px) {
  .login-page { display: block; padding: 0; }
  .login-layout { min-height: 100dvh; border: 0; border-radius: 0; box-shadow: none; }
  .brand-panel {
    min-height: 0;
    padding: 28px 24px 32px;
  }

  .brand-story {
    margin-top: 36px;
  }

  .illustration {
    min-height: 0;
    margin: 20px 0 12px;
  }

  .illustration img {
    max-height: 220px;
  }

  .form-panel {
    min-height: 0;
    padding: 56px 24px 72px;
  }
}

@media (max-width: 600px) {
  .brand-panel {
    padding: 24px;
  }

  .brand-story {
    margin-top: 28px;
  }

  .brand-story h1 {
    font-size: 26px;
  }

  .brand-description {
    margin-top: 10px;
    font-size: 13px;
  }

  .illustration,
  .brand-footer {
    display: none;
  }

  .form-panel {
    padding-top: 48px;
  }
}
</style>
