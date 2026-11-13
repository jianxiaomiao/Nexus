import type { ScreenshotJob } from './openApi'

export type TrackingStatus =
  | 'IDLE'
  | 'POLLING'
  | 'PAUSED'
  | 'TERMINAL'

export interface Trace {
  jobId: string | null
  latestJob: ScreenshotJob | null
  traceStatus: TrackingStatus
  queryFailedInfo: string | null
}
