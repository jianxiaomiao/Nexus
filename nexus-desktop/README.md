# Nexus Desktop

## Windows 打包配置检查点（2026-11-14）

开发者已调整启动边界：仅开发模式覆盖 `.runtime/user-data`，打包版保留Electron默认userData，两者共用协议/窗口/IPC启动流程。Main的安装版AppUserModelId与 `electron-builder.yml` 同为 `com.nexus.desktop`；开发版沿用Electron可执行路径。冒烟与诊断环境标志仅开发模式生效，安装版不向程序目录写诊断图片。

固定开发依赖electron-builder 26.18.0，目标Windows x64，ASAR归档，输出在忽略的 `release/`。文件清单只含Main、Preload、window-state、Renderer产物及必要package.json；不带测试入口、`.runtime`、相邻前端源码或开发依赖。当前版本为0.0.1，沿用默认Electron图标，没有配置代码签名或自动更新/发布；后端仍需单独运行于localhost:8080。

- `npm run pack:dir`：先检查类型并重建Renderer，再生成 `release/win-unpacked/Nexus Desktop.exe` 及其资源目录。运行时必须保留整个目录，不能只复制exe。
- `npm run test:packaged-smoke`：直接运行上述exe，使用正式命令行参数将userData隔离在`.runtime/packaged-smoke-*`，检查isPackaged=true、ASAR页面与Preload、可信IPC、History刷新、窗口可见和关闭后的窗口配置写入；不登录后端或创建业务资源。
- `npm run pack:win`：同样先重建，再生成NSIS安装包，不会自动安装或发布。2026-11-14已生成并完成首次当前用户安装，见下方实际验收。

NSIS采用带向导的当前用户安装，可选择安装目录、创建桌面和开始菜单快捷方式，卸载默认保留用户数据，安装结束不自动运行。`build/installer.nsh`仅通过官方customInstallMode宏固定当前用户模式；不能仅凭oneClick=false/perMachine=false就认定不存在全用户安装选项。当前用户模式且未接自动更新，因此packElevateHelper=false，不带可选提权helper。同版本覆盖、保留数据卸载及0.0.0→0.0.1升级已验收；安装版成功/失败通知与同进程去重也通过受控状态及真实Windows通知验收，见下节。

