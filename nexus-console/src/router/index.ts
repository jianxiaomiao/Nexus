import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/userStore'
import { ElMessage } from 'element-plus'
import { configureSessionNavigation } from '@/api/http'
import { isWebBrowser } from '@/auth/webSession'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      redirect: { name: 'applications' },
      component: () => import('../views/ConsoleLayout.vue'),
      meta: { requiresAuth: true },
      children: [
        {
          path: 'applications',
          name: 'applications',
          component: () => import('../views/ApplicationsView.vue'),
        },
        {
          path: 'applications/:id',
          name: 'application-detail',
          component: () => import('../views/ApplicationDetailView.vue'),
        },
        {
          path: 'applications/:applicationId/keys/:keyId',
          name: 'api-key-detail',
          component: () => import('../views/ApiKeyDetailView.vue'),
        },
        {
          path: 'playground',
          name: 'playground',
          component: () => import('../views/ApiPlaygroundView.vue'),
        },
        {
          path: 'short-links',
          name: 'short-links',
          component: () => import('../views/ShortLinksView.vue'),
        },
      ],
    },
    {
      path: '/docs',
      component: () => import('../views/DocsLayout.vue'),
      meta: { requiresAuth: true },
      children: [
        { path: '', name: 'docs-home', component: () => import('../views/DocsHomeView.vue') },
        { path: ':slug', name: 'docs-article', component: () => import('../views/DocsArticleView.vue') },
      ],
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('../views/LoginView.vue'),
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('../views/RegisterView.vue'),
    },
  ],
})

configureSessionNavigation(router)

router.beforeEach(async (to) => {
  const userStore = useUserStore()
  const desktop = typeof window === 'undefined' ? undefined : window.nexusDesktop
  if (isWebBrowser() && userStore.accessToken && !userStore.checkWebState()) {
    return to.name === 'login' ? true : { name: 'login', replace: true }
  }
  if ((desktop || isWebBrowser()) && !userStore.clearing && userStore.accessToken && userStore.expiresAt <= Date.now()) {
    const renewed = await userStore.ensureSession(userStore.sessionContext())
    if (renewed.status === 'stale' || renewed.status === 'failed') return false
    if (renewed.status === 'reauthenticate') {
      return to.name === 'login' ? true : { name: 'login', replace: true }
    }
  }
  if (userStore.clearing || (userStore.accessToken && userStore.expiresAt <= Date.now())) {
    const result = await userStore.invalidateSession(userStore.clearing
      ? { reason: 'logout' } // Join the existing clear; do not start another intent.
      : { reason: 'expired', context: {
        operationVersion: userStore.operationVersion, mainRevision: userStore.mainRevision,
      } })
    if (result.status === 'stale') return false
    if (result.status === 'ok' && result.operationVersion !== userStore.operationVersion) return false
    if (result.status === 'failed') {
      ElMessage.error({ message: userStore.clearError ?? '尚未确认会话清理，请重试。', grouping: true })
      return false
    }
  }
  if (userStore.clearError) {
    ElMessage.error({ message: userStore.clearError, grouping: true })
    return false
  }
  // The state may have changed while the clear was pending.
  const signedIn = Boolean(userStore.accessToken) && userStore.expiresAt > Date.now()

  if (to.matched.some((record) => record.meta.requiresAuth) && !signedIn) {
    return { name: 'login', replace: true }
  }
  if (signedIn && (to.name === 'login' || to.name === 'register')) {
    return { name: 'applications' }
  }
})

export default router
