// 仅供测试启动：控制系统对话框的返回，不替换 IPC 处理器或文件写入。
const { app, dialog } = require('electron')
const path = require('node:path')
const dir = process.env.NEXUS_SAVE_BOUNDARY_DIR
if (!dir) throw Error('Start with test-save-boundaries.cjs')
const setPath = app.setPath.bind(app)
app.setPath = (name, value) => setPath(name, name === 'userData' ? path.join(dir, 'profile') : value)
const control = globalThis.__saveBoundaryControl = { calls: 0, release: null }
dialog.showSaveDialog = async () => {
  control.calls++
  if (control.calls === 1) return new Promise(resolve => { control.release = () => resolve({ canceled: true }) })
  if (control.calls === 2) return { canceled: false, filePath: dir } // 真实文件系统：目录不能作为文件写入。
  if (control.calls === 3) return { canceled: false, filePath: path.join(dir, 'recovered.png') }
  throw Error('Unexpected save dialog call')
}
require('../main.cjs')
