type Entry = { publicId: string; target: string; key: string; settled: boolean }
const name = 'nexus.screenshot.submissions.v1'
const lifetime = 7 * 86400000

/** A rejected retry may still refer to an earlier committed operation; never replace its marker silently. */
export function screenshotSubmissionNeedsReconcile(status: number, validSuccess: boolean): boolean {
  return (
    !validSuccess &&
    (status >= 500 || (status >= 200 && status < 300) || [408, 409, 410, 429].includes(status))
  )
}

// Web Locks serialize localStorage read/modify/write across tabs. A browser without
// this primitive must not silently claim that a durable retry marker was saved safely.
function journal<T>(work: () => T): Promise<T> {
  if (typeof window === 'undefined') return Promise.resolve().then(work) // isolated Node fixtures
  if (typeof navigator !== 'undefined' && navigator.locks) {
    return navigator.locks.request(name, work)
  }
  return Promise.reject(new Error('当前环境不支持截图提交记录协调，请使用受支持的客户端。'))
}

/** Only public identity, a target digest and random operation ID are persisted, never the Key or URL. */
export async function prepareScreenshotSubmission(
  apiKey: string,
  url: string,
  storage: Storage = localStorage,
) {
  const publicId = /^nxk_v1_([0-9a-f-]{36})_/.exec(apiKey)?.[1] ?? 'unverified'
  const bytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(url))
  const target = Array.from(new Uint8Array(bytes), (byte) =>
    byte.toString(16).padStart(2, '0'),
  ).join('')
  return journal(() => {
    const raw: unknown = JSON.parse(storage.getItem(name) ?? '[]')
    if (
      !Array.isArray(raw) ||
      raw.some(
        (row) =>
          !row ||
          typeof row.publicId !== 'string' ||
          typeof row.target !== 'string' ||
          !/^\d{13}\.[0-9a-f-]{36}$/.test(row.key) ||
          typeof row.settled !== 'boolean',
      )
    ) {
      throw new Error('无法核对上次截图提交，请保留现场并检查本机存储。')
    }
    const entries: Entry[] = raw
    let entry = entries.find(
      (row) => row.publicId === publicId && row.target === target && !row.settled,
    )
    if (entry && Date.now() - Number(entry.key.slice(0, 13)) >= lifetime) {
      throw new Error('上次提交已超过七天恢复期，请核对任务记录后明确开始新任务。')
    }
    if (!entry) {
      // Keep unresolved markers; never silently erase an unknown outcome to make a retry possible.
      const retained = entries.filter((row) => !row.settled)
      if (retained.length >= 100) throw new Error('待核对的截图提交过多，请先处理已有任务。')
      entry = { publicId, target, key: `${Date.now()}.${crypto.randomUUID()}`, settled: false }
      entries.splice(0, entries.length, ...retained, entry)
    }
    storage.setItem(name, JSON.stringify(entries)) // durable before dispatch
    const key = entry.key
    return {
      key,
      settle() {
        return journal(() => {
          const latest: Entry[] = JSON.parse(storage.getItem(name) ?? '[]')
          storage.setItem(
            name,
            JSON.stringify(
              latest.map((row) => (row.key === key ? { ...row, settled: true } : row)),
            ),
          )
        })
      },
    }
  })
}

/** Only called after an explicit warning that the previous request may have consumed quota. */
export async function abandonScreenshotSubmission(
  apiKey: string,
  url: string,
  storage: Storage = localStorage,
) {
  const publicId = /^nxk_v1_([0-9a-f-]{36})_/.exec(apiKey)?.[1] ?? 'unverified'
  const bytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(url))
  const target = Array.from(new Uint8Array(bytes), (byte) =>
    byte.toString(16).padStart(2, '0'),
  ).join('')
  return journal(() => {
    const entries: Entry[] = JSON.parse(storage.getItem(name) ?? '[]')
    if (!Array.isArray(entries)) throw new Error('无法读取本机提交记录')
    storage.setItem(
      name,
      JSON.stringify(
        entries.map((row) =>
          row.publicId === publicId && row.target === target ? { ...row, settled: true } : row,
        ),
      ),
    )
  })
}
