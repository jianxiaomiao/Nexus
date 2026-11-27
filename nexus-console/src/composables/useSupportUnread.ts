import { nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { http, type ApiResponse } from '@/api/http'
import { useUserStore } from '@/stores/userStore'

const POLL_MS = 15_000

export function useSupportUnread(enabled: boolean) {
  const user = useUserStore()
  const unread = ref(0)
  let request: AbortController | undefined
  let suspended = true
  let timer: ReturnType<typeof setInterval> | undefined

  function reset() {
    request?.abort()
    request = undefined
    unread.value = 0
  }

  async function refresh() {
    if (suspended || document.visibilityState !== 'visible') return
    if (!user.accessToken || user.clearing || user.clearError || !user.checkWebState()) {
      reset()
      return
    }
    if (request) return
    const pending = new AbortController()
    const sessionId = user.sessionId
    const operation = user.operationVersion
    request = pending
    function current() {
      return request === pending && user.sessionId === sessionId && user.operationVersion === operation
    }
    try {
      const response = await http.get<ApiResponse<{ unread: number }>>('/support/chat/unread', { signal: pending.signal })
      if (!current() || !user.checkWebState()) return
      const total = response.data.data?.unread
      unread.value = typeof total === 'number' && Number.isSafeInteger(total) && total > 0 ? total : 0
    } catch {
      if (current()) unread.value = 0
    } finally {
      if (request === pending) request = undefined
    }
  }

  if (enabled) {
    function pageHide() {
      suspended = true
      reset()
    }

    function pageShow() {
      suspended = false
      void refresh()
    }

    watch([
      () => user.operationVersion,
      () => user.webGeneration,
      () => user.sessionId,
      () => Boolean(user.accessToken),
      () => user.clearing,
      () => user.clearError,
    ],
      () => {
        reset()
        if (!suspended) void nextTick(refresh)
      }, { flush: 'sync' })

    onMounted(() => {
      window.addEventListener('focus', refresh)
      window.addEventListener('pageshow', pageShow)
      window.addEventListener('pagehide', pageHide)
      document.addEventListener('visibilitychange', refresh)
      timer = setInterval(refresh, POLL_MS)
      pageShow()
    })
    onUnmounted(() => {
      pageHide()
      clearInterval(timer)
      window.removeEventListener('focus', refresh)
      window.removeEventListener('pageshow', pageShow)
      window.removeEventListener('pagehide', pageHide)
      document.removeEventListener('visibilitychange', refresh)
    })
  }

  return unread
}
