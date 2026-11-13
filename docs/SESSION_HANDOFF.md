# Nexus Session Handoff

## Electron 开发与窗口持久化检查点（2026-11-11）

- nexus-desktop 复用 nexus-console 的 Vue 与 Vite，固定回环 5178，代理到本地 8080；开发者确认桌面真实登录和 Application 列表加载成功。独立构建和正式转发入口、续期及任务通知尚未实现。
- 普通启动空白曾由启动器 windowsHide:true 触发显示差异，移除后开发者确认界面正常；保留 GPU 与浏览器安全设置。Playwright 启动绕过开发启动器，不能单独证明普通启动通过。
- 开发者编写 window-state.cjs 与 Main 接入。窗口配置通过同目录临时文件替换保存；screen 工作区校验不通过时回退主屏默认居中。当前仅支持启动时布局变化，不涵盖运行中热拔屏和小于800x600的工作区。
- Windows 150%缩放下存在创建/回读几何偏差；原生窗口对照已定位创建尺寸与矩形转换均参与，但未证明具体底层源码修复点。创建后 setBounds 候选不能通用收敛，未采用。详细证据与限制见 nexus-desktop/README.md。
- 采用 will-move / will-resize 标记，关闭时以 resolveWindowBounds 目标为基准：不操作保留目标，只移动更新坐标，缩放更新普通矩形，独立保存最大化标记。正式启动三轮不操作配置完全不变；真实移动后宽高仍1284x864；右下角缩放后保存800x601；最大化关闭保留普通矩形，重启最大化、还原后再关闭恢复maximized=false。实际操作由开发者完成并反馈，Agent逐步核对文件。最终配置为x=4、y=31、800x601、maximized=false，证据在忽略的.runtime目录。
- 该策略阻止保存值累积，未修复底层创建偏差；Windows贴靠、跨DPI拖动、键盘布局操作尚未验收。
- 截图另存为基本闭环完成：开发者实现Preload/contextBridge、受限screenshot:save处理器及Vue能力分流。前端类型检查、现有浏览器下载定向测试1/1通过；开发者确认真实Vue按钮取消安静结束、保存出现成功提示，Agent核对产物80037字节、1280x720、PNG签名/CRC/IDAT解压正常。`npm run test:save-boundaries`补验Main并发BUSY、真实目录写入失败后再次保存成功且PNG字节一致；Playwright使用真实桥接/IPC/文件系统，仅控制对话框返回并隔离userData，不涉及后端。非信任来源等其他边界仍未运行验收。下一候选任务通知，代码继续由开发者先写，Agent解释与审查。

## 多端规划（2026-11-10）

- 最新清理检查点（2026-11-13）：开发者实现Store.clearCurrentTask、clearing/clearError及提交/取图入口保护；停止返回值仅确认、不写回快照，IDLE推送复用applySnapshot清图与使旧请求失效，失败保留当前状态，已收IDLE后Promise拒绝不误报失败。Agent仅接清理按钮、提示和界面禁用条件，未改Store业务代码。源码/E2E类型检查通过；desktopScreenshotDisplay与trackingInitialization共20/20通过（真实Vue/Store，模拟桥/HTTP），包括IDLE先到和返回先到、失败/异常后重试、已收IDLE后异常、取图中清理拒绝迟到PNG，以及清理完成后第二次POST获得不同Job ID并预览新PNG。真实Electron清理后再创建仍待开发者人工验收。未解决pendingHandoffJob或creationUncertain：清理不解除其防重复提交保护；恢复接手和系统通知仍待实现。本条更新下方此前“页面清理任务尚未接入”的历史状态。

- 最新取图检查点（2026-11-13）：开发者确认真实Electron切换Applications返回后状态恢复并到达完成；随后通过真实Preload/IPC收到80105字节ArrayBuffer及no-store。开发者实现Main的screenshot-result:get（独立取消、8秒超时、流式5MiB上限、PNG签名、run身份校验）及Store自动取图、失败手动重试、请求版本失效。Agent本轮仅接页面加载/重试/预览模板与预览保留说明插槽，未改Store业务代码。源码/E2E类型检查通过；桌面展示、根部订阅和选定Web截图回归共20/20通过，真实Vue/Store配模拟桥/HTTP；新增验证含重复成功推送及直接调用去重、失败不自动重试、手动重试成功、PNG真实解码、离页预览恢复不重取，以及清理后同Job ID旧成功/失败/异常和finally不覆盖新请求。2026-11-13开发者随后反馈真实Electron自动取图、预览、离页返回、放大、取消保存和保存全部正常，并确认返回页面不会重新获取图片；这是开发者人工验收反馈，非Agent新增端到端测试。恢复接手、页面清理任务和系统通知仍未接入。下方2026-11-12条目为此前检查点。

