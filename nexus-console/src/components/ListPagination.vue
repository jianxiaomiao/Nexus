<script setup lang="ts">
import { ref, watch } from 'vue'

const props = withDefaults(defineProps<{ current: number; size: number; total: number; label?: string; showSize?: boolean }>(), {
  label: '每页条数',
  showSize: true,
})
const emit = defineEmits<{ 'page-change': [value: number]; 'size-change': [value: number] }>()
const draftSize = ref(props.size)
watch(() => props.size, (value) => { draftSize.value = value })

function changeSize(value: number | undefined) {
  if (value === undefined || !Number.isInteger(value) || value < 1 || value > 100) return
  emit('size-change', value)
}
</script>

<template>
  <div class="list-pagination">
    <label v-if="showSize" class="page-size">{{ label }}
      <el-input-number v-model="draftSize" :min="1" :max="100" :precision="0" :step="1" :aria-label="label" @change="changeSize" />
    </label>
    <el-pagination background layout="prev, pager, next, total" :current-page="current" :page-size="size" :total="total" @current-change="emit('page-change', $event)" />
  </div>
</template>

<style scoped>
.list-pagination { display: flex; align-items: center; justify-content: flex-end; flex-wrap: wrap; gap: 12px; margin-top: 20px; }
.page-size { display: flex; align-items: center; gap: 8px; color: var(--nexus-muted); font-size: 13px; }
.page-size :deep(.el-input-number) { width: 110px; }
</style>
