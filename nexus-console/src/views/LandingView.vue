<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowRight,
  Check,
  CopyDocument,
  Document,
  Key,
  Link,
  Monitor,
  Odometer,
  Share,
} from '@element-plus/icons-vue'
import brandSpark from '@/assets/icons/brand-spark.svg'
import { useUserStore } from '@/stores/userStore'

type ExampleKey = 'extract' | 'hash' | 'screenshot'

interface ApiExample {
  key: ExampleKey
  label: string
  endpoint: string
  description: string
  curl: string
  response: string
}

const userStore = useUserStore()
const signedIn = computed(() => Boolean(userStore.accessToken) && userStore.expiresAt > Date.now())

const examples: ApiExample[] = [
  {
    key: 'extract',
    label: '网页正文提取',
    endpoint: 'POST /v1/web/extract',
    description: '安全抓取目标网页并提取结构化标题、纯文本与清洗后的正文 HTML。',
    curl: `curl -X POST https://api.nexus.dev/v1/web/extract \\
  -H "Authorization: ApiKey nx_live_your_api_key" \\
  -H "Content-Type: application/json" \\
  -d '{"url": "https://example.com/article"}'`,
    response: `{
  "code": "SUCCESS",
  "message": "提取成功",
  "data": {
    "title": "Example Article",
    "textContent": "Clean extracted body text..."
  }
}`,
  },
  {
    key: 'screenshot',
    label: '异步网页截图',
    endpoint: 'POST /v1/web/screenshot/jobs',
    description: '基于无头 Chromium 的异步渲染任务，支持幂等提交与 Webhook 完成回调。',
    curl: `curl -X POST https://api.nexus.dev/v1/web/screenshot/jobs \\
  -H "Authorization: ApiKey nx_live_your_api_key" \\
  -H "Content-Type: application/json" \\
  -d '{"url": "https://example.com"}'`,
    response: `{
  "code": "SUCCESS",
  "message": "任务已创建",
  "data": {
    "jobId": "job_01J9X2K8M4...",
    "status": "QUEUED",
    "attemptCount": 0
  }
}`,
  },
  {
    key: 'hash',
    label: '哈希与工具集',
    endpoint: 'POST /v1/utils/hash',
    description: '开箱即用的轻量开发工具接口，统一纳入 API Key 鉴权与配额计量。',
    curl: `curl -X POST https://api.nexus.dev/v1/utils/hash \\
  -H "Authorization: ApiKey nx_live_your_api_key" \\
  -H "Content-Type: application/json" \\
  -d '{"algorithm": "SHA256", "value": "hello-nexus"}'`,
    response: `{
  "code": "SUCCESS",
  "message": "计算成功",
  "data": {
    "algorithm": "SHA256",
    "digest": "8f2a9c4b..."
  }
}`,
  },
]

const activeKey = ref<ExampleKey>('extract')
const copied = ref(false)
const activeExample = computed<ApiExample>(() => {
  return examples.find((item) => item.key === activeKey.value) ?? examples[0]!
})

async function copyCurl() {
  try {
    await navigator.clipboard.writeText(activeExample.value.curl)
    copied.value = true
    ElMessage.success('已复制示例命令')
    setTimeout(() => {
      copied.value = false
    }, 1800)
  } catch {
    ElMessage.warning('当前浏览器不支持自动复制，请手动复制')
  }
}

const capabilities = [
  {
    icon: Key,
    title: '应用与 API Key 治理',
    summary: '人机身份严格隔离，支持多应用管理、Key 一次性明文展示、平滑轮换与即时吊销。',
    tags: ['应用上限治理', 'Key 轮换', 'RBAC 后台'],
  },
  {
    icon: Odometer,
    title: '配额控制与用量分析',
    summary: '周期配额自动计量与拦截，按日趋势、按接口分布及细粒度调用事件一目了然。',
    tags: ['周期配额', '用量报表', '事件明细'],
  },
  {
    icon: Link,
    title: '开放 API 工具矩阵',
    summary: '内置短链接生成跳转、网页正文提取、异步静态网页截图与常用工具接口。',
    tags: ['短链接', '网页提取', '异步截图'],
  },
  {
    icon: Share,
    title: '可靠 Webhook 回调',
    summary: '基于 Outbox 模式的异步投递，支持 HMAC 签名验证、密钥双版本轮换与失败重试。',
    tags: ['Outbox', '签名校验', '密钥轮换'],
  },
]

