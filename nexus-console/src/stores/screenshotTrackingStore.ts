import { defineStore } from 'pinia'
import type {Trace} from "@/api/Trace.ts";
import {type CreatedScreenshotJob, submitScreenshotJob} from '@/api/openApi'

export const useScreenshotTrackingStore = defineStore('ScreenshotTrackingStore', {
  state: (): {
    snapshot: Trace,
    submitting: boolean
    pendingHandoffJob: CreatedScreenshotJob | null
    submissionError: string | null
    creationUncertain: boolean
  } => ({
    snapshot: {
      jobId: null,
      latestJob: null,
      traceStatus: 'IDLE',
      queryFailedInfo: null,
    },
    submitting: false,
    pendingHandoffJob: null,
    submissionError: null,
    creationUncertain: false,
  }),


  actions: {
    applySnapshot(next: Trace) {
      this.snapshot = next
    },
    async submitDesktopScreenshot(apiKey: string, url: string) {
      if (this.submitting || this.pendingHandoffJob !== null || this.creationUncertain) return

      const desktop = window.nexusDesktop
      if (!desktop) {
        this.submissionError = '桌面功能不可用'
        return
      }

      this.submitting = true
      this.submissionError = null

      let creating = false
      try {
        const current = await desktop.readScreenshotTracking()
        if (current.status !== 'ok') {
          this.submissionError = '读取截图跟踪状态失败'
          return
        }
        if (current.snapshot.traceStatus !== 'IDLE') {
          this.submissionError = '已有截图任务，请先处理当前任务'
          return
        }

        creating = true
        const res = await submitScreenshotJob(apiKey, url)
        if (res.status < 200 || res.status >= 300 || res.data === null) {
          if (res.status >= 500 || (res.status >= 200 && res.status < 300)) {
            this.creationUncertain = true
            this.submissionError = '创建结果无法确认，请勿直接重复提交'
          } else {
            this.submissionError = '截图任务提交失败'
          }
          return
        }

        this.pendingHandoffJob = res.data

        const result = await desktop.startScreenshotTracking({ jobId: res.data.jobId, apiKey })

        if (result.status === 'ok') {
          this.pendingHandoffJob = null
        } else {
          this.submissionError = '任务已创建，接手未成功，请勿重复提交'
        }
      } catch {
        if (this.pendingHandoffJob !== null) {
          this.submissionError = '任务已创建，接手结果暂未确认，请勿重新提交'
        } else if (creating) {
          this.creationUncertain = true
          this.submissionError = '创建结果无法确认，请勿直接重复提交'
        } else {
          this.submissionError = '读取截图跟踪状态失败'
        }
      }finally {
        this.submitting = false
      }
    }
  },
})
