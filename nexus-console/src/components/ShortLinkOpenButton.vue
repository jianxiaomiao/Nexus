<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { publicShortLinkUrl } from '@/api/shortLinks'

const props = defineProps<{ shortCode: string }>()
const isDesktop = Boolean(window.nexusDesktop)
const opening = ref(false)

async function open() {
  if (opening.value) return
  const desktop = window.nexusDesktop
  if (!desktop?.openShortLink) {
    ElMessage.error('当前桌面版本不支持打开短链，请复制短地址后在浏览器中访问')
    return
  }
  opening.value = true
  try {
    const result = await desktop.openShortLink(props.shortCode)
    if (result.status === 'failed') {
      ElMessage.error('无法打开短地址，请复制后在浏览器中访问')
    }
  } catch {
    ElMessage.error('桌面通信失败，请稍后重试')
  } finally {
    opening.value = false
  }
}
</script>

<template>
  <el-button v-if="isDesktop" link :loading="opening" :disabled="opening" @click="open"><slot>打开短地址</slot></el-button>
  <a v-else :href="publicShortLinkUrl(shortCode)" target="_blank" rel="noopener noreferrer"><slot>打开短地址</slot></a>
</template>
