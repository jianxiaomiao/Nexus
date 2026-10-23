# Nexus 本地可重复测试

以下命令以 Windows PowerShell 为例。后端需要 Java 21、Maven 3.9+ 和本机 MySQL；前端需要 Node.js 24、npm。先在 MySQL 中准备**专用测试库**（例如 `nexus_test`）及只对该库有权限的本地用户。可用 MySQL 管理工具执行以下 SQL，并把占位密码替换成新生成的本机密码：

```sql
CREATE DATABASE nexus_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'nexus_test_user'@'127.0.0.1' IDENTIFIED BY '<本机新密码>';
GRANT ALL PRIVILEGES ON nexus_test.* TO 'nexus_test_user'@'127.0.0.1';
```

不要把测试指向日常开发库或生产库；集成测试会创建、修改和清理业务数据。Flyway 会在 Spring 启动时应用 `nexus-server/src/main/resources/db/migration/` 中的迁移。

后端测试 classpath 另有启动前保护：`mvn test` 的 Spring 上下文只接受最终生效的 MySQL schema 为 `nexus_test`，并检查单独设置的 Hikari/Flyway URL 和 Flyway schema；缺失或不匹配时，在连接数据库前拒绝启动。这个保护不随正式应用打包，但运行真实后端验收时仍须人工确认启动日志中的 Flyway schema。本机配置可能保留注释掉的旧 `nexus` URL；判断生效值时不要只搜索文件里的第一个 JDBC URL。

## 1. 显式选择 local Profile，配置测试库与临时 JWT 密钥

公共 `application.yml` 不再导入本地文件。每次启动本地后端或运行后端测试，都必须显式设置 `SPRING_PROFILES_ACTIVE=local`；没有 Profile 时不能静默回退到本地数据库。完整边界及生产模板见 [部署配置边界](DEPLOYMENT_CONFIGURATION.md)。

在 `nexus-server/config/application-local.yml` 配置测试库连接。该文件已被 Git 忽略，不提交真实密码；至少需要以下属性：

```yaml
server:
  address: 127.0.0.1
  port: 8080
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/nexus_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8
    username: <本机测试库用户>
    password: <本机测试库密码>
    hikari:
      maximum-pool-size: <按本机资源设置>
      minimum-idle: <按本机资源设置>
nexus:
  auth:
    jwt:
      issuer: nexus
      access-token-ttl: 30m
      secret: ${NEXUS_JWT_SECRET}
```

在运行 Maven 的同一个 PowerShell 窗口生成本次运行专用的 JWT 密钥，勿把输出写进仓库：

```powershell
Set-Location D:\Nexus\nexus-server
$env:SPRING_PROFILES_ACTIVE = 'local'
$jwtBytes = [byte[]]::new(32)
[Security.Cryptography.RandomNumberGenerator]::Fill($jwtBytes)
$env:NEXUS_JWT_SECRET = [Convert]::ToBase64String($jwtBytes)
mvn test
```

`mvn test` 包含不依赖数据库的单元/MVC 测试，以及使用真实 Spring、Flyway、MySQL 和随机 HTTP 端口的集成测试。若连接失败，先检查 MySQL 是否运行、测试库用户权限、当前工作目录和本地配置；若 JWT 属性缺失，确认环境变量设在运行 Maven 的同一窗口。不要用跳过测试或关闭 Flyway 来掩盖这些错误。

若完整套件在本机出现 MySQL `Too many connections`，可以在**当前测试进程**中限制每个 Spring 上下文的连接池，再重跑：

```powershell
$env:SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = '2'
$env:SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE = '1'
mvn test
```

这只调整测试期间的连接池上限；不要以此替代检查数据库其他连接占用。运行前仍须按上文生成本次专用的 `NEXUS_JWT_SECRET`。

## 2. 前端构建与模拟浏览器回归

2026-10-14 配额开发者文档检查点：构建、E2E类型检查通过；`npx playwright test e2e/docs.spec.ts e2e/scrollbars.spec.ts --workers=2` 定向9/9。验证九篇文档入口、配额正文链接保留内存会话、查询示例复制不发送API、手机阅读与原滚动/锚点。此次允许测试子进程正常启动和关闭，未出现清理挂起；未重跑完整前后端套件或真实接口。