- 最新Renderer检查点（2026-11-12）：开发者已实现snapshot Store、根部订阅初始化、App清理和桌面提交action/页面分流。后端创建成功即保留pendingHandoffJob，Main接手失败保留Job ID；创建结果不确定以creationUncertain阻止重复POST；start返回值不覆盖推送快照。经开发者指定，Agent补上Playground桌面状态展示分支，并将按钮文字统一读取requestLoading。源码/E2E类型检查通过；trackingInitialization.spec.ts与desktopScreenshotDisplay.spec.ts受控桥接测试9/9通过，覆盖首次等待、离页推送返回恢复、接手失败/IPC异常、迟到start返回不覆盖新终态、根部读取和清理。测试使用真实Vue/Store、模拟桌面桥与HTTP，不代表真实Electron离页验收。桌面不再运行旧Renderer轮询；恢复接手、清理当前任务、PNG结果获取和通知尚未接入，真实离页恢复待开发者验证。

- 2026-11-12 Main单任务跟踪已由开发者实现。`npm run test:tracking-boundaries`隔离Electron/真实IPC/受控Main fetch验证去重、串行、停止后迟到响应隔离、8秒超时PAUSED保留旧状态、恢复和三个终态停查通过。开发者随后在真实桌面Console创建任务并交给Main，截图确认QUEUED→RUNNING→SUCCEEDED，跟踪最终TERMINAL、无查询错误。前次失败任务已通过stop清理后重新创建；此前VPN为开发者反馈，未将其认定为确证根因。每条日志出现在VM69与VM194两处，说明残留两个Console订阅，不能据此认定重复轮询。下一步接Renderer Store和页面恢复，替换桌面旧轮询，最后接通知；离页/最小化端到端仍待验收。详见nexus-desktop/README.md。

- 2026-11-12 截图跟踪边界已确认：第一版只跟踪一个任务，切换页面后保留跟踪和状态，桌面需支持应用运行时的后台完成通知；点击×退出程序后不再本地跟踪，不引入托盘驻留。后端已接受的任务不因退出客户端而取消。状态/跟踪协议及Web与Main监控分工仍处于设计，尚未改动轮询实现；代码由开发者先写。

- 开发者确认 Mobile MVP 为只读：登录、Application 列表与详情、Quota 和 Usage；移动端沿用 Web 按 API Key 查看 Usage。先开发 Electron；随后 uni-app 验证顺序为 Android → iOS → 微信小程序，同仓维护独立客户端。后续移动端和小程序允许创建或轮换凭据，一次性展示和复制参考 Web；这些写操作不属于只读 MVP。
- 登录目标包括 Refresh Token 和多设备长期会话；当前尚未实现，设备会话、续期、撤销和安全存储方案待设计。
- 管理 API 暂不引入版本前缀，长期版本兼容策略待定；多端网络与部署入口暂不讨论、待定。共享契约包是否以及何时抽取待讨论，不预设实施。
- Electron 关注任务通知和截图下载，安全存储的具体需求待明确；不以通用文件工作流为目标。域名 myxiaomiao.com 正在备案，目前先做本地自用，备案完成后再继续服务器域名与 Nginx 入口工作。

## Webhook 管理与真实投递闭环（2026-11-09）

Webhook 管理后端已接通现有前端契约：Bearer JWT 下按 Application 归属查询、创建、更新 URL/启停和轮换 Secret。配置与首个 AES-GCM 加密 Secret 同事务提交，Application 唯一约束处理并发创建；轮换携带 `currentSecretVersion`，在锁内比较版本并原子切换 CURRENT Secret，旧 Secret 保留一小时 GRACE。完整 Secret 只在创建或轮换响应中返回。保存 URL 时预检 HTTPS、公网域名和全部解析地址，实际投递仍在连接时重新校验。

验证包括后端编译、HTTP 契约与 URL 策略 8/8、真实 MySQL 管理事务 3/3，以及“管理 Service 创建配置和 Secret → 截图任务终态 Outbox → 真实本地 TLS 接收端先 503 后 200 → 重试保持 eventId 和请求体 → HMAC 验签”1/1。前端构建、源码/E2E 类型检查通过，Webhook 页面 Playwright 4/4 通过。隔离 MySQL 容器已删除，原 Compose 数据未改动。

