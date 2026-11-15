<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue'
import type { ButtonInstance } from 'element-plus'
import poster from './login-planet/poster.png'

defineProps<{ fallbackSrc: string }>()
const canvas = ref<HTMLCanvasElement>()
const resetButton = ref<ButtonInstance>()
const state = ref<'loading' | 'ready' | 'fallback'>('loading')
const posterFailed = ref(false)
const controller = new AbortController()
let dispose: (() => void) | undefined
let frame = 0
let timeout: ReturnType<typeof setTimeout> | undefined

function fallback() {
  state.value = 'fallback'
  controller.abort()
  dispose?.()
  clearTimeout(timeout)
}

onMounted(() => {
  // Paint the form and poster first. Graphics never block authentication.
  frame = requestAnimationFrame(() => {
    frame = requestAnimationFrame(async () => {
      timeout = setTimeout(fallback, 15000)
      try {
        const { start } = await import('./login-planet/scene.js')
        if (controller.signal.aborted || !canvas.value || !resetButton.value?.$el) return
        dispose = await start(canvas.value, resetButton.value.$el, controller.signal)
        if (!controller.signal.aborted) state.value = 'ready'
      } catch {
        if (!controller.signal.aborted) fallback()
      } finally {
        clearTimeout(timeout)
      }
    })
  })
})

onBeforeUnmount(() => {
  controller.abort()
  cancelAnimationFrame(frame)
  clearTimeout(timeout)
  dispose?.()
})
</script>

<template>
  <section class="login-planet" :data-state="state" aria-label="小猫的开发者星球">
    <div class="planet-viewport">
      <div class="planet-shadow" aria-hidden="true"></div>
      <div class="planet-stage">
        <img v-show="state !== 'ready'" class="planet-poster" :src="posterFailed ? fallbackSrc : poster" alt="橘白小猫、暖灯小屋和 API 积木坐落在绿色星球上" @error="posterFailed = true" />
        <canvas ref="canvas" v-show="state === 'ready'" tabindex="0" aria-label="拖动或左右方向键旋转，上下方向键微调视角，Home 键回到正面" @webglcontextlost="fallback"></canvas>
      </div>
    </div>
    <div v-show="state === 'ready'" class="planet-controls">
      <span>拖动旋转</span>
      <el-button ref="resetButton" round plain size="small">回到正面</el-button>
    </div>
    <p v-if="state === 'fallback'" class="planet-note" role="status">当前显示静态插画，不影响登录</p>
  </section>
</template>

<style scoped>
.login-planet { width: 100%; min-width: 0; text-align: center; }
.planet-viewport { position: relative; width: 100%; aspect-ratio: 1.11; max-height: 75vh; }
.planet-stage, .planet-poster, canvas { position: absolute; inset: 0; width: 100%; height: 100%; display: block; }
.planet-poster { object-fit: contain; }
canvas { touch-action: pan-y pinch-zoom; cursor: grab; border-radius: 24px; }
canvas:active { cursor: grabbing; }
canvas:focus-visible { outline: 3px solid var(--nexus-teal); outline-offset: 3px; }
.planet-shadow { position: absolute; width: 48%; height: 7%; left: 26%; bottom: 2%; border-radius: 50%; background: radial-gradient(ellipse,#7853304a, #91764b22 40%,transparent 72%); filter: blur(4px); }
.planet-controls { display: flex; min-height: 44px; align-items: center; justify-content: center; gap: 12px; color: var(--nexus-muted); font-size: 12px; }
.planet-controls :deep(.el-button) { min-height: 44px; background: #fffaf0b0; border-color: #b6bcaa; color: var(--nexus-muted); }
.planet-note { min-height: 44px; margin: 0; display: grid; place-items: center; color: var(--nexus-muted); font-size: 12px; }
@media (max-width: 760px) {
  .planet-viewport { height: clamp(175px, 29svh, 270px); aspect-ratio: auto; max-height: none; }
}
@media (prefers-reduced-motion: no-preference) {
  .planet-stage { animation: planet-rise 1050ms cubic-bezier(.16,.7,.24,1) both; }
  @keyframes planet-rise { from { transform: translateY(11%); opacity: .35; } to { transform: translateY(0); opacity: 1; } }
  @media (max-width: 760px) {
    .planet-stage { animation: planet-appear .55s ease-out both; }
    @keyframes planet-appear { from { filter: blur(3px); opacity: .3; } to { filter: blur(0); opacity: 1; } }
  }
}
</style>
