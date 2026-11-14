const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('nexusDesktop', {
    openShortLink: (shortCode) => {
        return ipcRenderer.invoke('short-link:open', shortCode)
    },

    saveScreenshot: (bytes) => {
        return ipcRenderer.invoke('screenshot:save', bytes)
    },
    startScreenshotTracking: (input) => {
        return ipcRenderer.invoke('screenshot-tracking:start', input)
    },

    readScreenshotTracking: () => {
        return ipcRenderer.invoke('screenshot-tracking:read')
    },

    stopScreenshotTracking: () => {
        return ipcRenderer.invoke('screenshot-tracking:stop')
    },

    onScreenshotTrackingUpdated: (listener) => {
        const handler = (_event, snapshot) => {
            listener(snapshot)
        }

        ipcRenderer.on('screenshot-tracking:updated', handler)

        return () => {
            ipcRenderer.removeListener('screenshot-tracking:updated', handler)
        }
    },

    getScreenshotResult: (jobId) => {
        return ipcRenderer.invoke('screenshot-result:get', jobId)
    },
})

