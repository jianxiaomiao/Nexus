const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('nexusDesktop', {
    saveScreenshot: (bytes) => {
        return ipcRenderer.invoke('screenshot:save', bytes)
    },
})