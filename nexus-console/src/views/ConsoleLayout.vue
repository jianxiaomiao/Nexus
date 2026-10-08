<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import type { ScrollbarInstance } from 'element-plus'
import { useUserStore } from '@/stores/userStore'
import { Close, Document, Expand, Fold, Grid, Link, Monitor, SwitchButton } from '@element-plus/icons-vue'
import brandSpark from '@/assets/icons/brand-spark.svg'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const collapsed = ref(false)
const mobileMenuOpen = ref(false)
const scrollArea = ref<ScrollbarInstance | null>(null)

watch(() => route.fullPath, () => { mobileMenuOpen.value = false })
watch(() => route.path, async () => {
  await nextTick()
  scrollArea.value?.setScrollTop(0)
})

function closeMenuOnEscape(event: KeyboardEvent) {
  if (event.key === 'Escape') mobileMenuOpen.value = false
}

onMounted(() => window.addEventListener('keydown', closeMenuOnEscape))
onUnmounted(() => window.removeEventListener('keydown', closeMenuOnEscape))

function logout() {
  userStore.clearSession()
  void router.replace({ name: 'login' })
}
</script>

<template>
  <div class="console-shell">
    <aside class="console-sidebar" :class="{ 'is-collapsed': collapsed, 'is-mobile-open': mobileMenuOpen }" aria-label="控制台导航">
      <RouterLink class="console-brand" :to="{ name: 'applications' }" aria-label="Nexus 控制台首页">
        <img class="brand-spark" :src="brandSpark" alt="" aria-hidden="true" />
        <span>Nexus</span>
      </RouterLink>

      <button
        class="mobile-menu-button"
        type="button"
        aria-controls="console-primary-nav"
        :aria-expanded="mobileMenuOpen"
        :aria-label="mobileMenuOpen ? '关闭导航' : '打开导航'"
        @click="mobileMenuOpen = !mobileMenuOpen"
      >
        <el-icon v-if="mobileMenuOpen" :size="22" aria-hidden="true"><Close /></el-icon>
        <span v-else class="menu-bars" aria-hidden="true"><span /><span /><span /></span>
      </button>

      <nav id="console-primary-nav" class="console-nav" aria-label="主导航">
        <RouterLink class="nav-item" :to="{ name: 'applications' }" active-class="is-active" aria-label="Applications" @click="mobileMenuOpen = false">
          <el-icon class="nav-icon" :size="20" aria-hidden="true"><Grid /></el-icon>
          <span>Applications</span>
        </RouterLink>
        <RouterLink class="nav-item" :to="{ name: 'docs-home' }" aria-label="开发文档" @click="mobileMenuOpen = false">
          <el-icon class="nav-icon" :size="20" aria-hidden="true"><Document /></el-icon>
          <span>开发文档</span>
        </RouterLink>
        <RouterLink class="nav-item" :to="{ name: 'short-links' }" aria-label="短链接" @click="mobileMenuOpen = false">
          <el-icon class="nav-icon" :size="20" aria-hidden="true"><Link /></el-icon>
          <span>短链接</span>
        </RouterLink>
        <RouterLink class="nav-item" :to="{ name: 'playground' }" aria-label="API 调试台" @click="mobileMenuOpen = false">
          <el-icon class="nav-icon" :size="20" aria-hidden="true"><Monitor /></el-icon>
          <span>API 调试台</span>
        </RouterLink>
      </nav>

      <div class="sidebar-bottom">
        <button class="collapse-button" type="button" :aria-label="collapsed ? '展开导航' : '收起导航'" @click="collapsed = !collapsed">
          <el-icon :size="18" aria-hidden="true"><Expand v-if="collapsed" /><Fold v-else /></el-icon>
          <span>收起导航</span>
        </button>
      </div>
    </aside>

    <button v-if="mobileMenuOpen" class="mobile-menu-backdrop" type="button" aria-label="关闭导航" @click="mobileMenuOpen = false" />

    <div class="console-main">
      <header class="console-header">
        <div class="header-context">
          <span class="header-caption">Nexus / 开发者控制台</span>
          <div class="header-breadcrumb">
            <RouterLink v-if="route.name === 'application-detail' || route.name === 'api-key-detail'" :to="{ name: 'applications' }">Applications</RouterLink>
            <strong v-else-if="route.name === 'playground'" aria-current="page">API 调试台</strong>
            <strong v-else-if="route.name === 'short-links'" aria-current="page">短链接</strong>
            <strong v-else>Applications</strong>
            <span v-if="route.name === 'application-detail'" aria-current="page">/ 应用详情</span>
            <template v-else-if="route.name === 'api-key-detail'">
              <RouterLink :to="{ name: 'application-detail', params: { id: route.params.applicationId }, query: { tab: 'keys' } }">/ API Keys</RouterLink>
              <span aria-current="page">/ Key 详情</span>
            </template>
          </div>
        </div>
        <el-button text class="logout-button" @click="logout">
          <el-icon :size="18" aria-hidden="true"><SwitchButton /></el-icon>
          <span>退出登录</span>
        </el-button>
      </header>

      <el-scrollbar ref="scrollArea" class="console-scroll-area" aria-label="控制台内容" :tabindex="0">
        <main class="console-content">
          <RouterView />
        </main>
      </el-scrollbar>
    </div>
  </div>
