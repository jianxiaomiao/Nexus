# Web Cookie 会话（2026-11-17）

## 问题与决定

Pinia 默认保存在当前页面内存，刷新会丢失。为恢复网页登录，不把 RT 写入 Pinia、localStorage 或 sessionStorage：RT 由服务端通过 HttpOnly Cookie 写入，AT 和公开会话快照仍只在 Pinia 内存。网页与 `/api` 使用同源部署；Electron 保持 Main 私有凭据和 JSON 传输，不读写浏览器 Cookie。

复用 `/api/auth/login`、`refresh`、`logout`，以固定请求头 `X-Nexus-Session-Transport: cookie` 选择网页传输。它不是身份凭据。缺少该值时保留原 JSON 协议：登录/续期返回完整 AT/RT，退出接受 Bearer AT；这是 Electron Main 的现有边界。两种模式不混用凭据。

不新增表或迁移。后端沿用 V14、严格 RT 轮换和固定会话绝对期限；普通轮换不会立即废止其他标签尚未到期的 AT。服务端退出会立即撤销同会话全部 AT/RT。

## 四个边界

1. **Cookie**：生产固定 `__Host-nexus_rt; Path=/; HttpOnly; Secure; SameSite=Strict`，无 Domain、Max-Age 或 Expires。默认不提供“记住登录”；浏览器可能恢复会话 Cookie，因此不能把关闭浏览器当作服务端撤销证明。最终期限由后端控制，续期不延长它。
2. **CSRF**：网页三种会话请求均要求 JSON、固定自定义头、精确允许的 Origin。Origin 缺失/null/不匹配或重复即拒绝；有 Sec-Fetch-Site 时只接受 same-origin。失败检查不轮换、不撤销、不写清除 Cookie。没有开放携带凭据的跨域 CORS。SameSite 为额外防线，不单独承担 CSRF 防护。
3. **AT**：响应只返回 `accessToken/tokenType/expiresInSeconds/sessionId/sessionExpiresAt`，由协调层校验后整体应用到 Pinia。没有 RT 字段；不持久化、广播 AT。刷新页面先调用 Cookie refresh，拿到新 AT 后才进行初始受保护导航。管理请求使用 Bearer；机器 API 仍使用 API Key 且 `credentials: omit`。
4. **跨标签**：同一标签并发续期共享 Store Promise；同源所有登录/续期/退出由 Web Locks 串行。每个标签可依次使用浏览器最新 Cookie 换取自己的 AT，而不是跨标签传播 AT。BroadcastChannel 和 storage 事件只提示重新读取非敏感状态；每次管理请求和受保护导航也核对共享状态，覆盖迟到的广播。

`HttpOnly` 降低脚本读取 RT 的风险，不保证发生同源 XSS 后攻击者不能代发请求。Web Locks 和本地版本用于协调，服务端仍验证真实凭据、会话、账号和权限。

## 调用链

### 登录

`LoginView → auth.login → WebSessionCoordinator.login → Web Lock → POST /api/auth/login`

后端在创建会话事务提交后设置 RT Cookie，返回公开快照。协调器验证响应、发布非敏感 ready 状态，Store 按当前登录意图应用 AT，再导航到 Applications。不再有“拿到 RT 后写回”的步骤。

### 页面恢复与续期

`bootstrap → Store.initializeSession → Coordinator.restore → Web Lock → Cookie refresh → 后端消费旧 RT、创建新 RT → Set-Cookie + 公开快照 → Store`

Router/管理 HTTP 请求发现 AT 到期，或原管理请求返回当前 `401/INVALID_ACCESS_TOKEN`，调用 `ensureSession`。一个标签共享一次进行中的续期；其他标签等待 Web Lock 后读取最新 Cookie。请求前续期成功才附新 AT。401 后即使续期成功，原业务请求仍 reject，避免重放写操作；其他 401、403、500 和网络错误不猜测刷新。

请求记录本地 operationVersion/webRevision。较旧登录意图的结果不能覆盖新状态；同一代 AT 已被其他并发结果更新时，迟到401复用当前凭据。Web refresh 携带已知 `expectedSessionId`，后端在消费 RT 前核对；客户端也核对 sid 和固定绝对期限。

### 退出