2026-10-14 配额页面检查点：构建、E2E类型检查通过，模拟响应完整回归82/82。配额定向可用 `npx playwright test e2e/quota.spec.ts`，覆盖列表概要、刷新失败/重试/加载、跨周期新值、更新不丢quota、零上限，以及1440px/390px和非北京时间设备。截图保存在忽略的 `test-results/quota-*/`。本轮没有写入数据库，也未完成真实页面联调。该次用例全部结束后，受限环境的测试服务清理挂起；核实命令及4173端口后仅终止本轮服务，测试最终正常退出并报告82 passed。未修改Playwright配置或跳过用例。

在另一个 PowerShell 窗口运行：

```powershell
Set-Location D:\Nexus\nexus-console
npm ci
npx playwright install chromium
npm run build
npm run type-check:e2e
npm run test:e2e
```

Playwright 测试自行启动 `127.0.0.1:4173` 的 Vite 服务，使用模拟的 `/api/` 和部分 `/v1/` 响应；不要求 Spring 或 MySQL 正在运行。端口被占用时先释放该端口。失败截图和 trace 在 `nexus-console/test-results/`。这组测试验证界面行为，不能替代下面的真实后端验收。

## 3. Usage 页真实后端验收

使用可丢弃的本地测试库。分别保持后端和前端两个窗口运行，前端默认在 `http://localhost:5173`：

```powershell
# 窗口 A：在 nexus-server，配置并生成本次运行的 NEXUS_JWT_SECRET 后
$env:SPRING_PROFILES_ACTIVE = 'local'
mvn spring-boot:run
```

```powershell
# 窗口 B
Set-Location D:\Nexus\nexus-console
npm run dev -- --host localhost --port 5173 --strictPort
```

第三个窗口执行：

```powershell
Set-Location D:\Nexus\nexus-console
node scripts/accept-usage-real.mjs
```

脚本通过真实 HTTP 创建随机测试账号、Application 和**全新** API Key；用该 Key 调用 UUID，并轮询异步 Usage 写入。随后在 Chromium 中登录控制台，通过页面内链接进入 Key 的“调用统计”，核对累计数、`uuid.generate`、今天及自定义北京时间范围；禁用 Key 后核对机器接口 403 和历史可见，再重新启用并调用。成功时输出 `PASS` 和非敏感的资源 ID。脚本在结束时软删除本次创建的 Application；注册的测试账号仍留在测试库中。因此建议在可重建的专用测试库运行，不要在正式数据中反复执行。

若服务使用其他地址，可设置 `NEXUS_ACCEPT_API_ORIGIN` 和 `NEXUS_ACCEPT_CONSOLE_ORIGIN` 后再运行脚本。脚本不会打印或持久化密码、JWT、完整 API Key。当前控制台会话只保存在页面内存，登录后通过页面内链接进入 Usage 页；整页刷新会回到登录页。

## 4. Usage 逐条记录分页

`GET /api/usage/events` 使用管理端 Bearer JWT，必填 `applicationId`、`apiKeyId`；`current` 默认 1，`size` 默认 10，允许 1–100。响应 `data` 包含 `total`、`current`、`size` 和 `records`，每条只展示 `apiCode`、`httpStatusCode`、`durationMs`、`occurredAt`（带北京时间偏移）。记录按调用时刻倒序，同一时刻按内部 ID 倒序；上方统计时间范围不限制逐条历史。已禁用 Key 可查历史，已删除 Key 不可查；Filter 拒绝的请求和公开短链跳转仍不写入 Usage。

可重复验证：在专用测试库运行 `mvn -q -Dtest=UsageHttpIntegrationTests test`，它插入同一时刻的 11 条记录，并验证默认第一页 10 条、第二页 1 条、自定义每页 5 条、禁用历史、无权访问及非法页大小。运行 `npm run test:e2e -- e2e/usage.spec.ts` 验证浏览器翻页和每页条数输入。完整回归仍按第 1、2 节执行；第 3 节现有真实浏览器脚本只覆盖 Usage 汇总与禁用历史，不覆盖 11 条翻页。

