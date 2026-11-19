/** A completion owns state only until a newer request or disposal. */
export function createRequestGate() {
  let version = 0
  let disposed = false
  return {
    begin() { const own = ++version; return () => !disposed && own === version },
    invalidate() { ++version },
    dispose() { disposed = true; ++version },
  }
}
