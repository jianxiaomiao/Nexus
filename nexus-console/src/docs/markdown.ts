import MarkdownIt from 'markdown-it'

export interface TocItem {
  id: string
  text: string
  level: number
}

export interface MarkdownPart {
  kind: 'html' | 'table' | 'code'
  html: string
}

const parser = new MarkdownIt({ html: false, linkify: false, typographer: true })

function headingId(text: string): string {
  return text.toLowerCase().trim().replace(/[^\p{L}\p{N}]+/gu, '-').replace(/^-|-$/g, '') || 'section'
}

export function renderMarkdown(source: string): { parts: MarkdownPart[]; toc: TocItem[] } {
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

  const parts: MarkdownPart[] = []
  let start = 0
  const appendHtml = (end: number) => {
    if (end > start) parts.push({ kind: 'html', html: parser.renderer.render(tokens.slice(start, end), parser.options, {}) })
  }
  for (let index = 0; index < tokens.length; index++) {
    const token = tokens[index]
    if (token?.type !== 'fence' && token?.type !== 'table_open') continue
    appendHtml(index)
    let end = index + 1
    if (token.type === 'table_open') {
      while (end < tokens.length && tokens[end]?.type !== 'table_close') end++
      end++
    }
    parts.push({ kind: token.type === 'fence' ? 'code' : 'table',
      html: parser.renderer.render(tokens.slice(index, end), parser.options, {}) })
    start = end
    index = end - 1
  }
  appendHtml(tokens.length)
  return { parts, toc }
}
