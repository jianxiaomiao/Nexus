import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/userStore'

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

router.beforeEach((to) => {
  const userStore = useUserStore()
  const signedIn = Boolean(userStore.accessToken) && userStore.expiresAt > Date.now()
  if (userStore.accessToken && !signedIn) userStore.clearSession()

  if (to.matched.some((record) => record.meta.requiresAuth) && !signedIn) {
    return { name: 'login' }
  }
  if (signedIn && (to.name === 'login' || to.name === 'register')) {
    return { name: 'applications' }
  }
})

export default router
