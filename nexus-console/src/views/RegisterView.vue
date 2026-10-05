<script setup lang="ts">
import loginIllustration from '@/assets/login_page.png'
import brandSpark from '@/assets/icons/brand-spark.svg'
import { reactive, ref } from 'vue'
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

  try {
    await formRef.value.validate()
  } catch {
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
    if (
      isAxiosError<ApiResponse<unknown>>(error) &&
      error.response?.status === 409 &&
      error.response.data?.code === 'AUTH_EMAIL_ALREADY_REGISTERED'
    ) {
      ElMessage.error('该邮箱已被注册，请直接登录')
    } else if (isAxiosError(error) && !error.response) {
      ElMessage.error('无法连接服务器，请确认后端已启动')
    } else {
      ElMessage.error('注册失败，请稍后重试')
    }
    return
  } finally {
    submitting.value = false
  }

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
    <el-row class="register-layout">
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
        <section class="form-wrap" aria-labelledby="register-title">
          <p class="form-eyebrow">从这里开始</p>
          <h2 id="register-title">创建 Nexus 账号</h2>
          <p class="form-description">用邮箱注册，开启你的开发者空间。</p>

          <el-form
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
      </el-col>
    </el-row>
  </main>
</template>

<style scoped>
.register-page,
.register-layout {
  min-height: 100vh;
  min-height: 100dvh;
}

.register-page {
  display: grid;
  place-items: center;
  padding: clamp(16px, 3vw, 40px);
  background: radial-gradient(circle at 3% 7%, #edf2e9 0, transparent 27%), var(--nexus-paper);
}

.register-layout {
  width: min(1380px, 100%);
  min-height: min(830px, calc(100dvh - 48px));
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
  margin: 12px 0 32px;
  color: var(--nexus-muted);
  font-size: 15px;
}

.register-form :deep(.el-form-item__label) {
  color: var(--nexus-ink);
  font-weight: 600;
}

.register-form :deep(.el-form-item) {
  margin-bottom: 20px;
}

.register-form :deep(.el-input__wrapper) {
  border-radius: 10px;
  box-shadow: 0 0 0 1px #d6e0d7 inset;
}

.register-form :deep(.el-form-item__error) {
  position: static;
  width: 100%;
  margin-top: 4px;
}

.field-hint {
  width: 100%;
  margin: 4px 0 0;
  color: var(--nexus-muted);
  font-size: 12px;
  line-height: 1.5;
}

.register-form :deep(.submit-item) {
  margin-top: 28px;
  margin-bottom: 0;
}

.submit-button {
  width: 100%;
  min-height: 46px;
  border-radius: 10px;
  font-weight: 600;
}

.login-prompt {
  margin: 24px 0 0;
  color: var(--nexus-muted);
  font-size: 14px;
  text-align: center;
}

.login-prompt a {
  color: var(--nexus-teal);
  text-decoration: none;
}

.login-prompt a:hover {
  text-decoration: underline;
}

@media (max-width: 991px) {
  .register-page { display: block; padding: 0; }
  .register-layout { min-height: 100dvh; border: 0; border-radius: 0; box-shadow: none; }
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
