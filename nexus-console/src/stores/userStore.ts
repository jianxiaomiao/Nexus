import { defineStore } from 'pinia'
import type { LoginResponse } from "@/api/auth"

type UserSession = {
  accessToken: string
  tokenType: string
  expiresInSeconds: number
  expiresAt: number // 绝对过期时间戳（毫秒）
}

export const useUserStore = defineStore('userStore', {
  state: (): UserSession => ({
    accessToken: "",
    tokenType: "",
    expiresInSeconds: 0,
    expiresAt: 0
  }),

  // 删掉那些单纯返回state的getter，直接 store.xxx 访问即可

  actions: {
    /**
     * 一次性设置会话，传入后端登录返回的 LoginResponse
     * 自动换算 expiresAt：当前时间 + 有效时长
     */
    setSession(loginResp: LoginResponse) {
      this.accessToken = loginResp.accessToken
      this.tokenType = loginResp.tokenType
      this.expiresInSeconds = loginResp.expiresInSeconds
      // Date.now() 是毫秒；expiresInSeconds是秒，*1000
      this.expiresAt = Date.now() + loginResp.expiresInSeconds * 1000
    },

    /** 清空会话，退出登录 */
    clearSession() {
      this.accessToken = ""
      this.tokenType = ""
      this.expiresInSeconds = 0
      this.expiresAt = 0
    },
  },
})