本轮 `pack:dir`、实际打包exe冒烟、开发入口及短链IPC边界回归通过。ASAR清单51项且全部符合允许范围，包内Main/Preload/window-state字节与源码一致；配置中的8MiB/30秒机器转发和固定后端不因打包改变。依据：[electron-builder v26配置](https://www.electron.build/v26/docs/configuration/)、[NSIS自定义安装模式](https://www.electron.build/v26/docs/nsis/#custom-nsis-script)。

### 首次安装实际验收与执行环境对照（2026-11-14）

NSIS产物约118MiB、版本0.0.0、未签名，已检查哈希与包内容。用户通过向导安装到默认的LocalAppData/Programs/Nexus Desktop，HKCU注册卸载入口；安装载荷与检查过的ASAR哈希一致。桌面和开始菜单快捷方式均指向安装exe，AUMID为com.nexus.desktop；两者均实际启动，用户分别确认正常登录页，测试窗口随后正常关闭。证据`.runtime/nsis-acceptance-20261114-140208/`。NSIS负责安装文件、快捷方式和卸载入口，不自动提供后端服务。

最初由Codex启动安装exe时观察到默认userData保存EXDEV。后续句柄路径与受控矩阵确认：这个上下文的逻辑C盘AppData映射到了D盘Codex包LocalCache，逻辑目标重命名失败、使用实际路径则成功；C盘普通Temp正常。并非C盘整体权限禁止，Encrypted属性也不足以证明加密是原因。

用户随后亲自从桌面启动、移动并关闭，窗口配置成功保存到句柄确认的真实C盘Roaming/nexus-desktop/window-state.json；不再把先前工具启动链失败当作普通用户运行缺陷。证据manual-explorer-state.json与path-matrix.json均在上述验收目录。未修改生产算法、权限或真实目录加密设置，继续保留同目录临时文件+rename。后续正常Windows会话验收见下节。背景：[Microsoft的AppData虚拟化说明](https://learn.microsoft.com/en-us/windows/msix/desktop/desktop-to-uwp-behind-the-scenes)。

### 重启、同版本覆盖安装和卸载通过（2026-11-14）

为避开工具上下文，用户手动执行所有应用/安装/卸载操作，Agent只读核对；不要通过Codex启动流程重现正常Windows用户会话。普通窗口与最大化重启恢复通过，最大化/还原不改变保存的普通矩形。窗口必须完整位于工作区；部分超出工作区时回退居中是当前保留的策略，不能据此判断保存失败。

同一0.0.0安装器覆盖至原目录后，ASAR和用户窗口配置哈希不变，两个快捷方式及应用ID正确；用户从开始菜单确认登录页与布局正常。随后用户从设置卸载：程序目录、快捷方式和进程移除，真实C盘配置仍在且逐字节一致。普通Windows设置和普通cmd均确认卸载项不存在；工具环境仍见旧注册表视图，未为此手动删除注册表。证据`.runtime/nsis-lifecycle-20261114-143143/`。

该轮结束时程序已卸载，用户数据与构建产物保留。后续卸载后重装与跨版本升级见下节，自动更新尚未接入。

### 跨版本升级0.0.0 → 0.0.1通过（2026-11-14）

先保留已验证的0.0.0安装器，用户从正常资源管理器重装旧版，确认保留布局恢复后关闭。再用 `npm version 0.0.1 --no-git-tag-version --ignore-scripts` 同步应用及锁文件版本，`npm run pack:win`生成0.0.1包。appId、包名、用户目录与业务代码保持不变；配置文件里的version1是格式版本，不随应用版本变化。此步骤没有Git提交、标签或发布。

用户不卸载旧版，直接从资源管理器执行0.0.1安装器覆盖原目录；新exe/ASAR版本与产物匹配、快捷方式/AUMID保持、真实C盘窗口配置逐字节不变。用户在正常Windows设置确认单一0.0.1条目，再从桌面确认登录页和布局恢复。工具注册表旧视图仍显示0.0.0，仅作环境差异记录，不据此删键或误判。

证据`.runtime/nsis-upgrade-20261114-145231/`，旧包另存old-installer，新包为 `release/Nexus-Desktop-0.0.1-x64-Setup.exe`。新旧ASAR只有package.json元数据变化；此验收不覆盖未来配置迁移或自动更新。当前保留0.0.1安装、用户数据和两版产物，测试窗口已关闭。

### 安装版通知受控验收通过（2026-11-14）

使用现有安装版的正式Preload、跟踪IPC、响应校验、终态处理和Windows Notification；仅将本地8080后端临时替换为独立模拟服务，不修改Main/Preload/ASAR，不创建截图任务，不获取PNG，不需要登录或真实Key。安装版必须由用户从普通Windows桌面启动，不能由Codex启动或覆盖userData，以避开此前AppData重定向影响。服务及证据仅在项目目录运行；测试代码不进入打包文件清单。

准备命令（均从仓库根目录执行）：

```powershell
node nexus-desktop/scripts/serve-installed-notification-mock.cjs --self-test
node nexus-desktop/scripts/serve-installed-notification-mock.cjs --prepare
```

自检只使用临时端口，无Electron/真实后端；prepare只生成忽略目录`.runtime/installed-notification-mock-*/`中的调用片段。正式验收前由用户暂停真实8080后端，再用prepare打印的`--serve --dir`命令启动模拟服务。仅在127.0.0.1和::1双回环8080同时绑定成功后输出READY；端口冲突立即退出，不停止进程、不改端口、不绑定公网地址。

模拟服务只接受两个固定UUID的GET状态查询和固定假Key；错误来源Host、方法、路径、Key及带正文的查询均拒绝，无HTTP控制入口。状态由服务标准输入命令控制：`success`令成功样例变为SUCCEEDED，`fail`令失败样例变为FAILED，`report`写摘要，`stop`释放两个监听。两样例初始均为RUNNING；成功元数据为虚构值，没有对应PNG。日志不保存请求头、正文、原始URL或真实凭据。

用户手动从桌面打开Nexus后，在主页面开发者工具Console逐项复制生成的文件内容执行；若有粘贴保护，不自动绕过，按界面提示检查代码后操作。不要点击Playground创建任务。每一步均等待人工确认：

1. `01-start-success.js`：要求Main当前没有跟踪任务，开始固定成功样例，等待确认POLLING/RUNNING，此时不应出现通知。
2. 关闭开发者工具并最小化窗口；Agent向模拟服务输入`success`，等待下一次查询，用户确认真实成功通知。返回Console运行`02-read-state.js`，应为TERMINAL/SUCCEEDED。
3. `03-retrack-success.js`：只允许上述成功终态，停止后重新跟踪同一ID；应再次得到SUCCEEDED且同一进程不重复通知。结合服务查询记录和用户观察，不把HTTP200当作通知已显示。
4. `04-start-failure.js`：从成功样例切换到另一个固定失败样例，先确认RUNNING；最小化后Agent输入`fail`，用户确认真实失败通知，再用02读取TERMINAL/FAILED。
5. `05-cleanup.js`：仅清理这两个测试ID或空跟踪，遇到其他任务拒绝清理。确认IDLE后用户正常关闭Nexus；Agent输入`stop`，确认8080释放后用户再恢复真实后端。无需删除用户配置或通知设置。

注意：根部`screenshotTrackingStore.applySnapshot`收到SUCCEEDED后会自动尝试读取PNG，即使停留登录页也一样；该请求会被模拟服务拒绝为404，不返回图片，不影响Main已完成的终态/通知触发，也不修改正式逻辑来跳过它。清理后重跟踪同一成功ID可能再次尝试取图。

技术证据为`requests.jsonl`、`service-summary.json`和人工提供的脱敏快照；是否看到通知单独记录，不替换或包装真实Notification，也不宣称采集了Main的show/failed事件。服务摘要的passed始终为null，不能单凭模拟服务自报通过。

本轮用户确认RUNNING无通知、SUCCEEDED真实成功通知及TERMINAL/SUCCEEDED、同ID停止后重跟踪无新通知、FAILED真实失败通知及TERMINAL/FAILED。Agent核对同一Main进程身份、两次成功终态查询及一次失败终态查询；共46个GET、非GET为0，两条未匹配GET被404拒绝且无PNG返回。用户执行05确认IDLE并正常关闭后，Agent停止模拟服务并确认无Nexus进程、8080已释放、安装ASAR哈希未变。最终证据`.runtime/installed-notification-mock-T3Q88G/summary.json`为passed=true，人工确认保存在user-observations.json。实际截图任务、通知点击跳转及进程重启后的去重不在本轮范围；真实后端由用户按原方式恢复。

## 桌面页面构建（2026-11-13）

在 `nexus-desktop` 执行 `npm run build:renderer`。脚本先运行现有 Vue 源码的类型检查，再复用 `nexus-console/vite.config.ts` 构建，将产物写入 `nexus-desktop/dist/renderer/`（已忽略）；不覆盖 Web 的 `nexus-console/dist/`。构建需要相邻 `nexus-console` 已安装依赖。

入口是 `dist/renderer/index.html`，JS/CSS 等资源位于其 `assets/` 目录。构建使用根路径 `/`，由标准 `nexus://app/` 协议提供资源并保留 History 路由。构建本身不启动 Vite 服务、Electron 或后端，也不将 Vite 的开发代理带入产物。

静态协议、固定/动态页面 History 回退和 nexus 来源校验已接入。缺失资源和 API 路径不会回退为 HTML。`/api/` 与 `/v1/` 已通过 forwardBackend 固定转发至 localhost:8080；2026-11-14 `/api/` 真实登录与 Application 列表验收通过，随后重构和 `/v1/` 转发通过24项受控处理器检查，并完成真实UUID与Hash调用验收。短链打开Main/Preload能力已实现，页面接入待完成，见下方说明。该命令不生成安装包。

当前复用 `../nexus-console` 的 Vue 源码与 Vite 构建配置。Electron Main 创建窗口并加载 Vue；已接入窗口状态文件持久化和任务完成通知。NSIS本地安装、快捷方式、重启恢复、同版本覆盖、保留数据卸载/重装及0.0.0→0.0.1升级通过；登录续期与自动更新仍待完成。

## 会话刷新恢复接入中（2026-11-14）

Main内存会话、固定read/write/clear IPC、Preload和console的desktop.d.ts已完成当前检查。共享userStore新增mainRevision、operationVersion和初始化状态，以及applyMainState/initializeSession：只读取并应用Main状态，保留绝对expiresAt，不把恢复写回Main；同一Store并发读取去重，旧读取不覆盖较新的本地登录/清空或Main版本。初始化失败与成功读取空会话区分，失败后可显式重试；调用方await之后还须检查initializationStatus是否ready。

Web没有window.nexusDesktop时仅保留原内存模式，不会因此持久登录。2026-11-15用户已在main.ts接入初始化等待：只有ready才安装Router，等待router.isReady后挂载；非ready显示错误并停止本次启动，当前通过整页刷新重试，没有页内重试按钮。此bootstrap只能按现有入口调用一次，未来添加按钮前需处理实例复用及重入保护。登录已通过beginLogin/completeLogin同步Main且真实刷新恢复通过；Console退出按钮已接invalidateSession，等待Main确认且代次仍匹配才导航，失败可重试、忙碌禁用、旧响应及卸载后不导航。真实Electron主动退出及同进程刷新不恢复也已通过，见下节。Router和HTTP过期/401现已接统一清理并通过受控测试，详见下一段；未实现Refresh Token、落盘记住登录或更新现有0.0.1安装包。

Router等待过期/进行中的清理，失败取消导航，成功按最新状态返回登录路由。HTTP在附Bearer时为每个管理请求保存本地authContext（operationVersion/mainRevision），只处理带原上下文的管理401/INVALID_ACCESS_TOKEN；旧事件不清新会话，原请求仍失败且不重放。过期请求不发送，清理未确认时阻止后续管理请求；当前清理确认后replace(login)，导航失败单独提示。Main清理IPC抛错或版本冲突后只读一次，合法新版本空会话才确认完成，非空新会话不恢复也不追清。Pinia任务WeakMap使用toRaw(this)以兼容开发工具的action代理。

受控回归在nexus-console目录运行：`node --test scripts/test-auth-session-initialization.mjs scripts/test-auth-session-login.mjs scripts/test-auth-session-invalidation.mjs`（49项）；`npx playwright test e2e/desktopSessionInvalidation.spec.ts e2e/desktopSessionLogout.spec.ts e2e/desktopSessionLogin.spec.ts e2e/auth.spec.ts --workers=2`（42项）。本轮均通过，另有E2E类型检查和桌面build:renderer通过。上述浏览器测试使用模拟API/桌面桥；真实Electron调用方受控验收见下一节，真实后端JWT过期及安装版仍需分别验证。

在nexus-console目录运行`node --test scripts/test-auth-session-initialization.mjs`可验证真实Pinia Store与受控桥：15项通过，覆盖Web分流、空/有效/传输中到期会话、并发/实例隔离、迟到响应、失败重试及版本回滚。无需浏览器、真实Electron、后端或业务资源；应用和E2E类型检查也通过。这不是桌面刷新端到端验收。

### 隔离Electron过期/401受控验收（2026-11-15）

先在desktop目录执行`npm run build:renderer`，然后`node scripts/test-session-invalidation-controlled.cjs`；可用`--case=router-expired`等场景名定向运行。脚本自动用固定假凭据登录，不需用户输入、不需启动/停止真实后端、不绑定8080。测试入口复用正式Main/Preload/协议/构建页面和会话处理器，只把Main fetch替换为固定登录/列表响应，并拒绝范围外API及Renderer HTTP/HTTPS；每个窗口使用D盘.runtime内独立profile，结束自动关闭，不触及安装版用户数据。

六个场景为`router-expired`、`request-expired`、`current-401`、`stale-before-save`、`stale-after-save`、`lost-clear-ack`。过期使用10秒模拟会话自然等待，不改系统时钟、不提前调用会自动清理过期会话的Main read；真实页面路由/分页事件触发守卫和HTTP，过期查询不会发出。旧401测试通过真实Store方法启动第二次登录意图并保存固定假响应，以验证Main保存前后的代次保护，不能当成真实后端重新认证或Refresh Token。丢确认场景在真实clear执行后抛测试异常，观察Store自己的只读核对，而非用诊断read代替它。

本轮`.runtime/session-invalidation-controlled-AWEMKr/evidence.json` passed=true，六场景通过且正常退出。四个清理场景Main/Store变为空/revision2，同PID刷新仍为空；两个旧401场景clear IPC为0，释放前后会话一致，刷新恢复新revision2。18条请求全部为内存模拟（6假登录、12查询、其中4次401），Renderer HTTP/HTTPS及范围外请求0，无真实截图任务或资源创建。窗口可见、DOM/状态自动核对，`humanVisualConfirmation=false`；不是人工逐屏确认、真实后端JWT或安装版验收。

## 独立启动（2026-11-13）

在 `nexus-desktop` 先执行 `npm run build:renderer`，再执行 `npm start`。后者只启动 Electron，通过 `nexus://app/` 加载现有产物，不启动 Vite、后端或数据库；修改 Vue 后需重新构建。未找到入口文件时会提示先构建。这里的“独立”指脱离 Vite 开发服务器，仍需要本地已安装的 Node/Electron，尚不是安装包。

`npm run test:standalone-smoke` 使用同一启动器和正式 Main/Preload，在独立的 `.runtime/standalone-smoke-*` 用户数据目录里启动隐藏测试窗口，等待 `/login` 与真实登录表单挂载，检查 Renderer 无 Node 全局变量，然后退出。测试入口阻断 HTTP/HTTPS，因此不会借助正在运行的 Vite。2026-11-13 重建及该检查通过，HTTP/HTTPS 请求为零。首次受限环境运行出现 Renderer launch-failed/code=49；允许 Electron 子进程执行后通过，未降低浏览器安全设置。测试不提交登录、不验证截图业务，也不代表普通可见窗口的人工验收。

旧 `npm run dev` 仍会启动 Vite，但当前 Main 已固定加载 `nexus://app/`，它不再是独立加载的验收入口。下方旧启动流程、开发代理和验收条目保留当时的背景，以本节为当前入口说明。

### 真实登录与刷新恢复验收（2026-11-15）

先运行现有真实后端localhost:8080，在desktop目录执行`npm run build:renderer`，再运行`node scripts/test-session-refresh-acceptance.cjs`。测试入口在D盘.runtime创建独立profile，不启动Vite，不修改安装版数据；只允许登录POST和Application列表GET转发，不提供截图或其他资源创建路径。用户只在窗口输入凭据，不在聊天发送；登录成功后按底部面板依次确认开始刷新、进程重开及结束，窗口会自动关闭。不要点业务退出按钮，本轮尚未接其Main同步。

本轮`.runtime/session-refresh-live-KWnmTg/evidence.json`为passed=true。真实登录POST200一次、Main写入一次；同一Main进程刷新后保持/applications、Main/Store一致、revision及原expiresAt不变，无新登录或写入，列表Bearer请求200；完全退出后同隔离profile重开为新进程，回/login且内存会话为空，用户确认三阶段页面正常。共四次列表200，另一次范围外GET被测试入口拒绝、未发后端；未知URL未记录，不将其解释为其他页面已验收。无真实截图/资源创建、无Renderer HTTP/HTTPS请求。首次setup曾在窗口显示前过早检查可见性，验收脚本改为等待后成功；未改业务代码。会话元数据及比较布尔被记录，凭据不离开比较上下文。

这是源码独立Electron验收，未验证安装版、退出后刷新、自然到期、401清理、Refresh Token或跨进程记住登录。结束时测试进程已关闭，真实后端保持运行。

### 真实退出与刷新验收通过（2026-11-15）

真实后端就绪并重建Renderer后，运行`node scripts/test-session-refresh-acceptance.cjs --logout`。该模式在独立`.runtime/session-logout-live-*/profile`中打开正式源码Main/Preload/构建页面，只放行登录POST和Application列表GET，保留脱敏请求/IPC结果观察，不替换会话处理器或操作系统能力。用户登录后按底部面板确认，由脚本点击真实退出按钮，检查Main清空确认及Store状态，再在同Main进程刷新验证不恢复会话；最后人工确认后自动关闭。无--logout时保留上节的登录/刷新/重开流程。

本轮session-logout-live-tsY6IZ/evidence.json为passed=true，用户确认三个页面步骤。Main成功写入/清理各一次，Main/Store状态由revision1有会话变为revision2空会话并回/login；同Main PID43604刷新后仍为空、revision不变，无新增登录/写入/清理，证明主动退出确已同步Main。登录POST先401后200均如实记录，列表Bearer GET200两次，范围外请求及Renderer HTTP/HTTPS请求均0。没有重启Main来掩盖结果、没有截图或资源创建；测试进程已关闭，真实后端仍运行。自然过期/401调用方仍未接入，失败/冲突真实环境及安装版不在本轮验收范围。

### 独立登录验收（2026-11-14，历史检查点）

先启动现有本地后端并确认8080可达，再运行 `node scripts/test-login-acceptance.cjs`。脚本不启动Vite，在隔离用户数据窗口验证真实后端400/401边界及IPC来源，然后等待用户在窗口登录；不要在聊天中发送凭据。成功后验证登录200、Bearer Application列表200、窗口可见与Renderer无HTTP/HTTPS请求，显示通过横幅30秒后自动关闭。证据位于忽略的 `.runtime/login-acceptance-*/evidence.json`；不记录凭据、请求体或响应正文，不创建业务资源或截图任务。

本轮真实验收通过。此前后端因8081被Codex占用而启动失败，开发者将本地management端口改为8082后启动成功；业务转发仍固定8080。当前登录会话在内存中，整页刷新会丢失会话，尚未实现续期或持久登录。机器API与公开短链跳转不在本轮真实验收范围。

### UUID / Hash 独立真实验收（2026-11-14）

运行 `node scripts/test-utils-acceptance.cjs`，在隔离窗口登录，待脚本进入调试台后填写已有完整API Key，再点击测试专用准备按钮；不要手动点击页面发送按钮。脚本只通过真实界面各发送一次UUID和Hash（正常消耗配额并记录Usage），不会创建截图任务，成功提示30秒后关闭。独立测试Main入口限制目的地和调用次数，不收集密码、Key、令牌或响应正文。

本轮通过：无凭据401；有效Key的UUID GET和Hash POST均200/SUCCESS，UUID v4结构正确，含中文输入的SHA256与独立UTF-8计算一致；机器请求只携带ApiKey，两个接口各一次，Renderer HTTP/HTTPS请求为0。证据位于忽略的 `.runtime/utils-acceptance-*/evidence.json`。PNG和Retry-After仍是受控转发检查结论，未重复真实截图任务验收。

## 短链打开能力（2026-11-14）

Main的 `short-link:open` 通过Preload的 `openShortLink(shortCode)` 接收12位ASCII字母数字短码，校验可信窗口、主框架及nexus来源后构造固定本地HTTP短地址，交给系统浏览器；不让应用窗口加载外站，不在协议处理器内代理/s/。`opened`只表示openExternal完成，不能证明后端302或目标页面加载成功；系统打开失败返回OPEN_FAILED。调试台创建结果、查询列表和管理页（含窄屏）的桌面打开按钮已接入窄桥，等待期间禁用，失败可重试；Web保留原有链接与复制行为。

`VITE_SHORT_LINK_PUBLIC_BASE_URL`原先只在 `nexus-console/src/api/shortLinks.ts` 读取，并未设置。现在 `scripts/build-renderer.mjs` 的 `define` 仅向桌面产物注入 `http://localhost:8080`，使显示与复制地址为HTTP短地址；不是运行时环境变量，修改后需重新 `npm run build:renderer`。Web构建仍使用原配置或当前网站origin，不需要新增共享.env文件。

`npm run test:short-link-ui` 验证真实桌面构建产物与正式Main/Preload/IPC的界面接入，后端、剪贴板及系统打开用替身，避免创建真实资源或影响系统剪贴板。2026-11-14通过创建/查询/管理/窄屏按钮、HTTP显示/复制、等待与失败恢复；Vue与E2E类型检查、Web原有短链/调试台10项及桌面桥异常/复制失败2项浏览器测试通过。真实浏览器联动由下方独立验收覆盖。

执行 `npm run test:short-link-boundaries` 可重复运行隔离真实Electron/Preload/IPC测试。2026-11-14通过23种非法输入、固定地址、错误窗口、系统同步/异步失败和恢复、等待Promise完成及协议/s/不打开浏览器等边界；来源辅助函数的隔离检查另覆盖子框架、来源URL、销毁/失效情况。该命令的系统打开使用替身，未启动真实浏览器、调用后端或创建业务资源。

### 短链真实浏览器联动（2026-11-14）

`node scripts/test-short-link-live.cjs`为有真实副作用的验收：在隔离窗口登录并填写已有Key、确认准备后，通过真实界面创建两条指向https://weibo.com/的专用短链，正常消耗配额/记录Usage；一条验证302后禁用，另一条10秒自然到期。Node使用manual检查302/404/410及响应契约，再通过正式管理页按钮调用真实系统浏览器。每次用户查看浏览器后，切回验收面板确认；脚本不收集密码/Key/令牌、不创建截图。

本轮通过，证据`.runtime/short-link-live-Qs7B3j/evidence.json`：302的Location匹配及no-store正确，404/410错误码正确且无Location，三次系统交接完成，用户分别确认了三种浏览器表现。首轮选择控件超时后复用了已创建样本（`.runtime/short-link-live-1FfQje/evidence.json`），累计仍仅两条。记录保留为禁用/已过期，隔离窗口30秒后关闭，外部浏览器标签页由用户自行关闭。系统浏览器的最终视觉效果依据用户确认，不将openExternal完成单独当作网页访问成功。

## 接手重试真实验收脚本

在`nexus-desktop`目录执行`node scripts/test-resume-acceptance.cjs --handoff`。隔离窗口中登录，填写完整截图URL和API Key，手动关闭VPN，然后点击测试专用浮层的“VPN 已关闭，开始验收”。输入有效且明确点击前，脚本不提交截图；不要点击原页面创建按钮。脚本不会操作VPN或保存/输出凭据。VPN关闭后脚本仍在本机继续运行；窗口结束后可重新打开VPN，再回来核对结果。

本模式只创建一个真实后端任务，测试入口使第一次`screenshot-tracking:start`返回BUSY（受控注入，并非认定有其他真实任务），验证保留Job ID且尚未查询；随后自动点击“重试接手”，放行正式Main处理器，验证第二次start仍使用原Job ID、POST始终仅一次、待接手记录和计数清理，最后检查真实终态、PNG解码/长度/SHA-256和通知。不会再创建第二个任务。完成后保留窗口45秒，再清理本地跟踪和测试登录存储并关闭；后端任务按原生命周期保留，失败也不自动重新创建。证据在忽略目录`.runtime/handoff-acceptance-*/`。2026-11-13真实运行通过：创建POST=1，首次start受控失败、第二次正式接手，Job ID始终一致；5次真实状态查询200到SUCCEEDED，一次PNG结果200，79902字节、解码宽1280，长度与SHA-256匹配后端记录。通知调用1次、show=1、无错误，Renderer旧查询=0；用户确认拿到截图，本轮通知人工可见性未单独确认。证据`.runtime/handoff-acceptance-rf0HxU/`，脚本正常清理退出。并发/三次上限/迟到返回等已有35项定向浏览器测试覆盖。

默认暂停恢复模式和`--notify`模式也采用相同的VPN准备按钮，不再在粘贴Key后立即提交。

## 任务完成通知验收记录（2026-11-13）

真实后端联动：`node scripts/test-resume-acceptance.cjs --notify`复用隔离窗口和真实登录，不注入查询失败；包装真实Notification只观察事件。2026-11-13自动验收通过：只创建一次，Job ID全程一致，4次状态查询200（QUEUED/RUNNING/SUCCEEDED），PNG结果200且仅获取一次，80064字节、解码宽1280，长度和SHA-256匹配后端元数据。通知构造1次、show=1、无failed，Renderer旧查询数0；技术链路已通过，用户随后确认实际看到了本次真实任务的通知。证据`.runtime/screenshot-notification-acceptance-Y9JiQN/`。窗口完成后保留45秒，再清理测试登录存储和本地跟踪并关闭；后端任务按原生命周期保留。正式业务代码未改。

开发者实现Main的notifyScreenshotFinished：成功解析SUCCEEDED/FAILED并推送快照后尝试通知，以进程内Job ID Set去重，尝试前登记，清理跟踪不清空集合；通知异常不更改任务状态。无需新增Preload/IPC；通知不是在PNG获取后触发。RUNNING/EXPIRED不通知，当前失败不自动重试，进程退出后去重集合消失，通知点击跳转尚未实现。

`node scripts/test-notification-boundaries.cjs`运行隔离Electron、正式Main/Preload/IPC，使用受控任务和通知替身；验证同Job ID在stop后重新跟踪仍不重复、FAILED通知和RUNNING/EXPIRED不通知、支持检查/构造/show同步抛错、异步failed、系统不支持均不影响TERMINAL/SUCCEEDED且无未处理Promise拒绝。初次测试入口直接重定义Electron.Notification报错；修正为仅拦截目标模块require并返回独立门面，正式业务代码未改。

加`--native`会在边界测试后对受控成功任务发送一条真实Windows通知，并保留窗口45秒后自动关闭，不访问后端。最初系统接口返回DisabledForUser，真实通知failed，开发者也未看到；开发者打开通知总开关后接口返回Enabled，重测show=1、failed=0、任务终态不变，开发者截图确认横幅出现。`show`事件本身不等于用户看到，最终结论结合系统状态与人工观察。证据在忽略目录`.runtime/notification-boundaries-wpSYVg/`，不将该受控任务验收表述为真实后端截图与通知的一体化验收。

## 真实暂停恢复验收（2026-11-13）

`node scripts/test-resume-acceptance.cjs`在隔离userData的真实Electron中加载正式Main、Preload、Vue和Store，连接本地真实8080。用户在窗口登录，先粘贴完整截图URL，最后一次性粘贴Key；无需手动提交。创建请求由Renderer/Vite到后端，仅Main第一次状态查询被测试入口注入503，后续查询和PNG获取均为真实后端响应。脚本自动点击继续查询，完成后清理本地跟踪和测试登录存储，并自动关闭隔离窗口。真实后端任务不会因本地清理被取消；不自动重试创建。

本轮验收通过：创建POST一次，Main请求全部使用同一个Job ID，状态POLLING→PAUSED→POLLING/RUNNING→TERMINAL/SUCCEEDED；恢复后4次真实状态查询和一次真实PNG获取均200。Renderer没有发起旧状态/结果查询；PNG为79999字节、解码宽度1280，与后端记录的长度和SHA-256一致。证据为忽略目录`.runtime/resume-acceptance-wVsZde/`中的evidence.json、paused.png、completed.png；未记录密码、Key或JWT。下方2026-11-12和此前条目保留对应阶段的验证边界，当前Playground已接Store与状态/取图/清理/恢复界面。

## Main 截图跟踪（2026-11-12）

Renderer根部同步已接入：开发者实现Pinia screenshotTrackingStore.snapshot、initScreenshotTracking和App挂载/卸载接入；先订阅再读取，以推送计数拒绝迟到的首次读取，清理时标记disposed并取消订阅，不停止Main。`e2e/trackingInitialization.spec.ts`定向5/5通过，覆盖首次读取、路由切换不重复订阅、推送更新、旧读取不覆盖新推送、真实App卸载后迟到读取/已排队回调不更新、没有桌面桥的Web兼容。测试使用真实App/Store和受控桌面桥，不是本轮真实Electron验收。前端与E2E类型检查通过；E2E配置补齐与前端一致的@别名以引用实际类型。Playground提交与展示尚未切换到Store，通知未实现。

真实后端验收：开发者在桌面Console创建一次任务后交由Main查询，提供截图确认QUEUED、RUNNING、SUCCEEDED及TERMINAL，无查询错误。请求创建走Renderer/Vite，跟踪查询由Main直连本地8080。Console每条日志分别来自VM69与VM194两个订阅，不据此推断重复网络请求。此阶段仍未连接Store、页面恢复及通知。

开发者实现内存快照、受限的开始/读取/停止接口、Preload订阅与取消订阅、activeRun对象身份围栏、8秒请求超时，以及每次查询完成后等待2秒的串行调度。当前只跟踪一个任务；PAUSED可重试，同一POLLING或TERMINAL任务不重复启动，其他Job返回BUSY。Key留在Main运行对象，不进入快照。此阶段尚未把Vue原页面轮询切换到Main，也未实现Renderer Store、系统通知或完成结果读取桥。

`npm run test:tracking-boundaries`使用现有前端Playwright依赖，在隔离userData的Electron中运行正式Main/Preload/IPC，只有Main fetch被受控响应替换，不访问真实8080、数据库或真实Key。验证通过：同Job去重与不同Job BUSY；首请求未完成即使超过2秒也不重叠；停止触发abort，模拟不遵守abort的旧响应在新任务开始后返回，不修改新快照、不推送、不继续调度；真实8秒超时进入PAUSED、保留最近RUNNING快照且不继续查询；重试PAUSED后可得到终态；SUCCEEDED/FAILED/EXPIRED均停止查询，终态同任务start不重查；推送中无模拟Key。第一次测试因测试脚本误用Electron evaluate参数位置而中断，修正测试后全通过，未修改开发者业务实现。证据在.runtime/tracking-boundaries-hEWDv2/evidence.json。

## 截图另存为接入（2026-11-11）

开发者已实现 desktop.d.ts、Preload saveScreenshot(ArrayBuffer)、screenshot:save IPC 处理器和 ScreenshotPreview.vue 能力分流。Main 校验窗口主框架/来源、大小及 PNG 签名，使用用户选择路径异步写入，区分 saved/cancelled/failed 并在 finally 释放忙碌标记。通过开发者在真实 Renderer 控制台调用，确认无效输入被拒绝、真实对话框取消和保存成功；Agent检查保存文件为134字节32x32绿色PNG，签名、块CRC和图像均正常。

前端类型检查通过，现有浏览器下载定向Playwright测试1/1通过，下载PNG与模拟接口响应逐字节一致。开发者随后从真实Vue按钮确认取消安静结束、保存成功并出现提示；Agent检查 screenshot-ui-save-test.png 为80037字节、1280x720，PNG签名和所有块CRC有效，IDAT可解压。保存操作来源与提示为开发者反馈，文件结构为Agent检查；未对该真实截图与后端原响应做逐字节比较。Main写入失败、并发、非信任来源等运行时边界尚未验收。

保存边界补验通过：`npm run test:save-boundaries` 使用现有前端 Playwright 依赖启动 Electron，加载正式 Main、Preload，通过真实主页面的能力桥调用 IPC；只控制 showSaveDialog 返回并重定向测试 userData，未替换业务处理器或 fs.writeFile。第一个调用挂起时第二个返回BUSY，未增加对话框调用；取消第一个后，将第二次对话框路径指定为已存在的测试目录，真实写入失败返回SAVE_FAILED；第三次对话框选择新文件后保存成功，逐字节等于输入PNG。该测试直接调用桥接以绕过Vue按钮防重复点击状态，验证Main并发门而非按钮外观；真实系统对话框交互已由上述人工验证覆盖。不可信来源等其他边界仍未运行验收。测试文件与证据放在独立.runtime/save-boundaries-*目录，既有窗口与配置不被关闭或覆盖。

## 窗口状态检查点（2026-11-11）


最新验收：手动事件策略下四个基本场景通过。三轮不操作配置不变；只拖标题栏后位置4,31、宽高保持1284x864；右下角缩放后800x601；最大化关闭保留普通矩形，重启后开发者确认最大化与还原，最后正常关闭保存4,31、800x601、maximized=false。真实鼠标操作及可见效果由开发者确认，Agent逐次核对文件，证据为.runtime下的move-only/resize/maximize/restore-evidence.json。下文保留几何差异调查过程；这不是底层像素精度修复，也不涵盖贴靠、跨DPI与键盘布局操作。

开发者编写 window-state.cjs 和 Main 接入。普通窗口重启恢复、最大化启动后还原到普通窗口，以及模拟旧位置超出全部工作区后的回退均由开发者确认可见效果。最后一次关闭保存 x=213、y=79、maximized=false，屏幕外模拟坐标 x=2707 已被替换；备份和模拟证据保留在忽略的 .runtime 下。此验证不涵盖运行中热拔显示器，也不涵盖小于当前 800x600 最小窗口限制的工作区。

精确尺寸仍有待核实：模拟回退请求 1280x860，关闭后 getNormalBounds 保存 1284x864。尚未确认是一次性调整还是连续重启会累积；不能据可见效果通过宣称像素尺寸完全一致，后续应做不手动调整窗口的连续重启对照。

2026-11-11 连续普通启动实验证实会累积：初始 1284x864 → 第一轮 1288x868 → 第二轮 1292x872，均未移动或缩放，使用 CloseMainWindow 正常关闭。隔离几何探针在本机 scaleFactor=1.5 下发现，构造参数 1280x860，刚创建时 getBounds/getNormalBounds 均为 1284x864；尚未显示就已有差异。setBounds 请求 1280x860 后回读仍为 1281x861，最大化还原保留该数值。因此差异发生在窗口几何 API 路径，不能仅换 getBounds 或固定减 4 认定修复；DPI/边框层具体原因与兼容方案待验证。实验备份和每轮数据在 .runtime 下，实验后恢复最初窗口配置。

原生对照进一步定位（Electron 45.2.3、Windows DPI=144）：辅助进程在线程 Per-Monitor-V2 上读取 GetWindowRect、GetClientRect/ClientToScreen 和 DWM 可见边框，分别记录，不混用边框口径。位置 213,79、请求 1280x860 时，Electron dipToScreenRect 目标为 1921x1291 物理像素；构造后 Win32 外框实际 1925x1295，Electron 回读 1284x864。再次 Electron setBounds 或原生 SetWindowPos 后，外框为 1921x1291，Electron 回读 1281x861。位置仅改为 214,80 后，转换目标为 1920x1290；构造后外框 1923x1293、回读 1282x862，重新设置后外框1920x1290、回读1280x860。因此创建阶段的原生尺寸偏大与非整数坐标转换取整均参与；不是 JSON 问题，也不是单独 getter 错读，更不是恒定 4 物理像素。仍未通过 native 调试确定底层哪条边框/转换语句应修复。证据文件为 .runtime/native-bounds-evidence.json 和 native-bounds-aligned-evidence.json。初版辅助进程 SetWindowPos 同步调用因阻塞 Main 消息循环超时，改为异步调用后完成；只操作隔离窗口。

创建后立即 setBounds 的连续重启候选未通过（2026-11-11）：每组 6 个独立 Electron 进程，读取上轮配置、创建隐藏窗口、立即重设目标、加载本地实验页、显示、正常关闭保存。初始1284x864，位置213,79组每轮仍增加1 DIP，最终1290x870；位置214,80组6轮均保持1284x864。结论仅适用于本次150%屏幕测试，不能将偶数坐标组通过当作通用方案。正式 Main 未加入该候选，用户配置未改动。详细逐轮证据在 .runtime/reapply-1794381338412/evidence.json。下一步应讨论持久化策略与精度目标，避免把程序恢复产生的舍入差异自动当作用户调整后保存。

开发者随后实现手动事件标记策略：will-move 记录移动、will-resize 记录缩放；关闭时从 resolveWindowBounds 目标起步，只移动则更新坐标，缩放则更新普通矩形，独立保存最大化状态。正式 dev.mjs → Main 三轮不操作重启并正常关闭后，文件均精确保持213,79、1284x864、maximized=false；不操作时的持久化尺寸累积已被阻止，但底层创建偏差仍存在。证据在 .runtime/no-operation-evidence.json。新策略下真实移动、缩放与最大化还原待分步验收，不由该自动化结果推断通过。

## 启动

前提：Node 24.12+，nexus-console 已安装依赖。进入 nexus-desktop，首次执行 `npm ci`；随后执行 `npm run dev`。启动器会启动专用的 `127.0.0.1:5178` Vite，再启动 Electron。端口被占用会报错，不自动改端口或终止占用者。关闭窗口会退出 Electron 并关闭本轮 Vite。

无需先启动后端即可查看登录页。实际登录或业务调用需要现有本地后端：复用 Vite 配置中的 localhost:8080，前端 /api、/v1、/s 请求继续走原代理。本轮不会自动启动后端、数据库或 Compose。

## 启动与加载流程

1. `npm run dev` 使用普通 Node 执行 `scripts/dev.mjs`。
2. 启动器从相邻 nexus-console 加载 Vite，指定前端根目录，复用其插件、别名和代理；等待 `server.listen()` 完成。
3. 启动器运行 Electron 二进制，Electron 根据 package.json 的 main 执行 `main.cjs`。
4. Main 等待 `app.whenReady()`，创建 BrowserWindow，再 `loadURL` 到固定开发地址。
5. 窗口里的 Chromium Renderer 加载 index.html 及 Vue 入口；nexus-console/src/main.ts 注册 Pinia、Router、Element Plus，并挂载 App。
6. Router 检查内存会话；首次启动没有 JWT，所以跳到 /login，随后异步加载登录页。

Main 的 `loadURL` 完成代表文档加载完成，不保证 Vue 的异步组件已挂载。`ready-to-show` 用于显示窗口，实际业务就绪应按业务状态判断。

## 边界与验证

Renderer 关闭 Node 集成，启用 sandbox、contextIsolation、webSecurity；拒绝新窗口、跨来源的顶层导航和权限请求。本轮没有桌面系统操作，所以还没有 Preload。后续需要保存文件或通知时，再加入具体能力桥。不要因本地开发而关闭浏览器安全设置。

开发数据写入本项目忽略的 `.runtime/user-data`，避免与实验和普通 Electron 默认数据混用；正式用户数据位置在持久化阶段决定。使用 Vite 的开发策略，尚未完成生产 CSP 或完整桌面安全验收。

`npm run test:smoke` 启动同一链路，使用隐藏窗口，等待真实 /login 路由及登录表单挂载，同时检查 Renderer 不暴露 require/process；通过后自动退出并关闭 Vite。此检查不提交登录表单，也不证明真实后端或构建产物已验收。

## 空白窗口排查（2026-11-11）

开发者报告可见窗口空白。独立诊断确认 Vue 登录 DOM 存在，无捕获到的脚本错误；可见窗口在默认 GPU 与软件绘制下均成功显示。正式 Main 重启后也成功截图，原空白根因未确认，不能认定为 GPU 故障。隐藏窗口截图曾超时，不能当作可见窗口绘制失败的证据。

桌面 Vite 已改用 `.runtime/vite-cache`，避免与单独启动的 Web 实例共用依赖优化缓存；这是隔离并行开发的风险处理，不是已证实的空白根因。Main 新增加载失败、Renderer 退出和无响应日志；不输出凭据或请求正文。

关闭桌面实例后，可运行 `node scripts/diagnose.cjs`。它使用现有前端 Playwright 依赖，启动与桌面相同配置的 Vite 和正式 Main，等待可见登录控件并生成 `.runtime/diagnostic/desktop-login.png`，随后关闭本轮进程；端口被占用则拒绝启动。默认 GPU 的正式窗口截图与现有 5173 Web 登录页检查均通过。若空白再次出现，需保留终端输出并继续定位，不自动关闭安全检查或硬件加速。

### 普通启动对照的后续发现

上述 Playwright 检查没有复用 `scripts/dev.mjs` 的子进程启动参数，不能代表 `npm run dev` 的完整启动链。开发者再次报告空白后，改用 `npm run dev -- --diagnose`：页面登录 DOM 存在，但窗口为 visible=false。仅去掉 Electron spawn 的 `windowsHide: true` 后，首帧日志出现、visible=true，应用自身捕获的登录页完整。本桌面进程本身就需要可见窗口，不应使用隐藏子进程窗口的启动选项。保留默认 GPU 与原有安全设置。

`npm run dev -- --diagnose` 通过普通启动器采集登录页结构状态、窗口可见性及 `.runtime/normal-start.png`；不输出表单内容。它保持窗口打开，便于人工确认。截图与可见性仍不能代替开发者对系统实际显示效果的最终确认。
