<script setup lang="ts">
import { computed, inject, nextTick, onMounted, onUnmounted, ref, watch, type Ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import type { ScrollbarInstance } from 'element-plus'
import { ArrowLeft, ArrowRight } from '@element-plus/icons-vue'
import { docs, findDoc } from '@/docs/catalog'
import { renderMarkdown } from '@/docs/markdown'

const route = useRoute()
const articleBody = ref<HTMLElement | null>(null)
const docsScrollbar = inject<Ref<ScrollbarInstance | null>>('docs-scrollbar')
let disposed = false
const activeSection = ref('')
const doc = computed(() => findDoc(String(route.params.slug)))
const rendered = computed(() => doc.value ? renderMarkdown(doc.value.markdown) : null)
const docIndex = computed(() => docs.findIndex((item) => item.slug === doc.value?.slug))
const previousDoc = computed(() => docs[docIndex.value - 1])
const nextDoc = computed(() => docs[docIndex.value + 1])

function updateActiveSection() {
  const headings = articleBody.value?.querySelectorAll<HTMLElement>('h2[id], h3[id]')
  if (!headings?.length) return
  const marker = (docsScrollbar?.value?.wrapRef?.getBoundingClientRect().top ?? 0) + 140
  let current = headings[0]?.id ?? ''
  for (const heading of headings) {
    if (heading.getBoundingClientRect().top <= marker) current = heading.id
  }
  activeSection.value = current
}

async function copyCode(event: MouseEvent) {
  const target = event.target as HTMLElement
  const button = target.closest<HTMLButtonElement>('.docs-copy-button')
  if (!button || !articleBody.value?.contains(button)) return
  const content = button.closest('.docs-code-block')?.querySelector('code')?.textContent
  if (content == null) return
  try {
    await navigator.clipboard.writeText(content)
    button.textContent = '已复制'
    button.setAttribute('aria-label', '代码已复制')
    window.setTimeout(() => {
      button.textContent = '复制代码'
      button.setAttribute('aria-label', '复制代码')
    }, 1800)
  } catch {
    button.textContent = '复制失败'
    button.setAttribute('aria-label', '复制失败，请手动复制')
  }
}

watch(() => route.params.slug, async () => {
  await nextTick()
  docsScrollbar?.value?.setScrollTop(0)
  updateActiveSection()
})
onMounted(async () => {
  await nextTick()
  if (disposed) return
  updateActiveSection()
  docsScrollbar?.value?.wrapRef?.addEventListener('scroll', updateActiveSection, { passive: true })
})
onUnmounted(() => {
  disposed = true
  docsScrollbar?.value?.wrapRef?.removeEventListener('scroll', updateActiveSection)
})
</script>

<template>
  <div v-if="doc && rendered" class="docs-article-page docs-page">
    <div class="docs-article-grid">
      <div class="docs-article-main">
        <nav class="docs-crumb docs-article-crumb" aria-label="面包屑">
          <RouterLink :to="{ name: 'docs-home' }">文档首页</RouterLink><span aria-hidden="true">/</span><span>{{ doc.group }}</span><span aria-hidden="true">/</span><span aria-current="page">{{ doc.title }}</span>
        </nav>
        <header class="docs-article-header">
          <p class="docs-eyebrow">{{ doc.group }}</p>
          <h1>{{ doc.title }}</h1>
          <div v-if="doc.path" class="docs-endpoint"><span class="docs-method" :class="doc.method?.toLowerCase()">{{ doc.method }}</span><code>{{ doc.path }}</code></div>
          <p>{{ doc.summary }}</p>
          <RouterLink v-if="doc.path" class="docs-try-link" :to="{ name: 'playground', query: { endpoint: doc.slug } }">在线试用此接口 <el-icon aria-hidden="true"><ArrowRight /></el-icon></RouterLink>
        </header>
        <div ref="articleBody" class="docs-markdown" @click="copyCode">
          <template v-for="(part, index) in rendered.parts" :key="index">
            <div v-if="part.kind === 'html'" v-html="part.html" />
            <el-scrollbar v-else-if="part.kind === 'table'" class="docs-table-scroll"><div v-html="part.html" /></el-scrollbar>
            <div v-else class="docs-code-block">
              <button class="docs-copy-button" type="button" aria-label="复制代码">复制代码</button>
              <el-scrollbar class="docs-code-scroll"><div v-html="part.html" /></el-scrollbar>
            </div>
          </template>
        </div>
        <nav class="docs-article-footer" aria-label="相邻文档">
          <RouterLink v-if="previousDoc" :to="{ name: 'docs-article', params: { slug: previousDoc.slug } }"><el-icon aria-hidden="true"><ArrowLeft /></el-icon><span><small>上一篇</small><strong>{{ previousDoc.title }}</strong></span></RouterLink>
          <RouterLink v-else :to="{ name: 'docs-home' }"><el-icon aria-hidden="true"><ArrowLeft /></el-icon><span><small>返回</small><strong>文档首页</strong></span></RouterLink>
          <RouterLink v-if="nextDoc" :to="{ name: 'docs-article', params: { slug: nextDoc.slug } }"><span><small>下一篇</small><strong>{{ nextDoc.title }}</strong></span><el-icon aria-hidden="true"><ArrowRight /></el-icon></RouterLink>
        </nav>
      </div>
      <aside class="docs-toc" aria-label="本页目录"><el-scrollbar max-height="calc(100dvh - 140px)">
        <strong>本页目录</strong>
        <a v-for="item in rendered.toc" :key="item.id" :href="`#${item.id}`" :class="{ 'is-active': activeSection === item.id, 'is-nested': item.level === 3 }">{{ item.text }}</a>
      </el-scrollbar></aside>
    </div>
  </div>
  <div v-else class="docs-not-found docs-page">
    <h1>找不到这篇文档</h1>
    <p>这个地址暂时没有对应内容。</p>
    <RouterLink :to="{ name: 'docs-home' }">返回文档首页</RouterLink>
  </div>
</template>
