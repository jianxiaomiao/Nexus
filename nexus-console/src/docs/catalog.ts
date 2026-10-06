import quickStart from './content/quick-start.md?raw'
import authentication from './content/authentication.md?raw'
import uuid from './content/uuid.md?raw'
import hash from './content/hash.md?raw'
import errors from './content/errors.md?raw'

export type DocSlug = 'quick-start' | 'authentication' | 'uuid' | 'hash' | 'errors'

export interface DocPage {
  slug: DocSlug
  title: string
  group: '开始使用' | 'API 参考' | '帮助'
  summary: string
  method?: 'GET' | 'POST'
  path?: string
  markdown: string
}

export const docs: DocPage[] = [
  { slug: 'quick-start', title: '快速开始', group: '开始使用', summary: '创建应用与 Key，发送第一条 API 请求。', markdown: quickStart },
  { slug: 'authentication', title: 'API Key 与认证', group: '开始使用', summary: '理解管理端 JWT 与机器端 API Key 的边界。', markdown: authentication },
  { slug: 'uuid', title: '生成 UUID', group: 'API 参考', summary: '获取一个新生成的 UUID。', method: 'GET', path: '/v1/utils/uuid', markdown: uuid },
  { slug: 'hash', title: '计算 Hash', group: 'API 参考', summary: '计算文本的 SHA-256 或 SHA-512 摘要。', method: 'POST', path: '/v1/utils/hash', markdown: hash },
  { slug: 'errors', title: '错误码与排查', group: '帮助', summary: '区分认证、禁用和请求参数错误。', markdown: errors },
]

export const docGroups = ['开始使用', 'API 参考', '帮助'] as const

export function findDoc(slug: string): DocPage | undefined {
  return docs.find((doc) => doc.slug === slug)
}
