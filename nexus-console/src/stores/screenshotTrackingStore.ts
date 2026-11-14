import { defineStore } from 'pinia'
import type {Trace} from "@/api/Trace.ts";
import {type CreatedScreenshotJob, submitScreenshotJob} from '@/api/openApi'

const MAX_HANDOFF_RETRIES = 3

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

    clearing: boolean       // 初始 false
    clearError: string | null // 初始 null

    resuming: boolean        // 初始 false
    resumeError: string | null // 初始 null

    retryingHandoff: boolean // 初始 false
    handoffRetryCount: number // 初始 0
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
    resultRequestVersion: 0,   // 初始 0

    clearing: false,
    clearError: null,

    resuming: false,        // 初始 false
    resumeError: null, // 初始 null

    retryingHandoff: false, // 初始 false
    handoffRetryCount: 0, // 初始 0
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

      if (next.jobId !== this.snapshot.jobId || next.traceStatus !== 'PAUSED') {
        this.resumeError = null
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
      if (this.submitting || this.pendingHandoffJob !== null || this.creationUncertain || this.clearing || this.resuming || this.retryingHandoff) return

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
        this.handoffRetryCount = 0

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
      if (this.clearing) return

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
    },

    async clearCurrentTask(){
      if (this.clearing || this.submitting || this.resuming || this.retryingHandoff) return

      if(this.pendingHandoffJob !== null || this.creationUncertain === true){
        this.clearError = "保护中"
        return
      }

      if (this.snapshot.jobId === null) return

      const desktop = window.nexusDesktop

      if (typeof desktop?.stopScreenshotTracking !== 'function') {
        this.clearError = '桌面清理功能不可用'
        return
      }

      this.clearing = true
      this.clearError = null

      try{
        const result = await desktop.stopScreenshotTracking()

        if(result.status === 'failed'){
          this.clearError = '清理请求被拒绝，请重试'
        }
      }catch{
        const alreadyIdle =
          this.snapshot.jobId === null &&
          this.snapshot.traceStatus === 'IDLE'
        if(!alreadyIdle){
          this.clearError = '清理结果暂未确认，请查看当前状态后重试'
        }
      }finally {
        this.clearing = false
      }
    },

    async resumeCurrentTask(apiKey: string){
      if (this.resuming || this.submitting || this.clearing || this.retryingHandoff) return
      if (this.pendingHandoffJob !== null || this.creationUncertain) return

      const jobId = this.snapshot.jobId

      if (!jobId || this.snapshot.traceStatus !== 'PAUSED') return

      const key = apiKey.trim()

      if (!key) {
        this.resumeError = '请输入该任务使用的完整 API Key'
        return
      }

      const desktop = window.nexusDesktop

      if (typeof desktop?.startScreenshotTracking !== 'function') {
        this.resumeError = '桌面恢复查询功能不可用'
        return
      }

      this.resuming = true
      this.resumeError = null

      try {
        const result = await desktop.startScreenshotTracking({
          jobId,
          apiKey: key,
        })

        const stillPaused =
          this.snapshot.jobId === jobId &&
          this.snapshot.traceStatus === 'PAUSED'

        if(result.status === 'failed' && stillPaused){
          this.resumeError = '恢复查询请求未成功，请检查 API Key 后重试'
          return
        }
      }catch {
        if(this.snapshot.jobId === jobId && this.snapshot.traceStatus === 'PAUSED'){
          this.resumeError = '恢复结果暂未确认，请查看当前状态后重试'
        }
      }finally {
        this.resuming = false
      }
    },

    async retryPendingHandoff(apiKey: string){
      if (
        this.submitting ||
        this.clearing ||
        this.resuming ||
        this.retryingHandoff
      ) return



      const pending = this.pendingHandoffJob
      if (!pending) return

      if (this.creationUncertain) return

      const alreadyTracked =
        this.snapshot.jobId === pending.jobId &&
        this.snapshot.traceStatus !== 'IDLE'

      if(alreadyTracked){
        this.pendingHandoffJob = null
        this.submissionError = null
        this.handoffRetryCount = 0
        return
      }

      const desktop = window.nexusDesktop

      if (typeof desktop?.startScreenshotTracking !== 'function') {
        this.submissionError = '桌面重试接手功能不可用'
        return
      }

      if (this.handoffRetryCount >= MAX_HANDOFF_RETRIES) {
        this.submissionError = '接手重试次数已达上限，请保留 Job ID 并核对当前任务状态'
        return
      }

      const key = apiKey.trim()

      if (!key) {
        this.submissionError = '请输入该任务使用的完整 API Key'
        return
      }

      this.retryingHandoff = true
      this.submissionError = null
      this.handoffRetryCount++

      try {
        const result = await desktop.startScreenshotTracking({
          jobId: pending.jobId,
          apiKey: key,
        })

        if (this.pendingHandoffJob?.jobId !== pending.jobId) return

        const nowTracked =
          this.snapshot.jobId === pending.jobId &&
          this.snapshot.traceStatus !== 'IDLE'

        if(nowTracked){
          this.pendingHandoffJob = null
          this.submissionError = null
          this.handoffRetryCount = 0
          return
        }else if (result.status === 'ok'){
          this.pendingHandoffJob = null
          this.submissionError = null
          this.handoffRetryCount = 0
          return
        }else {
          const messages = {
            INVALID_REQUEST: '接手参数格式错误，请检查 API Key 后重试',
            FORBIDDEN: '桌面调用来源被拒绝，更换 API Key 无法解决',
            BUSY: 'Main 正在跟踪其他任务，请先核对当前任务状态',
          }

          this.submissionError = messages[result.code]
        }
      }catch {
        const nowTracked =
          this.snapshot.jobId === pending.jobId &&
          this.snapshot.traceStatus !== 'IDLE'

        if (this.pendingHandoffJob?.jobId !== pending.jobId) return

        if(nowTracked){
          this.pendingHandoffJob = null
          this.submissionError = null
          this.handoffRetryCount = 0
        }else{
          this.submissionError = "接手结果暂未确认，请勿重新创建"
        }
      }finally {
        this.retryingHandoff = false
      }
    }
  },
})