</template>

<style scoped>
.console-shell {
  display: flex;
  height: 100vh;
  height: 100dvh;
  overflow: hidden;
  background: var(--nexus-paper);
}
.console-sidebar {
  display: flex;
  flex: 0 0 248px;
  flex-direction: column;
  min-height: 0;
  padding: 34px 18px 22px;
  border-right: 1px solid var(--nexus-line);
  background: linear-gradient(160deg, #f1f4ed, var(--nexus-sage));
  transition: flex-basis .2s ease;
}
.console-sidebar.is-collapsed { flex-basis: 78px; }
.console-sidebar.is-collapsed .console-brand span,
.console-sidebar.is-collapsed .nav-item span,
.console-sidebar.is-collapsed .collapse-button span { display: none; }
.console-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 14px;
  color: var(--nexus-ink);
  font-family: Georgia, 'Times New Roman', serif;
  font-size: 27px;
  font-weight: 700;
  text-decoration: none;
  white-space: nowrap;
}
.brand-spark { width: 24px; height: 24px; }
.console-nav { display: grid; gap: 8px; margin-top: 58px; }
.nav-item {
  display: flex;
  align-items: center;
  min-height: 48px;
  gap: 12px;
  padding: 0 14px;
  border-left: 3px solid transparent;
  border-radius: 10px;
  color: #4f6a60;
  font-size: 15px;
  font-weight: 600;
  text-decoration: none;
  white-space: nowrap;
}
.nav-item:hover { background: #e4ece3; }
.nav-item.is-active, .nav-item.router-link-active {
  border-left-color: var(--nexus-teal);
  background: #dfeae1;
  color: #17685e;
}
.nav-icon { flex: none; }
.sidebar-bottom { margin-top: auto; }
.collapse-button {
  display: flex;
  align-items: center;
  width: 100%;
  min-height: 42px;
  gap: 12px;
  padding: 0 14px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: #526a60;
  cursor: pointer;
  font: inherit;
  font-size: 13px;
  text-align: left;
}
.collapse-button:hover { background: #e4ece3; }
.mobile-menu-button, .mobile-menu-backdrop { display: none; }
.console-main { display: flex; min-width: 0; min-height: 0; flex: 1; flex-direction: column; }
.console-header {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: space-between;
  min-height: 84px;
  gap: 24px;
  padding: 16px clamp(24px, 3.5vw, 56px);
  border-bottom: 1px solid var(--nexus-line);
  background: var(--nexus-surface);
}
.header-context { display: grid; gap: 4px; }
.header-caption { color: #809087; font-size: 12px; }
.header-breadcrumb { display: flex; gap: 8px; color: var(--nexus-ink); font-size: 15px; }
.header-breadcrumb a { color: var(--nexus-muted); text-decoration: none; }
.header-breadcrumb a:hover { color: var(--nexus-teal); text-decoration: underline; }
.header-breadcrumb strong { font-weight: 650; }
.logout-button { display: inline-flex; gap: 6px; color: #526a60; }
.console-scroll-area { min-height: 0; flex: 1; }
.console-scroll-area :deep(.el-scrollbar__wrap) { overscroll-behavior-y: contain; }
.console-content {
  width: 100%;
  max-width: 1600px;
  margin: 0 auto;
  padding: clamp(30px, 4vw, 58px) clamp(24px, 3.5vw, 56px) 72px;
}
@media (max-width: 700px) {
  .console-shell { flex-direction: column; }
  .console-sidebar, .console-sidebar.is-collapsed {
    position: relative;
    z-index: 30;
    flex: 0 0 64px;
    flex-direction: row;
    align-items: center;
    justify-content: space-between;
    min-height: 64px;
    padding: 10px 18px;
    border-right: 0;
    border-bottom: 1px solid var(--nexus-line);
  }
  .console-brand { padding: 0; font-size: 23px; }
  .console-sidebar.is-collapsed .console-brand span,
  .console-sidebar.is-collapsed .nav-item span { display: inline; }
  .mobile-menu-button {
    display: grid;
    width: 42px;
    height: 42px;
    place-items: center;
    border: 1px solid var(--nexus-line);
    border-radius: 10px;
    background: var(--nexus-surface);
    color: var(--nexus-ink);
    cursor: pointer;
  }
  .menu-bars { display: grid; gap: 4px; }
  .menu-bars span { display: block; width: 18px; height: 2px; border-radius: 2px; background: currentColor; }
  .console-nav {
    display: none;
    position: absolute;
    top: calc(100% + 8px);
    right: 12px;
    left: 12px;
    margin: 0;
    padding: 8px;
    border: 1px solid var(--nexus-line);
    border-radius: 14px;
    background: var(--nexus-surface);
    box-shadow: var(--nexus-card-shadow);
  }
  .console-sidebar.is-mobile-open .console-nav { display: grid; }
  .mobile-menu-backdrop {
    display: block;
    position: fixed;
    z-index: 20;
    inset: 64px 0 0;
    width: 100%;
    border: 0;
    background: rgba(32, 59, 55, .18);
    cursor: default;
  }
  .sidebar-bottom { display: none; }
  .console-header { min-height: 68px; padding: 12px 18px; }
  .console-content { padding: 28px 18px 64px; }
}
</style>
