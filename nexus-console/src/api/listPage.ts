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
