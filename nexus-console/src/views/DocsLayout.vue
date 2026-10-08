<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, provide, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute } from 'vue-router'
import type { ScrollbarInstance } from 'element-plus'
import { ArrowLeft, Close, Document, HomeFilled, Menu, Monitor } from '@element-plus/icons-vue'
import brandSpark from '@/assets/icons/brand-spark.svg'
import { docGroups, docs } from '@/docs/catalog'
import '@/assets/docs.css'

const route = useRoute()
const mobileNavOpen = ref(false)
const mainScrollbar = ref<ScrollbarInstance | null>(null)
provide('docs-scrollbar', mainScrollbar)
watch(() => route.fullPath, () => { mobileNavOpen.value = false })
watch(() => route.path, async () => {
  await nextTick()
  mainScrollbar.value?.setScrollTop(0)
})

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') mobileNavOpen.value = false
}
onMounted(() => window.addEventListener('keydown', onKeydown))
onUnmounted(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div class="docs-shell">
    <header class="docs-topbar">
      <div class="docs-topbar-brand">
        <RouterLink :to="{ name: 'applications' }" class="docs-brand" aria-label="Nexus 文档首页">
          <img :src="brandSpark" alt="" aria-hidden="true" />
          <span>Nexus</span>
        </RouterLink>
        <span class="docs-brand-divider" aria-hidden="true" />
        <span class="docs-brand-label">开发文档</span>
      </div>
      <div class="docs-topbar-actions">
        <RouterLink :to="{ name: 'playground' }" class="docs-playground-link" aria-label="打开 API 调试台">
          <el-icon aria-hidden="true"><Monitor /></el-icon><span>API 调试台</span>
        </RouterLink>
        <RouterLink :to="{ name: 'applications' }" class="docs-console-link">
          <el-icon aria-hidden="true"><ArrowLeft /></el-icon>
          返回控制台
        </RouterLink>
        <button class="docs-mobile-toggle" type="button" aria-controls="docs-primary-nav" :aria-expanded="mobileNavOpen" :aria-label="mobileNavOpen ? '关闭文档目录' : '打开文档目录'" @click="mobileNavOpen = !mobileNavOpen">
          <el-icon aria-hidden="true"><Close v-if="mobileNavOpen" /><Menu v-else /></el-icon>
        </button>
      </div>
    </header>

    <div class="docs-frame">
      <aside id="docs-primary-nav" class="docs-sidebar" :class="{ 'is-open': mobileNavOpen }" aria-label="文档目录">
        <el-scrollbar class="docs-sidebar-scroll"><div class="docs-sidebar-content">
        <p class="docs-sidebar-title">文档目录</p>
        <RouterLink :to="{ name: 'docs-home' }" class="docs-nav-link docs-nav-home" exact-active-class="is-active" @click="mobileNavOpen = false">
          <el-icon aria-hidden="true"><HomeFilled /></el-icon>
          文档首页
        </RouterLink>
        <div v-for="group in docGroups" :key="group" class="docs-nav-group">
          <p>{{ group }}</p>
          <RouterLink v-for="doc in docs.filter((item) => item.group === group)" :key="doc.slug" :to="{ name: 'docs-article', params: { slug: doc.slug } }" class="docs-nav-link" exact-active-class="is-active" @click="mobileNavOpen = false">
            <el-icon aria-hidden="true"><Document /></el-icon>
            {{ doc.title }}
          </RouterLink>
        </div>
        </div></el-scrollbar>
      </aside>
      <button v-if="mobileNavOpen" class="docs-nav-backdrop" type="button" aria-label="关闭文档目录" @click="mobileNavOpen = false" />
      <main class="docs-main" id="main-content"><el-scrollbar ref="mainScrollbar" class="docs-main-scroll" :tabindex="0"><RouterView /></el-scrollbar></main>
    </div>
  </div>
</template>
