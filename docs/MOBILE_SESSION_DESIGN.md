# 安卓移动端登录与会话恢复

2026-11-17。Nexus Android App 复用现有 `/api/auth/login`、`refresh`、`logout` JSON 协议和 Bearer 管理接口。用户选择关闭 App 后保留登录；本模块负责真实登录、进程重开恢复、续期和退出；后续完整管理页面接入见 [安卓管理页面迁移](MOBILE_MANAGEMENT_DESIGN.md)。

## 期限和身份

关闭进程不主动退出，重开后通过保存的 RT 换取新 AT。服务端仍执行现有固定 24 小时绝对期限，续期不延长它。保持登录不等于无限期登录；未来若需要类似长期活跃会话，须另行设计服务端政策。

管理端代表人类用户，以邮箱密码和 Bearer AT 登录；不使用 API Key 代替用户身份。App 不使用 Web 的 Cookie 传输标记，也不修改 Web 的同源 CSRF 策略。Android 以外的平台目前保留界面预览，未提供明文存储降级。

## 凭据保存

`auth/android-vault.js` 调用 Android 系统 AndroidKeyStore、AES/GCM/NoPadding 和应用私有 SharedPreferences。每次加密由 Cipher 生成随机 IV；系统管理不可导出的 AES 密钥。持久记录仅含 RT、会话 ID、固定期限、状态和撤销提示，不含密码或 AT。密文以项目命名空间和后端 Origin 作为关联数据，记录也按 Origin 隔离。

选择系统 Keystore 加密存储，是为了跨进程恢复且避免把 RT 明文放进 uni Storage。代价是 Android 原生桥接与设备密钥生命周期；设备密钥不可用、数据损坏或读取失败时停止恢复并提示，不绕过加密。此实现需要 Android API 23 及以上。它不能防止已经控制应用进程的攻击者使用该应用的系统密钥。

## 调用链

- 登录页通过 `v-model` 收集输入，提交后调用 `auth/runtime.js` 的 `getAuth()` 和 `login()`。密码在提交结束与页面卸载时清空。
- `auth/session.js` 验证成功响应，先提交加密持久记录，再向页面报告已登录。页面不获得 AT/RT；3D 组件只接收前后台激活状态。
- 重开先读取加密记录；ready 状态且仍在绝对期限内时轮换 RT，得到新的内存 AT，再进入 Application 页。
- 管理页面通过 `api/management.js` 组装请求，再调用会话模块的 `management` 入口；该入口限制路径与 HTTP 方法，所有权继续由后端校验。最初接入仅查询 Application；当前已接入应用和 Key 管理、配额及 Usage，范围见 [安卓管理页面迁移](MOBILE_MANAGEMENT_DESIGN.md)。
- 请求前发现 AT 到期时，所有调用共享当前登录代次的一次续期。当前 AT 的 `401/INVALID_ACCESS_TOKEN` 可以触发续期，但原业务请求不重放。旧代次请求不能覆盖新登录。

## 中断与退出

每次登录或 RT 轮换请求发送前，先把持久记录改为 working，移除旧 RT。请求成功才保存新的 ready 记录。这样即使进程在网络请求中被杀死，重开也不会重新提交可能已消费的 RT。网络失败、超时、坏响应或遗留 working 都要求重新登录；这是现有严格 RT 重放撤销规则的必要取舍。正常关闭后的恢复与续期中断后的重新登录是两个不同场景。

退出先清空内存身份并写入 logged-out，再用原 AT 请求撤销原会话，包括 AT 已到期的情况；不为退出续期。网络撤销未确认时保留提示，重开不会靠旧记录恢复。持久化失败会显式报错，同时仍尝试服务端撤销，并保留内存证明供重试；不能把这类失败报告为退出成功。失败的新登录不抹掉既有撤销未确认标记。

## 开发环境

`config/backend.js` 当前选择 `http://127.0.0.1:18086`。Mac 后端只监听回环地址，通过 `adb reverse tcp:18086 tcp:18086` 提供给连接的安卓手机，不开放局域网服务。开发 HTTP 仅允许这个固定回环地址；正式目标必须配置明确的 HTTPS Origin。USB 断开后此本机入口不可达，不能当作生产离线登录体验。

真实验收只使用核实后的 `nexus_test` 和专用测试数据库身份，不访问日常数据库或 ECS。自然 AT 到期检查使用独立后端进程的真实 8 秒 JWT，不修改系统时间、持久配置或只缩短客户端计时。正式会话绝对期限仍为 24 小时。

## 验证入口

`nexus-mobile/scripts/test-auth-session.cjs` 直接验证实际会话模块，包含持久化边界、恢复轮换、并发续期、过期、未知结果、迟到响应、当前/过时 401、退出和存储失败。会话专项当前38项通过，另有10项传输与时间契约检查。

安卓 Keystore 已通过无凭据探针验证加解密、密文中无明文及重复加密生成不同 IV。真实安卓到独立 HTTP/MySQL 后端已验证：登录并读取所属 Application、真实 8 秒 JWT 自然到期后的续期、完整终止进程后恢复登录、显式退出及退出后重开不恢复；续期中断遗留 working 时要求重新登录也已实测。本轮四名临时用户、四个 Application 和七条会话及其 RT 已定向清理，复查为空。验收后本机后端恢复 30 分钟 AT，仍为独立 nexus_test、回环 18086 和 24 小时绝对期限。详细结果记录于 AGENTS.md 当前移动端检查点。源模式验收不等于已签名安装包、iOS、小程序或生产部署验收。

参考：[Android Keystore](https://developer.android.com/privacy-and-security/keystore)、[Native.js Android](https://www.html5plus.org/doc/zh_cn/android.html)、[现有 Web 会话边界](WEB_SESSION_DESIGN.md)。

## 2026-11-18 恢复复验

开机后恢复专用测试后端及USB通道，用正常30分钟AT补完最后UI收尾后的安卓验收。真实登录/所属列表、完整杀进程后恢复、用户确认原生退出弹窗后清除凭据、再次重开保持退出及空密码均通过。手机运行的app-service.js与当前本地构建哈希一致。本轮一用户、一个Application、两条会话及RT已精确清理，复查为空；退出后的服务端活跃会话为0。

当前后端和USB API通道保留供继续开发，手机停在登录页；这次未重跑30分钟自然过期，先前8秒真实JWT续期结果仍独立有效。运行状态和下一步见 [SESSION_HANDOFF.md](SESSION_HANDOFF.md) 的移动端接续节。


2026-11-18管理页面接入后，HTTP传输集中到 `api/transport.js`，会话模块仍私有持有AT/RT；management只向明确允许的管理路径和方法附Bearer。最终安卓复验通过完整管理流程、进程重开RT恢复以及退出后重开保持退出，详见 [接续记录](SESSION_HANDOFF.md)。
