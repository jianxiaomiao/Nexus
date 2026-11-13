# Nexus Desktop：阶段 1 开发壳

当前复用 `../nexus-console` 的 Vue 源码与 Vite 配置，仅支持本地开发模式。Electron Main 创建窗口并加载 Vue；已接入窗口状态文件持久化和任务完成通知，尚未实现安装包、独立构建入口和登录续期。

## 任务完成通知验收（2026-11-13）

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
