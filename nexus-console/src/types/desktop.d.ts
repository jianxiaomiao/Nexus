import type { Trace } from '../api/Trace'

export type SaveScreenshotResult =
  | { status: 'saved' }
  | { status: 'cancelled' }
  | {
  status: 'failed'
  code: 'INVALID_IMAGE' | 'FORBIDDEN' | 'BUSY' | 'SAVE_FAILED'
}

export type OpenShortLinkResult =
  | { status: 'opened' }
  | { status: 'failed'; code: 'FORBIDDEN' | 'INVALID_REQUEST' | 'OPEN_FAILED' }

export interface NexusDesktop {
  openShortLink?(shortCode: string): Promise<OpenShortLinkResult>

  saveScreenshot(bytes: ArrayBuffer): Promise<SaveScreenshotResult>

  startScreenshotTracking(
    input: StartScreenshotTrackingInput,
  ): Promise<StartScreenshotTrackingResult>

  readScreenshotTracking(): Promise<ReadScreenshotTrackingResult>

  stopScreenshotTracking(): Promise<StopScreenshotTrackingResult>

  onScreenshotTrackingUpdated(
    listener: ScreenshotTrackingListener,
  ): () => void

  getScreenshotResult(jobId: string): Promise<GetScreenshotResult>
}

declare global {
  interface Window {
    nexusDesktop?: NexusDesktop
  }
}

export interface StartScreenshotTrackingInput {
  jobId: string
  apiKey: string
}

export type StartScreenshotTrackingResult =
  | { status: 'ok'; snapshot: Trace }
  | {
  status: 'failed'
  code: 'INVALID_REQUEST' | 'FORBIDDEN' | 'BUSY'
}

export type ReadScreenshotTrackingResult =
  | { status: 'ok'; snapshot: Trace }
  | { status: 'failed'; code: 'FORBIDDEN' }

export type StopScreenshotTrackingResult =
  | { status: 'ok'; snapshot: Trace }
  | { status: 'failed'; code: 'FORBIDDEN' }

export type ScreenshotTrackingListener = (snapshot: Trace) => void

export type GetScreenshotResult =
  | {
  status: 'ok'
  jobId: string
  bytes: ArrayBuffer
  cacheControl: string | null
}
  | {
  status: 'failed'
  code:
    | 'FORBIDDEN'
    | 'INVALID_REQUEST'
    | 'NOT_READY'
    | 'STALE_TASK'
    | 'BUSY'
    | 'FETCH_FAILED'
    | 'INVALID_IMAGE'
}