`退出按钮 → Store.clearSession → 写入持久的 logged-out/pending 意图 → 通知其他标签 → 等 Web Lock → Cookie logout → 后端撤销 → 清 Cookie → 登录页`

退出意图先于网络完成生效，在途续期响应不能恢复 AT。服务端从 RT 摘要定位原会话，按 User→Session→RT 加锁，消费过的已知 RT 也可作为退出证明，退出不刷新。目标不匹配不追清其他会话。保留既有短连接/有界重试，成功撤销可安全重复。

撤销响应失败/丢失：本地所有标签继续退出，持久保留“服务端撤销未确认”，重载不会因仍存在的 Cookie 静默重新登录。明确重新登录可建立新的本地代次。失败的新登录不能抹掉已有撤销警告。

本地停止使用不等于服务端已经撤销。结果未知后建立新登录，也不证明旧会话已撤销；旧会话仍受后端绝对期限和独立的撤销规则约束。

### 结果未知与标签关闭

发请求前记录 working。网络错误、超时、坏响应或绝对期限/身份不匹配后进入 uncertain，不重复旧 RT。持锁标签关闭时 Web Lock 会释放；下一个持锁者发现遗留 working，按结果未知处理，不试探同一个 RT。登录/刷新/退出均有网络期限，等待锁也有期限；不支持 Web Locks、BroadcastChannel 或本地存储时明确停止，不使用无锁降级。

localStorage 的 `nexus.auth.web.v1` 仅含代次、操作ID、阶段、操作类型、sid、绝对期限和撤销确认状态。它不是权限来源；不含邮箱、密码或令牌。切换新登录、退出和异常会使旧页面意图失效。

## 配置

`application-local.yml` 只在 local profile 生效：独立 Cookie 名 `nexus_rt_dev`、Secure=false，仅允许 `http://localhost:5173` 和 `http://127.0.0.1:5173`。选择一个入口持续使用；两种主机名各有独立 Cookie。4173/4174 不在日常允许来源中，仅由测试进程覆盖。

生产示例要求 `NEXUS_WEB_ORIGIN` 为实际公开 HTTPS Origin，例如 `https://console.example.com`，无尾部斜杠。前端和 API 必须经同一 Origin 访问，代理保留 Origin/Sec-Fetch-Site/Set-Cookie。默认安全配置的来源列表为空，未配置时拒绝网页 Cookie 请求，JSON Electron 仍可用。

Cookie 不按端口隔离，Web Locks 按 Origin 隔离。因此服务端拒绝为同一 Cookie 主机配置多个网页 Origin，避免两个端口共享 RT 却没有共享锁。该方案不提供不同来源之间的标签协调。

## 关键实现与验证入口

- `nexus-console/src/auth/webSession.ts`：Cookie fetch、锁、共享元数据、未知结果处理。
- `nexus-console/src/stores/userStore.ts`：内存快照、同标签合并、意图与凭据版本。
- `nexus-console/src/api/auth.ts`、`api/http.ts`、`router/index.ts`、`main.ts`：登录、HTTP/导航边界和标签事件。
- `nexus-server/src/main/java/com/nexus/auth/websession/`：Cookie HTTP 适配、CSRF策略、RT退出证明。
- `AuthSessionService.refreshForWeb`：消费前核对预期 sid；`LogoutDatabase.revokeRefresh`：沿原锁顺序撤销。
- `WebSessionPolicyTests`、`WebSessionIntegrationTests`：Cookie属性/CSRF/真实DB、JSON隔离、原RT退出证明。
- `nexus-console/e2e/webSession.spec.ts`：真实浏览器 API、受控服务响应下的多标签/并发/故障场景。
- `WebSessionBrowserIntegrationTests` + `scripts/test-web-session-live.mjs`：显式启用的真实 Chrome→Vite→HTTP→MySQL 验收，使用独立临时用户，结束清理用户/会话。

测试结果和实际验收范围记录于 SESSION_HANDOFF.md；模拟浏览器、真实 MySQL/HTTP 和真实浏览器闭环分别记录，不能互相代替。未更新安装版 Electron、部署或生产配置。

设计参考：[OWASP CSRF 防护](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html)、[Web Locks API](https://developer.mozilla.org/en-US/docs/Web/API/Web_Locks_API)。
