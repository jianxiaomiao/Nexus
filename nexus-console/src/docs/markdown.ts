import MarkdownIt from 'markdown-it'

export interface TocItem {
  id: string
  text: string
  level: number
}

const parser = new MarkdownIt({ html: false, linkify: false, typographer: true })
const defaultFence = parser.renderer.rules.fence
parser.renderer.rules.fence = (tokens, index, options, env, renderer) => {
  const code = defaultFence?.(tokens, index, options, env, renderer) ?? ''
  return `<div class="docs-code-block"><button class="docs-copy-button" type="button" aria-label="复制代码">复制代码</button>${code}</div>`
}
const defaultTableOpen = parser.renderer.rules.table_open
const defaultTableClose = parser.renderer.rules.table_close
parser.renderer.rules.table_open = (tokens, index, options, env, renderer) =>
  `<div class="docs-table-scroll">${defaultTableOpen?.(tokens, index, options, env, renderer) ?? '<table>'}`
parser.renderer.rules.table_close = (tokens, index, options, env, renderer) =>
  `${defaultTableClose?.(tokens, index, options, env, renderer) ?? '</table>'}</div>`

function headingId(text: string): string {
  return text.toLowerCase().trim().replace(/[^\p{L}\p{N}]+/gu, '-').replace(/^-|-$/g, '') || 'section'
}

export function renderMarkdown(source: string): { html: string; toc: TocItem[] } {
  const tokens = parser.parse(source, {})
  const toc: TocItem[] = []
  const ids = new Map<string, number>()

  for (let index = 0; index < tokens.length - 1; index++) {
    const token = tokens[index]
    if (!token || token.type !== 'heading_open') continue
    const inline = tokens[index + 1]
    if (!inline || inline.type !== 'inline') continue
    const text = inline.children?.filter((child) => child.type === 'text' || child.type === 'code_inline').map((child) => child.content).join('') || inline.content
    const base = headingId(text)
    const count = ids.get(base) ?? 0
    ids.set(base, count + 1)
    const id = count ? `${base}-${count + 1}` : base
    token.attrSet('id', id)
    const level = Number(token.tag.slice(1))
    if (level <= 3) toc.push({ id, text, level })
  }

  return { html: parser.renderer.render(tokens, parser.options, {}), toc }
}
