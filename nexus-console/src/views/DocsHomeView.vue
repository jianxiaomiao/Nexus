<script setup lang="ts">
import type { Component } from 'vue'
import { RouterLink } from 'vue-router'
import { ArrowRight, DataAnalysis, DocumentCopy, HomeFilled, Key, Link, Promotion, Right, Warning } from '@element-plus/icons-vue'
import heroIllustration from '@/assets/docs-hero.png'
import { docs, type DocSlug } from '@/docs/catalog'

const cardIcons: Record<DocSlug, Component> = {
  'quick-start': Promotion,
  authentication: Key,
  uuid: DocumentCopy,
  hash: DataAnalysis,
  shortlink: Link,
  errors: Warning,
}
</script>

<template>
  <div class="docs-home docs-page">
    <p class="docs-crumb"><el-icon aria-hidden="true"><HomeFilled /></el-icon><span>文档首页</span></p>
    <section class="docs-hero" aria-labelledby="docs-hero-title">
      <div class="docs-hero-content">
        <p class="docs-eyebrow">NEXUS · DEVELOPER GUIDES</p>
        <h1 id="docs-hero-title">从第一条 API 调用开始</h1>
        <p class="docs-hero-subtitle">用 Nexus API Key 调用 UUID、Hash 与短链接开放能力。</p>
        <RouterLink :to="{ name: 'docs-article', params: { slug: 'quick-start' } }" class="docs-primary-action">
          阅读快速开始 <el-icon aria-hidden="true"><ArrowRight /></el-icon>
        </RouterLink>
      </div>
      <img class="docs-hero-image" :src="heroIllustration" alt="" aria-hidden="true" />
    </section>

    <ol class="docs-steps" aria-label="三步开始使用">
      <li><span class="docs-step-number">1</span><div><strong>创建 Application</strong><small>在控制台创建应用。</small></div></li>
      <li><span class="docs-step-number">2</span><div><strong>创建 API Key</strong><small>在应用详情中创建并保存完整 Key。</small></div></li>
      <li><span class="docs-step-number">3</span><div><strong>发送请求</strong><small>按照文档示例调用开放接口。</small></div></li>
    </ol>

    <section class="docs-library" aria-labelledby="docs-library-title">
      <div class="docs-section-heading">
        <h2 id="docs-library-title">按任务查阅</h2>
        <p>从以下文档开始，了解和使用 Nexus 当前提供的开放能力。</p>
      </div>
      <div class="docs-card-grid">
        <RouterLink v-for="doc in docs" :key="doc.slug" :to="{ name: 'docs-article', params: { slug: doc.slug } }" class="docs-card">
          <span class="docs-card-icon" aria-hidden="true"><el-icon><component :is="cardIcons[doc.slug]" /></el-icon></span>
          <span class="docs-card-copy"><strong>{{ doc.title }}</strong><small>{{ doc.summary }}</small></span>
          <el-icon class="docs-card-arrow" aria-hidden="true"><Right /></el-icon>
        </RouterLink>
      </div>
    </section>
  </div>
</template>
