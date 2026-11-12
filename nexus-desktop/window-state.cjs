const fs = require('node:fs')
const path = require('node:path')

function loadWindowState(filePath) {
    try {
        // 1. 读取 UTF-8 文件
        const text = fs.readFileSync(filePath, 'utf8')
        // 2. 解析 JSON
        const value = JSON.parse(text)
        // 3. 检查结构和字段
        if (
            value === null ||
            typeof value !== 'object' ||
            Array.isArray(value)
        ) {
            console.warn('窗口配置格式无效')
            return null
        }

        if (
            value.bounds === null ||
            typeof value.bounds !== 'object' ||
            Array.isArray(value.bounds)
        ) {
            console.warn('窗口位置和尺寸无效')
            return null
        }

        const { x, y, width, height } = value.bounds

        if (
            value.version !== 1 ||
            !Number.isInteger(x) ||
            !Number.isInteger(y) ||
            !Number.isInteger(width) ||
            !Number.isInteger(height) ||
            width < 800 ||
            height < 600 ||
            typeof value.maximized !== 'boolean'
        ) {
            console.warn('窗口配置字段无效')
            return null
        }

        return value
        // 4. 有效则返回配置，否则返回 null
    }catch (e) {
        if(e.code === "ENOENT"){
            return null
        }
        if (e instanceof SyntaxError) {
            console.warn('窗口配置不是有效的 JSON')
            return null
        }

        console.warn('读取窗口配置失败，使用默认窗口设置', e.code)
        return null
    }
}

function resolveWindowBounds(savedState, workAreas, primaryArea) {
    // 返回 { x, y, width, height }
    if (savedState != null) {
        const bounds = savedState.bounds

        const fits = workAreas.some(area => {
            return bounds.x >= area.x &&
                bounds.y >= area.y &&
                bounds.x + bounds.width <= area.x + area.width &&
                bounds.y + bounds.height <= area.y + area.height
        })

        if (fits) {
            return bounds
        }
    }
    const width = Math.min(1280, primaryArea.width)
    const height = Math.min(860, primaryArea.height)

    const x = primaryArea.x + Math.floor((primaryArea.width - width) / 2)
    const y = primaryArea.y + Math.floor((primaryArea.height - height) / 2)

    return { x, y, width, height }
}

function saveWindowState(filePath, state) {
    // 保存成功返回 true，失败记录错误类别并返回 false
    try {
        fs.mkdirSync(path.dirname(filePath), { recursive: true })

        const tempPath = `${filePath}.tmp`
        fs.writeFileSync(tempPath, JSON.stringify(state, null, 2), 'utf8')
        fs.renameSync(tempPath, filePath)

        return true;
    }catch (e) {
        console.warn('窗口状态保存失败', e.code)
        return false
    }
}

module.exports = {
    loadWindowState,
    resolveWindowBounds,
    saveWindowState,
}