const quickSteps = [
  {
    step: '01',
    title: '邮箱验证注册账号',
    detail: '通过 QQ / SMTP 邮箱验证码完成注册与邮箱核验，保障账号真实性。',
  },
  {
    step: '02',
    title: '创建 Application 与 Key',
    detail: '在控制台创建你的应用并签发 API Key，按需配置配额与 Webhook 回调地址。',
  },
  {
    step: '03',
    title: '在线调试或接入业务',
    detail: '直接在内置 API 调试台（Playground）体验，或复制 curl / 代码集成到你的服务端。',
  },
]
</script>

<template>
  <div class="landing-page">
    <div class="landing-bg" aria-hidden="true">
      <span class="orb orb-left" />
      <span class="orb orb-right" />
    </div>

    <header class="landing-header">
      <div class="header-inner">
        <RouterLink class="brand" :to="{ name: 'landing' }" aria-label="Nexus 首页">
          <img :src="brandSpark" alt="" aria-hidden="true" />
          <span>Nexus</span>
        </RouterLink>

        <nav class="header-nav" aria-label="主导航">
          <a href="#capabilities">核心能力</a>
          <a href="#quickstart">快速接入</a>
          <a href="#architecture">工程特性</a>
          <RouterLink :to="{ name: 'docs-home' }">开发文档</RouterLink>
        </nav>

        <div class="header-actions">
          <template v-if="signedIn">
            <RouterLink class="btn-primary" :to="{ name: 'applications' }">
              <span>进入控制台</span>
              <el-icon aria-hidden="true"><ArrowRight /></el-icon>
            </RouterLink>
          </template>
          <template v-else>
            <RouterLink class="btn-ghost" :to="{ name: 'login' }">登录</RouterLink>
            <RouterLink class="btn-primary" :to="{ name: 'register' }">
              <span>免费注册</span>
              <el-icon aria-hidden="true"><ArrowRight /></el-icon>
            </RouterLink>
          </template>
        </div>
      </div>
    </header>

    <main class="landing-main">
      <section class="hero-section">
        <div class="hero-copy">
          <p class="eyebrow">Developer API Platform · 面向开发者的开放能力平台</p>
          <h1>把常用开发基础设施，收进一个清晰可控的 API 空间</h1>
          <p class="hero-subtitle">
            Nexus 提供应用与 API Key 治理、配额与用量洞察、短链接、网页正文提取、异步网页截图及可靠 Webhook 回调，兼顾 Web、桌面端与移动端一致体验。
          </p>
          <div class="hero-cta">
            <RouterLink class="btn-primary btn-lg" :to="{ name: signedIn ? 'applications' : 'register' }">
              <span>{{ signedIn ? '进入开发者控制台' : '立即免费开始' }}</span>
              <el-icon aria-hidden="true"><ArrowRight /></el-icon>
            </RouterLink>
            <RouterLink class="btn-outline btn-lg" :to="{ name: 'docs-home' }">
              <el-icon aria-hidden="true"><Document /></el-icon>
              <span>浏览开发文档</span>
            </RouterLink>
          </div>
          <ul class="hero-highlights">
            <li><el-icon aria-hidden="true"><Check /></el-icon> 人机身份严格分离（JWT / ApiKey）</li>
            <li><el-icon aria-hidden="true"><Check /></el-icon> 周期配额与多维用量统计</li>
            <li><el-icon aria-hidden="true"><Check /></el-icon> 内置在线 API 调试台</li>
          </ul>
        </div>

        <div class="hero-code-card" aria-label="API 调用示例">
          <div class="code-card-tabs" role="tablist">
            <button
              v-for="item in examples"
              :key="item.key"
              type="button"
              role="tab"
              :aria-selected="activeKey === item.key"
              :class="['tab-btn', { 'is-active': activeKey === item.key }]"
              @click="activeKey = item.key"
            >
              {{ item.label }}
            </button>
          </div>

          <div class="code-card-meta">
            <code>{{ activeExample.endpoint }}</code>
            <button type="button" class="copy-btn" @click="copyCurl">
              <el-icon aria-hidden="true"><Check v-if="copied" /><CopyDocument v-else /></el-icon>
              <span>{{ copied ? '已复制' : '复制' }}</span>
            </button>
          </div>

          <p class="code-card-desc">{{ activeExample.description }}</p>

          <pre class="code-block"><code>{{ activeExample.curl }}</code></pre>
          <div class="response-label">Response · 200 OK</div>
          <pre class="code-block response-block"><code>{{ activeExample.response }}</code></pre>
        </div>
      </section>

      <section id="capabilities" class="content-section">
        <div class="section-head">
          <p class="eyebrow">Core Capabilities</p>
          <h2>从凭证治理到开放能力，全链路开箱即用</h2>
          <p>每一项功能都围绕真实开发者工作流设计与测试，无冗余概念。</p>
        </div>
        <div class="capability-grid">
          <article v-for="cap in capabilities" :key="cap.title" class="capability-card">
            <div class="cap-icon">
              <el-icon :size="22" aria-hidden="true"><component :is="cap.icon" /></el-icon>
            </div>
            <h3>{{ cap.title }}</h3>
            <p>{{ cap.summary }}</p>
            <div class="cap-tags">
              <span v-for="tag in cap.tags" :key="tag">{{ tag }}</span>
            </div>
          </article>
        </div>
      </section>

      <section id="quickstart" class="content-section">
        <div class="section-head">
          <p class="eyebrow">Quick Start</p>
          <h2>三步完成接入</h2>
        </div>
        <div class="steps-grid">
          <article v-for="item in quickSteps" :key="item.step" class="step-card">
            <span class="step-num">{{ item.step }}</span>
            <h3>{{ item.title }}</h3>
            <p>{{ item.detail }}</p>
          </article>
        </div>
      </section>

      <section id="architecture" class="content-section architecture-section">
        <div class="arch-box">
          <div>
            <p class="eyebrow">Engineering & Multi-Platform</p>
            <h2>为长期可维护而打磨的工程底座</h2>
            <p class="arch-desc">
              后端基于 Java 21 + Spring Boot 4 + MySQL 8.4（Flyway 版本化迁移）构建模块化单体，严格保障并发计数、幂等提交、UTC 时间一致性与安全边界；前端覆盖 Web 控制台、Electron 桌面端与移动端。
            </p>
          </div>
          <div class="arch-actions">
            <RouterLink class="btn-primary" :to="{ name: signedIn ? 'playground' : 'register' }">
              <el-icon aria-hidden="true"><Monitor /></el-icon>
              <span>{{ signedIn ? '打开 API 调试台' : '创建账号并体验' }}</span>
            </RouterLink>
            <a class="btn-outline" href="https://github.com/jianxiaomiao/Nexus" target="_blank" rel="noopener noreferrer">
              查看 GitHub 仓库
            </a>
          </div>
        </div>
      </section>
    </main>

    <footer class="landing-footer">
      <div class="footer-inner">
        <div class="footer-brand">
          <img :src="brandSpark" alt="" aria-hidden="true" />
          <span>Nexus · Developer API Platform</span>
        </div>
        <div class="footer-links">
          <RouterLink :to="{ name: 'docs-home' }">开发文档</RouterLink>
          <RouterLink :to="{ name: 'login' }">登录</RouterLink>
          <RouterLink :to="{ name: 'register' }">注册</RouterLink>
          <a href="https://github.com/jianxiaomiao/Nexus" target="_blank" rel="noopener noreferrer">GitHub</a>
        </div>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.landing-page {
  position: relative;
  isolation: isolate;
  min-height: 100dvh;
  background: radial-gradient(ellipse at 24% 12%, #fffaf0 6%, transparent 56%),
    linear-gradient(135deg, #f8f2e5, #fbf7ee 55%, #f4ede0);
  color: var(--nexus-ink, #1f2421);
  display: flex;
  flex-direction: column;
}

.landing-bg {
  position: fixed;
  inset: 0;
  z-index: -1;
  overflow: hidden;
  pointer-events: none;
}

.orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(2px);
}

.orb-left {
  width: 520px;
  height: 520px;
  left: -180px;
  bottom: -220px;
  background: rgba(219, 226, 200, 0.55);
}

.orb-right {
  width: 440px;
  height: 440px;
  right: -140px;
  top: 120px;
  background: rgba(230, 232, 214, 0.55);
}

.landing-header {
  position: sticky;
  top: 0;
  z-index: 20;
  backdrop-filter: blur(12px);
  background: rgba(251, 247, 238, 0.84);
  border-bottom: 1px solid rgba(31, 36, 33, 0.08);
}

.header-inner {
  max-width: 1200px;
  margin: 0 auto;
  padding: 14px 24px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  text-decoration: none;
  color: inherit;
  font-weight: 750;
  font-size: 22px;
  letter-spacing: -0.04em;
}

.brand img {
  width: 22px;
  height: 22px;
}

.header-nav {
  display: flex;
  align-items: center;
  gap: 24px;
}

.header-nav a {
  text-decoration: none;
  color: var(--nexus-muted, #5c635e);
  font-size: 14px;
  font-weight: 500;
  transition: color 0.15s ease;
}

.header-nav a:hover {
  color: var(--nexus-ink, #1f2421);
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.btn-primary,
.btn-outline,
.btn-ghost {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border-radius: 10px;
  font-size: 14px;
  font-weight: 600;
  text-decoration: none;
  padding: 9px 16px;
  transition: all 0.15s ease;
}

.btn-lg {
  padding: 12px 20px;
  font-size: 15px;
}

.btn-primary {
  background: #247b70;
  color: #fff;
}

.btn-primary:hover {
  background: #1d645b;
}

.btn-outline {
  border: 1px solid rgba(31, 36, 33, 0.18);
  color: var(--nexus-ink, #1f2421);
  background: rgba(255, 255, 255, 0.65);
}

.btn-outline:hover {
  border-color: #247b70;
  color: #247b70;
}

.btn-ghost {
  color: var(--nexus-ink, #1f2421);
}

.btn-ghost:hover {
  background: rgba(31, 36, 33, 0.06);
}

.landing-main {
  flex: 1;
  max-width: 1200px;
  width: 100%;
  margin: 0 auto;
  padding: 56px 24px 72px;
  display: flex;
  flex-direction: column;
  gap: 88px;
}

.hero-section {
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(0, 0.95fr);
  gap: 40px;
  align-items: center;
}

.eyebrow {
  margin: 0 0 12px;
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: #247b70;
}

.hero-copy h1 {
  margin: 0 0 18px;
  font-size: clamp(32px, 4vw, 48px);
  line-height: 1.15;
  letter-spacing: -0.03em;
}

.hero-subtitle {
  margin: 0 0 28px;
  font-size: 16px;
  line-height: 1.7;
  color: var(--nexus-muted, #5c635e);
}

.hero-cta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 24px;
}

.hero-highlights {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 12px 20px;
  font-size: 13px;
  color: var(--nexus-muted, #5c635e);
}

.hero-highlights li {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.hero-code-card {
  background: #1b2220;
  color: #e6efe9;
  border-radius: 16px;
  padding: 18px;
  box-shadow: 0 20px 50px rgba(27, 34, 32, 0.16);
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}

.code-card-tabs {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.tab-btn {
  border: 0;
  background: rgba(255, 255, 255, 0.08);
  color: #c2d1cb;
  padding: 6px 12px;
  border-radius: 8px;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.tab-btn.is-active {
  background: #247b70;
  color: #fff;
}

.code-card-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  font-size: 13px;
  color: #93c5ba;
}

.copy-btn {
  border: 1px solid rgba(255, 255, 255, 0.18);
  background: transparent;
  color: #e6efe9;
  border-radius: 7px;
  padding: 4px 10px;
  font-size: 12px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
}

.copy-btn:hover {
  border-color: #93c5ba;
}

.code-card-desc {
  margin: 0;
  font-size: 13px;
  color: #b4c2bc;
  line-height: 1.5;
}

.code-block {
  margin: 0;
  padding: 12px 14px;
  border-radius: 10px;
  background: rgba(0, 0, 0, 0.28);
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12.5px;
  line-height: 1.55;
  overflow-x: auto;
  color: #edf7f3;
}

.response-label {
  font-size: 12px;
  color: #79b8a9;
  font-weight: 600;
}

.response-block {
  color: #bfe6da;
}

.content-section {
  display: flex;
  flex-direction: column;
  gap: 28px;
}

.section-head h2 {
  margin: 0 0 8px;
  font-size: clamp(24px, 3vw, 32px);
  letter-spacing: -0.02em;
}

.section-head p:last-child {
  margin: 0;
  color: var(--nexus-muted, #5c635e);
}

.capability-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20px;
}

.capability-card {
  background: rgba(255, 255, 255, 0.78);
  border: 1px solid rgba(31, 36, 33, 0.08);
  border-radius: 16px;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.cap-icon {
  width: 42px;
  height: 42px;
  border-radius: 10px;
  background: rgba(36, 123, 112, 0.12);
  color: #247b70;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.capability-card h3 {
  margin: 0;
  font-size: 18px;
}

.capability-card p {
  margin: 0;
  font-size: 14px;
  line-height: 1.65;
  color: var(--nexus-muted, #5c635e);
}

.cap-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: auto;
  padding-top: 4px;
}

.cap-tags span {
  font-size: 12px;
  padding: 3px 10px;
  border-radius: 999px;
  background: rgba(36, 123, 112, 0.09);
  color: #1f665d;
}

.steps-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 20px;
}

.step-card {
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid rgba(31, 36, 33, 0.08);
  border-radius: 16px;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.step-num {
  font-size: 28px;
  font-weight: 750;
  color: #247b70;
  letter-spacing: -0.04em;
}

.step-card h3 {
  margin: 0;
  font-size: 17px;
}

.step-card p {
  margin: 0;
  font-size: 14px;
  line-height: 1.6;
  color: var(--nexus-muted, #5c635e);
}

.arch-box {
  background: rgba(255, 255, 255, 0.85);
  border: 1px solid rgba(36, 123, 112, 0.22);
  border-radius: 20px;
  padding: 32px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 28px;
  flex-wrap: wrap;
}

.arch-box h2 {
  margin: 0 0 10px;
  font-size: 26px;
}

.arch-desc {
  margin: 0;
  max-width: 680px;
  font-size: 15px;
  line-height: 1.7;
  color: var(--nexus-muted, #5c635e);
}

.arch-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.landing-footer {
  border-top: 1px solid rgba(31, 36, 33, 0.08);
  background: rgba(251, 247, 238, 0.7);
}

.footer-inner {
  max-width: 1200px;
  margin: 0 auto;
  padding: 20px 24px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  font-size: 13px;
  color: var(--nexus-muted, #5c635e);
}

.footer-brand {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  color: var(--nexus-ink, #1f2421);
}

.footer-brand img {
  width: 18px;
  height: 18px;
}

.footer-links {
  display: flex;
  gap: 18px;
}

.footer-links a {
  color: inherit;
  text-decoration: none;
}

.footer-links a:hover {
  color: #247b70;
}

@media (max-width: 960px) {
  .hero-section {
    grid-template-columns: 1fr;
  }

  .header-nav {
    display: none;
  }

  .capability-grid,
  .steps-grid {
    grid-template-columns: 1fr;
  }
}
</style>