## 5. API Key 轮换真实浏览器验收

先确认后端实际连接的是**可丢弃的 `nexus_test`**：真实服务启动不带测试 classpath 保护，不能未经核对就运行验收。可在启动后端的同一个 PowerShell 窗口临时设置 `$env:SPRING_DATASOURCE_URL` 为 `jdbc:mysql://127.0.0.1:3306/nexus_test?...`，并按第 1 节生成本次专用 JWT 密钥；启动日志中的 Flyway schema 必须明确是 `nexus_test`。前端 Vite 默认把 `/api`、`/v1` 和 `/s` 代理到 `localhost:8080`，所以也要确认 8080 没有其他指向日常库的后端实例。分别启动 `mvn spring-boot:run` 和 `npm run dev -- --host localhost --port 5173 --strictPort` 后，在 `nexus-console` 运行：

```powershell
node scripts/accept-rotate-real.mjs
```

脚本在真实后端创建随机测试账号、Application、全新 Key 和短链，再通过 Chromium 页面点击轮换，验证旧凭据 401、新凭据 200、旧短链仍归原 Key 且能跳转、Usage 历史连续、列表中 Key ID 不变且 Public ID 更新。脚本不打印或持久化密码、JWT、完整 Key；结束时软删除本次 Application，但测试账号仍留在 `nexus_test`，因此只在可重建的专用测试库运行。若服务地址不同，可使用 `NEXUS_ACCEPT_API_ORIGIN`、`NEXUS_ACCEPT_CONSOLE_ORIGIN` 覆盖；还要确保 Vite 代理与所测后端一致。

## 6. 网页正文提取验收

浏览器场景在 `nexus-console/e2e/webExtract.spec.ts`：固定响应验证机器 Key 请求、正文/图片顺序、图片成功与失败、纯文本/JSON 切换、脚本和事件属性不执行、URL 校验、401/403/400/422/502 展示、网络失败、切换 API 取消请求、文档入口、窄屏布局和离开页面清除 Key。按第 2 节构建并运行完整 Playwright 回归即可；这些是模拟响应的浏览器测试。

真实后端验收前仍须确认运行服务的 Flyway schema 是 `nexus_test`。在 `nexus-console` 执行：

```powershell
node scripts/accept-web-extract-real.mjs
```

脚本创建随机账号、Application 和全新 Key，检查错误 Key 返回 401、不在白名单的目标返回 400，再提取一篇真实 CSDN 文章，要求 HTTP 200、纯文本至少 80 字符和净化后的正文 HTML。它不打印或保存密码、JWT、完整 Key，结束时软删除本次 Application；随机账号留在可丢弃的测试库。脚本只验收真实后端，不启动浏览器；真实文章受网络、原站访问规则和正文识别影响，失败不会被记作通过。

可用 `NEXUS_ACCEPT_API_ORIGIN` 覆盖后端地址；用 `NEXUS_ACCEPT_WEB_URL` 指定另一篇公开的知乎专栏或 CSDN 文章：

```powershell
$env:NEXUS_ACCEPT_WEB_URL = Read-Host '输入公开文章的完整 HTTPS URL'
node scripts/accept-web-extract-real.mjs
```

若出现 502，先检查本机 DNS 结果。代理的 Fake-IP 模式可能把目标解析到 `198.18.0.0/15`；该特殊地址段会被 SSRF 策略拒绝。应让允许的文章域名使用正常公网 DNS 结果，并重启后端以免复用 DNS 缓存，不应删除 IP 检查或扩大安全白名单。切换 `application-local.yml` 到 `nexus` 后也要重启后端；Key 必须由当前连接库中的应用创建。

## 7. 静态网页截图

第一版接口、Chromium 安装、PNG 保存命令与能力边界见 [静态网页截图](SCREENSHOT_API.md)。截图成功是图片字节，不能按 ApiResponse JSON 解析；失败仍返回 JSON。

