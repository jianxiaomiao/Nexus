export type SaveScreenshotResult =
  | { status: 'saved' }
  | { status: 'cancelled' }
  | {
  status: 'failed'
  code: 'INVALID_IMAGE' | 'FORBIDDEN' | 'BUSY' | 'SAVE_FAILED'
}

export interface NexusDesktop {
  saveScreenshot(bytes: ArrayBuffer): Promise<SaveScreenshotResult>
}

declare global {
  interface Window {
    nexusDesktop?: NexusDesktop
  }
}
