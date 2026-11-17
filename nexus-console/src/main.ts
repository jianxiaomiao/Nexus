import 'element-plus/dist/index.css'
import './assets/main.css'

import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import App from './App.vue'
import router from './router'
import { createPinia } from 'pinia'
import {useUserStore} from "@/stores/userStore.ts";
import { watch } from 'vue'
import { isWebBrowser, webSession, type SharedWebState } from '@/auth/webSession'

async function bootstrap() {
  const app = createApp(App)
  const pinia = createPinia()

  app.use(pinia)
  app.use(ElementPlus)

  const userStore = useUserStore(pinia)   // 获取容器里的用户 Store
  await userStore.initializeSession()     // 调用它的方法

  if( userStore.initializationStatus !== 'ready'){
    const root = document.querySelector<HTMLDivElement>('#app')

    if (root) {
      root.textContent =
        userStore.initializationError ?? '会话初始化未完成，请刷新重试。'
    }

    return
  }

  app.use(router)
  if (isWebBrowser()) {
    watch(() => userStore.webEvent, async () => {
      let state: SharedWebState | null = null
      try { state = webSession().read() } catch { /* Store already cleared on the invalid metadata event. */ }
      if (state?.phase === 'ready' && !userStore.accessToken && !userStore.clearing) {
        const result = await userStore.restoreWebSession()
        if (result.status !== 'ok') return
        if (router.currentRoute.value.name === 'login') await router.replace({ name: 'applications' })
      } else if (!userStore.accessToken && router.currentRoute.value.matched.some(record => record.meta.requiresAuth)) {
        await router.replace({ name: 'login' })
      }
    }, { immediate: true })
  }
  await router.isReady()

  app.mount('#app')
}

void bootstrap().catch((error: unknown) => {
  console.error(
    '应用启动异常',
    error instanceof Error ? error.name : 'UnknownError',
  )

  const root = document.querySelector<HTMLDivElement>('#app')
  if (root) {
    root.textContent = '应用启动失败，请刷新重试。'
  }
})
