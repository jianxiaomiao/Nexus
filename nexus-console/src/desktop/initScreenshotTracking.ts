import { watch } from 'vue'
import { useUserStore } from '@/stores/userStore'
import { useScreenshotTrackingStore } from '@/stores/screenshotTrackingStore'

export function initScreenshotTracking(): () => void {
  const desktop = window.nexusDesktop
  if (!desktop) return () => {}

  const store = useScreenshotTrackingStore()
  const user = useUserStore()
  const stopIdentity = watch(() => user.operationVersion, () => {
    pushVersion++
    store.resetForIdentityChange()
  }, { flush: 'sync' })

  let disposed = false
  let pushVersion = 0

  const unsubscribe = desktop.onScreenshotTrackingUpdated(snapshot => {
    if (disposed) return
    pushVersion++
    store.applySnapshot(snapshot)
  })

  const versionAtRead = pushVersion

  void desktop.readScreenshotTracking()
    .then(result => {
      if (disposed || pushVersion !== versionAtRead) return
      if (result.status === 'ok') {
        store.applySnapshot(result.snapshot)
      } else {
        console.warn('截图任务跟踪初始化失败')
      }
    })
    .catch(() => {
      if (!disposed && pushVersion === versionAtRead) {
        console.warn('截图任务跟踪初始化失败')
      }
    })

  return () => {
    if (disposed) return
    disposed = true
    stopIdentity()
    unsubscribe()
  }
}
