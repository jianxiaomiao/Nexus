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
    resultImage: Blob | null       // 初始 null
    resultJobId: string | null     // 初始 null
    resultLoading: boolean        // 初始 false
    resultError: string | null     // 初始 null
    resultCacheControl: string | null // 初始 null
    resultRequestVersion: number   // 初始 0
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
    resultImage: null,       // 初始 null
    resultJobId: null,     // 初始 null
    resultLoading: false,        // 初始 false
    resultError: null,     // 初始 null
    resultCacheControl: null, // 初始 null
    resultRequestVersion: 0   // 初始 0
  }),


  actions: {
    applySnapshot(next: Trace) {
        if (next.jobId !== this.snapshot.jobId) {
          this.resultRequestVersion++
          this.resultImage = null
          this.resultJobId = null
          this.resultError = null
          this.resultCacheControl = null
          this.resultLoading = false
        }

        // 每次推送都要更新；清理图片只在任务变化时执行
        this.snapshot = next

        if (
          next.latestJob?.status === 'SUCCEEDED' &&
          this.resultImage === null &&
          this.resultError === null &&
          !this.resultLoading
        ) {
          void this.loadScreenshotResult()
        }
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
    },

    async loadScreenshotResult(){
      const desktop = window.nexusDesktop
      if (!desktop) {
        this.resultError = '桌面功能不可用'
        return
      }

      const jobId = this.snapshot.jobId
      const job = this.snapshot.latestJob

      if (!jobId || !job || job.status !== 'SUCCEEDED' || job.jobId !== jobId) {
        return
      }

      if(this.resultLoading || this.resultImage !== null) return

      const version = ++this.resultRequestVersion
      this.resultLoading = true
      this.resultError = null

      try {
        const result = await desktop.getScreenshotResult(jobId)

        if (version !== this.resultRequestVersion || this.snapshot.jobId !== jobId) return


        if(result.status === 'failed'){
          this.resultError = result.code
          return
        }else{
          if (result.jobId !== jobId) {
            this.resultError = '返回的截图与当前任务不匹配'
            return
          }
          this.resultImage = new Blob([result.bytes], { type: 'image/png' },)
          this.resultJobId = jobId
          this.resultCacheControl = result.cacheControl
        }
      }catch {
        if (
          version === this.resultRequestVersion &&
          this.snapshot.jobId === jobId
        ) {
          this.resultError = '获取截图失败，请稍后重试'
        }
      }finally {
        if (version === this.resultRequestVersion && this.snapshot.jobId === jobId)
          this.resultLoading = false
      }



    }
  },
})
