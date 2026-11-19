export interface ListPage<T> {
  total: number
  current: number
  size: number
  records: T[]
}

export interface ListPageQuery {
  current?: number
  size?: number
}

export function checkedListPage<T>(data: ListPage<T>): ListPage<T> {
  if (!data || !Array.isArray(data.records) || !Number.isSafeInteger(data.total) || data.total < 0 ||
      !Number.isSafeInteger(data.current) || data.current < 1 || !Number.isSafeInteger(data.size) || data.size < 1) {
    throw new Error('INVALID_LIST_RESPONSE')
  }
  return data
}
