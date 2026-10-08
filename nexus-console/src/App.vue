<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import type { ScrollbarInstance } from 'element-plus'

const route = useRoute()
const authScrollbar = ref<ScrollbarInstance | null>(null)
watch(() => route.name, async () => {
  await nextTick()
  authScrollbar.value?.setScrollTop(0)
})
</script>

<template>
  <el-scrollbar v-if="route.name === 'login' || route.name === 'register'" ref="authScrollbar" class="auth-scroll" height="100dvh" :tabindex="0">
    <RouterView />
  </el-scrollbar>
  <RouterView v-else />
</template>