前端调试台已有第五个“网页截图”入口和第八篇教程 `/docs/web-screenshot`。`nexus-console/e2e/screenshot.spec.ts` 的9个浏览器场景验证机器ApiKey与无Cookie、PNG字节下载、放大、部分截图与资源计数、401/403/400/422/502/503/504 JSON、非法目标、PNG签名/大小/解码与HTML/SVG不执行、请求取消/过时结果、临时URL释放和窄屏教程入口。单独执行 `npm run test:e2e -- e2e/screenshot.spec.ts e2e/docs.spec.ts`，或按第2节执行全套；这些使用模拟后端，不证明真实上游当前可用。

2026-10-11 前端检查点：构建和E2E类型检查通过，定向12/12，全套75/75。首次定向10/12，修复代码Scrollbar遮挡复制按钮的真实层级问题；测试错误地强制点击弹窗背景，未实际切换API，改为正常切换与弹窗打开时浏览器后退验证卸载。ConsoleLayout与DocsLayout未改，本轮未重跑后端和真实页面联调；真实后端PowerShell证据仍见下文。

2026-10-11 后续：按开发者要求，本地忽略配置已切到 `nexus_test` 和专用测试账号。新增 `nexus-server/scripts/diagnose-screenshot.ps1`，启动日志验证 schema/端口/PID 后用全新 Key 通过 PowerShell 读取 PNG 或失败 JSON；运行方法见 `SCREENSHOT_API.md`。真实 CSDN 在公网 DNS 环境复现 HTTP 422，诊断明确为 `REQUEST_COUNT observed=65 limit=64`，包括被拒绝的资源请求尝试。定向 26/26 与真实集成 3/3 通过，未放宽限制；尚无真实成功 PNG。

随后开发者选择次数超限后返回部分截图：真实 PowerShell 再次请求同一文章，得到 HTTP 200、78984 字节 PNG、blocked=58、`X-Nexus-Resource-Limit-Reached: true`。观察102次资源请求（主页面1/图片86/样式15）、成功获取44、停止后续38；截图标题正文可读，部分头像图标缺失。当前定向回归42/42（含4个真实集成用例），不是全量。Application 693/Key 423 已软删除；账号/Usage 留在测试库，临时后端已停止。调用同一个诊断脚本即可复现当前行为。

## 8. Application 限流经 Nginx 的真实验收

从仓库的 `nexus-server` 目录执行：

```powershell
.\scripts\accept-rate-limit-nginx.ps1
```

脚本拒绝占用中的端口，默认只使用回环地址的 Nginx `18080` 和后端 `18081`；可用 `-NginxPort 18082 -BackendPort 18083` 改为其他空闲隔离端口。它从被 Git 忽略的本地配置中选择 `nexus_test` 专用设置，只为自己启动的后端注入数据库凭据和一次性 JWT，不修改日常配置。脚本会打包并启动真实 Spring Boot、生成独立 Nginx 配置、创建随机账号/Application/两把 Key，验证认证、共享限流、排队放行、队列满/超时、Quota、JSON、PNG、代理状态和日志泄密，最后软删除 Application 并停止两个进程。随机账号仍留在可丢弃的 `nexus_test`；运行产物只保存在忽略的 `target/rate-limit-nginx-acceptance-*`。

如只验证限流与 JSON，可加 `-SkipScreenshot`，避免依赖真实文章和公网。默认 PNG 验收使用公开 CSDN 样本，也可以用 `-ArticleUrl '<允许域名的公开 HTTPS 文章>'` 指定文章。脚本不会输出或持久化 JWT、完整 API Key 或密码。

2026-10-16 首次生产链路验收使用隔离的 `18082 → 18083`：两把 Key 所属的同一 Application 首批 3 请求全部返回200、Quota为3；随后30个并发请求得到10个200、19个队列满429和1个等待超时429，Quota只增加10。真实截图经Nginx返回200 `image/png`、79075字节并只扣1次Quota；Nginx 20条429均与Java上游状态一致，日志未出现凭证。测试 Application 1151及两把Key已软删除，随机账号留在 `nexus_test`；所有18080–18083隔离端口均已关闭。

