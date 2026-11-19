/** Bounds the entire exchange, including body consumption, even for an adapter ignoring abort. */
export async function withNetworkDeadline<T>(signal: AbortSignal | undefined, milliseconds: number,
  work: (signal: AbortSignal) => Promise<T>): Promise<T> {
  const controller = new AbortController()
  let timer: ReturnType<typeof setTimeout> | undefined
  let rejectStop: (reason: unknown) => void = () => {}
  const stopped = new Promise<never>((_, reject) => { rejectStop = reject })
  const abort = () => {
    controller.abort()
    rejectStop(new DOMException('请求已取消', 'AbortError'))
  }
  signal?.addEventListener('abort', abort, { once: true })
  try {
    if (signal?.aborted) abort()
    timer = setTimeout(() => {
      controller.abort()
      rejectStop(new DOMException('请求超时', 'TimeoutError'))
    }, milliseconds)
    return await Promise.race([stopped, Promise.resolve().then(() => {
      if (controller.signal.aborted) throw new DOMException('请求已取消', 'AbortError')
      return work(controller.signal)
    })])
  } finally {
    clearTimeout(timer)
    signal?.removeEventListener('abort', abort)
    controller.abort()
  }
}
