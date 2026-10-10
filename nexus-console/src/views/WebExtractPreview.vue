<script setup lang="ts">
import { h, type VNode } from 'vue'
import { ElImage } from 'element-plus'

const props = defineProps<{ html: string }>()
const allowedTags = new Set(['p', 'div', 'span', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'ul', 'ol', 'li', 'blockquote', 'pre', 'code', 'b', 'strong', 'i', 'em', 'br', 'hr', 'small', 'sub', 'sup'])
const discardedTags = new Set(['script', 'style', 'iframe', 'object', 'embed', 'svg', 'math', 'template', 'form', 'input', 'button', 'link', 'meta', 'base'])

function renderNode(node: Node): (VNode | string)[] {
  if (node.nodeType === Node.TEXT_NODE) return [node.textContent ?? '']
  if (!(node instanceof HTMLElement)) return []
  const tag = node.tagName.toLowerCase()
  if (discardedTags.has(tag)) return []
  if (tag === 'img') {
    try {
      const url = new URL(node.getAttribute('src') ?? '')
      if (url.protocol !== 'https:' || url.username || url.password) return []
      // 只把 HTTPS 地址、替代文本交给组件；不复制外站事件、样式等属性。
      return [h(ElImage, {
        src: url.href, alt: node.getAttribute('alt') ?? '文章配图',
        referrerpolicy: 'no-referrer', class: 'playground-article-image', fit: 'contain',
      }, {
        error: () => h('div', { class: 'playground-image-error', role: 'status' }, '图片无法加载，可能受原站防盗链或网络限制。'),
      })]
    } catch { return [] }
  }
  const children = Array.from(node.childNodes).flatMap(renderNode)
  return allowedTags.has(tag) ? [h(tag, {}, children)] : children
}

function ArticleBody() {
  // template 内容保持惰性，不插入真实 DOM；逐个重建允许的元素，避免直接 v-html。
  const template = document.createElement('template')
  template.innerHTML = props.html
  return h('div', { class: 'playground-article' }, Array.from(template.content.childNodes).flatMap(renderNode))
}
</script>

<template>
  <ArticleBody />
</template>