本地 `application-local.yml` 若已切回 `nexus` 与日常账号，数据库测试必须同时覆盖 URL、用户名和密码。仅覆盖 URL 会导致日常账号因没有 `nexus_test` 权限而初始化失败。可以在专用测试窗口设置以下环境变量，再按第 1 节生成本轮 JWT 密钥并运行 `mvn clean test`：

```powershell
$env:SPRING_DATASOURCE_URL = 'jdbc:mysql://127.0.0.1:3306/nexus_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8'
$env:SPRING_DATASOURCE_USERNAME = 'nexus_test_user'
$env:SPRING_DATASOURCE_PASSWORD = Read-Host '输入本机专用测试库账号的密码'
$env:SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = '2'
$env:SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE = '1'
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD = '1'
```

不要扩大日常账号权限来让测试运行，也不要把测试库密码写进仓库或聊天。截图集成测试使用新生成的 Key；固定上游样本保留真实 Spring/HTTP/MySQL/Chromium，验证成功 PNG、认证拒绝和 Usage。真实站点的静态内容与 CDN 资源仍需另行手工验收。

2026-10-11：截图后端编译、真实 Chromium 样本和非数据库场景通过后，首次全量运行因只覆盖库名、仍使用日常账号而出现 63 个上下文错误。确认本地文件保留专用测试账号后，仅在测试进程注入其设置及新的临时 JWT 密钥，`mvn clean test` 最终通过 229/229、零失败/错误/跳过；Flyway 实际 schema 为 `nexus_test`，未改日常配置或账号权限。测试覆盖静态 PNG、URL/MIME/重定向限制、真实 HTTP 请求取消、资源预算、并发许可、机器身份与 Usage，尚未验收真实知乎/CSDN 截图或新增前端界面。

## 9. Spring Boot 回环监听边界

从仓库根目录执行：

```powershell
& .\tools\experiments\deploy-1-loopback\run.ps1
```

脚本使用真实 Nexus JAR、真实 `nexus_test` 和隔离 Nginx `18080 → 18081`。Java 通过本次进程参数绑定 `127.0.0.1:18081`，不会修改日常 `application.yml`、忽略配置、正式 Nginx 或 80/8080。它验证直接回环和代理请求都到达机器认证 Filter、本机非回环 IPv4 不能直连 Java、Nginx 上下游状态一致，以及日常监听快照未变化。请求不带凭证并停在401，因此不创建用户、Application、Key、Usage 或 Quota 数据。完整预测、运行约束、首次脚本错误与 2026-10-17 通过证据见 [DEPLOY-1](../tools/experiments/deploy-1-loopback/README.md)。

本机结果只证明 Spring Socket 没有绑定非回环本地地址。云端仍须同时检查实际监听、阿里云安全组和 Windows 防火墙，并从外部节点分别验证直连 Java 失败、经 Nginx 成功。

## 这些测试各自证明什么

2026-10-18：移除公共配置对 `application-local.yml` 的固定导入后，清空 Profile 和外部数据源/JWT 变量执行负向启动，应用使用 `default` 并因缺少 datasource URL 在建连前退出。随后显式激活 `local`，仅对测试进程覆盖专用 `nexus_test` URL/账号/密码、Hikari 1..2 与一次性 JWT；完整后端回归 **382/382** 通过，0 失败、0 错误、0 跳过，启动日志确认 `local`、`nexus_test` 和 8 个 Flyway 迁移。DEPLOY-1 同轮复验通过，日常 80/8080 未改变。

2026-10-16：Application 生产限流器、Servlet 异步 Filter 与失败日志接入后，先确认忽略配置的生效项仍为日常 `nexus`，再仅对本次 Maven 进程同时覆盖为保留的 `nexus_test` URL、专用账号和密码，连接池上限为 2，并生成一次性 JWT 密钥。Flyway 日志确认 schema 为 `nexus_test`、8 个迁移校验通过；`mvnw clean test` 最终通过 **382/382**，0 失败、0 错误、0 跳过，包含真实 MySQL、随机端口 HTTP、Quota/Usage 和 Chromium 截图回归。本地配置文件未修改；该结果不替代经本机 Nginx 的真实限流验收。

