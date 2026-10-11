<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { Download, ZoomIn } from '@element-plus/icons-vue'

const props = defineProps<{ image: Blob; blockedResources: number | null; resourceLimitReached: boolean; cacheControl: string | null }>()
const imageUrl = ref('')
const tab = ref('preview')
const expanded = ref(false)
const imageError = ref(false)
const dimensions = ref('1280 × 720')
const sizeLabel = computed(() => `${(props.image.size / 1024).toFixed(1)} KB`)

function releaseImage() {
  if (imageUrl.value) URL.revokeObjectURL(imageUrl.value)
  imageUrl.value = ''
}
watch(() => props.image, (image) => {
  expanded.value = false
  imageError.value = false
  tab.value = 'preview'
  dimensions.value = '1280 × 720'
  releaseImage()
  imageUrl.value = URL.createObjectURL(image)
}, { immediate: true, flush: 'sync' })
onBeforeUnmount(releaseImage)

function readDimensions(event: Event) {
  const image = event.target as HTMLImageElement
  dimensions.value = `${image.naturalWidth} × ${image.naturalHeight}`
}
function downloadImage() {
  if (!imageUrl.value || imageError.value) return
  const link = document.createElement('a')
  link.href = imageUrl.value
  link.download = 'nexus-screenshot.png'
  document.body.append(link)
  link.click()
  link.remove()
}
</script>

<template>
  <div class="playground-screenshot-result">
    <el-tabs v-model="tab" aria-label="截图结果格式">
      <el-tab-pane label="截图预览" name="preview">
        <el-alert v-if="resourceLimitReached || (blockedResources ?? 0) > 0"
          class="playground-screenshot-warning" type="warning" show-icon :closable="false"
          :title="resourceLimitReached ? '已返回部分截图' : '部分资源未加载'"
          :description="resourceLimitReached ? '已停止加载后续资源，部分图片或图标可能缺失。' : '部分资源被拒绝或获取失败，图片或样式可能缺失。'" />
        <el-scrollbar max-height="480px" class="playground-screenshot-scroll">
          <el-image class="playground-screenshot-image" :src="imageUrl" fit="contain" alt="网页截图预览"
            @load="readDimensions" @error="imageError = true">
            <template #error><div class="playground-image-error">图片无法解码，可能是返回的 PNG 已损坏，请重新生成截图。</div></template>
          </el-image>
        </el-scrollbar>
        <div class="playground-screenshot-footer">
          <span>{{ dimensions }} · PNG · {{ sizeLabel }}</span>
          <el-tag v-if="blockedResources !== null && blockedResources > 0" type="warning">{{ blockedResources }} 个资源未加载</el-tag>
          <div class="playground-screenshot-actions">
            <el-button :disabled="imageError" @click="expanded = true"><el-icon><ZoomIn /></el-icon>放大预览</el-button>
            <el-button type="primary" :disabled="imageError" @click="downloadImage"><el-icon><Download /></el-icon>下载 PNG</el-button>
          </div>
        </div>
        <p class="playground-screenshot-note">图片仅在当前页面保留；离开或重新请求后清除。</p>
      </el-tab-pane>
      <el-tab-pane label="响应信息" name="info" lazy>
        <el-descriptions :column="1" border class="playground-screenshot-info">
          <el-descriptions-item label="Content-Type">image/png</el-descriptions-item>
          <el-descriptions-item label="图片尺寸">{{ dimensions }}</el-descriptions-item>
          <el-descriptions-item label="文件大小">{{ sizeLabel }}</el-descriptions-item>
          <el-descriptions-item label="未加载资源">{{ blockedResources ?? '后端未提供' }}</el-descriptions-item>
          <el-descriptions-item label="请求次数上限">{{ resourceLimitReached ? '已触达，返回部分截图' : '未触达；不代表资源全部完整' }}</el-descriptions-item>
          <el-descriptions-item label="Cache-Control">{{ cacheControl ?? '后端未提供' }}</el-descriptions-item>
        </el-descriptions>
      </el-tab-pane>
    </el-tabs>
    <el-dialog v-model="expanded" title="网页截图大图预览" width="min(1440px, 94vw)" align-center append-to-body destroy-on-close>
      <el-scrollbar max-height="75vh" class="playground-screenshot-dialog-scroll">
        <el-image :src="imageUrl" fit="contain" alt="网页截图大图" />
      </el-scrollbar>
    </el-dialog>
  </div>
</template>
