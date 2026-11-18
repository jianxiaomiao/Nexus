<template>
  <view class="login-planet">
    <view class="planet-viewport">
      <image v-if="!posterFailed" v-show="sceneState !== 'ready'" class="planet-poster"
        src="/static/auth/login-planet.png" mode="aspectFit"
        aria-label="橘白小猫和暖灯小屋坐落在绿色星球上" @error="posterFailed = true" />
      <view v-if="posterFailed && sceneState !== 'ready'" class="planet-fallback">
        <text class="fallback-star">✦</text>
        <text class="fallback-title">你好，开发者</text>
      </view>
      <!-- #ifdef APP-PLUS || H5 -->
      <view :id="hostId" class="planet-host" :class="{ 'planet-host--ready': sceneState === 'ready' }"
        :scene-config="sceneConfig" :change:scene-config="planetView.onConfig" />
      <!-- #endif -->
    </view>
    <!-- #ifdef APP-PLUS || H5 -->
    <view class="planet-controls">
      <text v-if="sceneState === 'ready'" class="planet-hint">左右拖动旋转</text>
      <text v-else-if="sceneState === 'fallback'" class="planet-hint">当前显示静态插画</text>
      <button :id="hostId + '-reset'" v-show="sceneState === 'ready'" class="planet-reset"
        hover-class="planet-reset--pressed">回到正面</button>
    </view>
    <!-- #endif -->
  </view>
</template>

<script>
let nextId = 0
export default {
  props: {
    active: { type: Boolean, default: true }
  },
  data() {
    return { hostId: 'nexus-login-planet-' + ++nextId, sceneState: 'loading', posterFailed: false }
  },
  computed: {
    sceneConfig() {
      let src = ''
      // #ifdef APP-PLUS
      src = './static/app/login-planet/scene.js'
      // #endif
      // #ifdef H5
      src = '/static/web/login-planet/scene.js'
      // #endif
      return { hostId: this.hostId, src, active: this.active }
    }
  },
  methods: {
    onSceneStatus(state) {
      if (['loading', 'ready', 'fallback', 'paused'].includes(state)) this.sceneState = state
    }
  }
}
</script>

<!-- #ifdef APP-PLUS || H5 -->
<script module="planetView" lang="renderjs">
import { createPlanetView } from '../graphics/login-planet-view.js'
export default {
  methods: {
    onConfig(config, previous, owner) {
      this._planetOwner = owner || this.$ownerInstance
      if (!this._planetView) {
        this._planetView = createPlanetView(state => {
          this._planetOwner.callMethod('onSceneStatus', state)
        })
      }
      this._planetView.update(config)
    }
  }
}
</script>
<!-- #endif -->

<style scoped>
.planet-viewport { position: relative; width: 100%; height: 340rpx; }
.planet-poster, .planet-host, .planet-fallback { position: absolute; top: 0; left: 0; width: 100%; height: 100%; }
.planet-host { opacity: 0; pointer-events: none; }
.planet-host--ready { opacity: 1; pointer-events: auto; }
.planet-fallback { display: flex; flex-direction: column; align-items: center; justify-content: center; }
.fallback-star { font-size: 90rpx; color: #e7bd58; }
.fallback-title { margin-top: 12rpx; font-size: 32rpx; color: #203b37; }
.planet-controls { display: flex; min-height: 44px; align-items: center; justify-content: center; }
.planet-hint { color: #667a72; font-size: 22rpx; }
.planet-reset { display: flex; min-height: 44px; align-items: center; margin: 0 0 0 12rpx; padding: 0 16rpx; border: 0; border-radius: 18rpx; background: transparent; color: #247b70; font-size: 22rpx; line-height: 1.4; }
.planet-reset::after { border: 0; }
.planet-reset--pressed { background: #eef2e8; }
@media screen and (max-width: 360px) {
  .planet-viewport { height: 290rpx; }
}
@media screen and (min-width: 600px) {
  .planet-viewport { height: 225px; }
  .planet-hint, .planet-reset { font-size: 12px; }
  .planet-reset { margin-left: 8px; padding: 0 12px; border-radius: 10px; }
  .fallback-star { font-size: 56px; }
  .fallback-title { margin-top: 8px; font-size: 20px; }
}
</style>