| 层次 | 主要证明 | 不证明 |
| --- | --- | --- |
| 单元/MVC 测试 | 单个规则、输入边界、HTTP 翻译 | 真实数据库和完整服务接线 |
| Spring 集成与真实 HTTP 测试 | Spring 接线、Flyway/MySQL、认证及 API 路径 | Vue 页面在浏览器中的真实调用 |
| 模拟浏览器测试 | 页面交互、路由、请求形状和展示 | 后端状态与持久化 |
| Usage 真实验收脚本 | 新 Key → 机器调用 → 异步入库 → 管理查询 → Vue 展示及禁用历史 | 其他功能的完整人工验收 |

2026-10-08：在本地运行中的后端、MySQL 和 Vite 上，Usage 真实验收脚本通过；测试 Application 已软删除。后端完整测试 165/165、模拟浏览器测试 44/44 和构建通过是此前 Usage 检查点的结果，本次未重跑完整套件。

2026-10-08：加入逐条记录分页后，后端完整套件使用新生成的一次性 JWT 测试密钥通过 166/166，前端构建和模拟浏览器测试通过 45/45。第一次未设置密钥的完整后端测试出现 6 个上下文初始化错误；补齐临时测试配置后全部通过。新的分页验证是 Spring/MySQL/HTTP 与模拟浏览器两层，尚未通过真实浏览器执行 11 条翻页脚本。

2026-10-08：Application、API Key、短链接管理列表的现有查询已分页。可运行 `mvn -q -Dtest=ManagementListPaginationIntegrationTests test` 验证三个列表各 11 条跨页、自定义条数和权限隔离；运行 `npm run test:e2e -- e2e/listPagination.spec.ts` 验证页面翻页与页外详情。完整后端套件在限制本次测试连接池后通过 167/167，模拟浏览器回归 48/48。接口说明见 `docs/MANAGEMENT_PAGINATION.md`。

2026-10-08：Element Plus 组件与滚动条改造后，前端构建、E2E 类型检查和模拟浏览器回归 56/56 通过。提交前尝试重新运行后端全套时，从 Flyway 日志发现当前本地配置连接 `nexus` 而不是文档建议的独立 `nexus_test`，已中止运行；这次不能记作通过，也不保证中止前没有测试写入。后续只对核实属于 Usage 验收的三名 `usage-accept` 用户及其关联数据执行了定向清理，不能据此断言其他测试写入均已清除。再次运行前先核实专用测试库及连接配置，再使用新生成的临时 JWT 密钥。

2026-10-10：API Key 轮换与测试库保护收尾。当前本地配置里旧 `nexus` URL 是注释，生效 URL 为 `nexus_test`。负向实验把测试 URL 显式设为错误库名，确认在 Hikari/Flyway 建连前拒绝；后端全套 189/189、前端构建与 E2E 类型检查、Playwright 60/60 均通过。轮换真实浏览器脚本验证旧 Key 401、新 Key 200、短链和 Usage 连续；本次 Application 已软删除，随机测试账号留在可丢弃的测试库。

2026-10-10：网页正文提取前端完成后，构建、E2E 类型检查、完整 Playwright 66/66 和后端提取定向测试 14/14 通过；本轮未重跑后端全套。窄屏布局断言改成同一帧读取两张卡片坐标，重复 5/5 通过。真实后端验收确认新 Key 通过认证、无效 Key 401、非白名单目标 400，但真实 CSDN 文章返回 502；本机 DNS 将两个文章主机解析为 `198.18.0.41/42`，安全策略正确拒绝。真实文章成功验收尚未完成，不能把模拟浏览器通过当作真实站点抓取成功。此次 Application 608 已软删除，随机账号留在测试库。

2026-10-10 后续：开发者关闭 VPN 并重启后端后，反馈真实文章文本提取成功，先前的网络环境阻塞已解除。该反馈不代表 Agent 重新运行真实验收脚本，也不代表已确认两站或真实图片展示；本轮未重复运行自动化测试。
