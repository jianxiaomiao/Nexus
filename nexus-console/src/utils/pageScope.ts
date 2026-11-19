import { onBeforeUnmount, watch } from 'vue'
import { useUserStore } from '@/stores/userStore'

/** Page lifetime and human identity; AT rotation deliberately does not invalidate it. */
export function usePageScope(resource: () => unknown = () => undefined, clear?: () => void) {
  const user = useUserStore()
  let alive = true
  let generation = 0
  const capture = () => {
    const identity = user.operationVersion
    const key = resource()
    const own = generation
    return () => alive && own === generation && identity === user.operationVersion && key === resource() && !user.clearing
  }
  watch([() => user.operationVersion, resource], () => { ++generation; clear?.() }, { flush: 'sync' })
  onBeforeUnmount(() => { alive = false; ++generation; clear?.() })
  return { capture, authenticated: () => !!user.accessToken && !user.clearing }
}