2026-11-10 开发者反馈：已通过真实浏览器连接新后端完成人工验收。未提供逐项验收结果及所使用的部署环境；是否已将新代码打包并更新本地 Compose 仍待确认，不将本次反馈推断为 Compose 更新或真实投递验收。

## 完整 Compose 重建验证（2026-11-09）

按开发者请求，使用 base + monitoring 配置执行不带 -v 的 `down` 和 `up -d --no-build`。五个 Nexus 服务容器 ID 均变化，MySQL、截图、Prometheus、Grafana 四个卷保留；用户数仍为 2、Prometheus 重建前历史样本一致、Grafana 卷标记保留后精确删除。目标 up、Grafana 登录页 200、前端未认证 UUID 401，FlowOps 十个容器 ID 不变。Grafana API 验证 14 面板、数据源 OK，HTTP 状态面板查询 200 且 3 组数据；尚未由 Agent 观察开发者浏览器的视觉显示。截图卷存在，但本轮未重测截图文件，已有结果按业务规则过期。详见 tools/observability/docker/README.md。

## Docker 监控检查点（2026-11-09）

开发者授权完成 Prometheus/Grafana Docker 接入。可选覆盖文件 `tools/docker/compose.monitoring.yaml` 使用 Nexus 独立监控网络，Grafana `127.0.0.1:13000`、Prometheus `127.0.0.1:19090`，FlowOps 的 3000/9090 保持使用。后端管理 8081 不发布主机端口，但监听容器所有网络接口，其他默认网络容器也可能访问；仅供本地学习。旧原生监控数据不迁移。

使用本机已缓存固定摘要的 Prometheus 3.5.0 / Grafana 12.1.1，生成独立本地明文管理员密码文件；数据卷和首次启动细节见 `tools/observability/docker/README.md`。真实 UUID 成功后 Prometheus HTTP 200 计数 0→1；Grafana 数据源健康、14 面板加载且实际查询成功。监控容器重建后 Prometheus 历史样本及 Grafana 数据均保留，临时验收文件夹已删除；浏览器曲线仍待开发者观察。监控验收创建一组新用户/Application/Key 保留在本地库，未输出凭据。

不要使用带 `-v` 的 Compose down，避免删除现有 MySQL、截图及监控卷。Grafana 管理员密码只在初始化时生效，修改密码文件不会重置已初始化的账户。ECS 和 FlowOps 未修改。

## 当前阶段（2026-11-08）

Docker Compose 和 UTC 时间调整已完成本地验证。经开发者明确授权，旧 Compose 实验数据库和截图卷已重建，原秘密文件保留；新镜像和 UTC 配置已应用到 nexus-docker-compose-lab，ECS 未修改。

开发者随后报告：异步截图任务 SUCCEEDED，Nexus 容器重建后 ID 改变，重新下载的 PNG 与重建前 SHA-256 一致。这是开发者提供的验收结果；结果文件仍按业务规则过期，不是永久归档。

## 重要约定

- 内部绝对时刻统一 UTC，API 返回明确偏移，控制台使用北京时间；Usage 自然日按北京时间，配额周期固定 720 小时。
- Docker Compose 使用独立数据库卷与截图卷；保存本地秘密文件，不提交 data、密码、API Key 或令牌。Webhook 租约仍由数据库 UTC 时间判定。
- Webhook 管理 Controller/Service 已实现并接入前端轮换版本契约；代码级管理到真实 HTTPS 投递闭环已验证，开发者反馈已完成真实浏览器连接新后端的人工验收；本地 Compose 更新状态待确认。
- 后端完整回归 638/638、前端模拟浏览器回归 103/103；隔离 Compose 与真实本地 Compose 的登录、Key、UUID、UTC 和容器重建验证见 docs/UTC_TIME_DESIGN.md。
- 开发者希望先说明每一步的目的、验证内容及遗漏的影响，机械工作由 Agent 完成，关键结果用于教学。

## 下次会话

开发者准备关机。下次开机后先只读检查 Docker Desktop 和 nexus-docker-compose-lab 的启动状态，再根据实际状态处理。不要修改 ECS 或清理数据，除非获得明确授权。先读 AGENTS.md、docs/UTC_TIME_DESIGN.md 和 tools/docker/README.md，以仓库与实际状态为准。
