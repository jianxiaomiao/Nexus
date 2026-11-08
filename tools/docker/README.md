# Nexus 本地 Docker 截图配置

`playwright-1.63.0-seccomp.json` 是本地截图验证使用的 Playwright 1.63.0 配置副本，来源：<https://github.com/microsoft/playwright/blob/v1.63.0/utils/docker/seccomp_profile.json>。Playwright 以 Apache License 2.0 发布，许可证文本见 `PLAYWRIGHT-LICENSE.txt`（上游：<https://github.com/microsoft/playwright/blob/v1.63.0/LICENSE>）。

该配置保留 seccomp 过滤，同时为使用它的整个容器额外允许 `clone`、`setns` 和 `unshare`。这扩大了容器的系统调用权限，不只作用于浏览器进程。它与 CI 中宿主机上的 AppArmor 规则作用于不同层级。当前只在本地 Docker Desktop 学习环境验证，不代表生产安全配置。

SHA-256：`CC3E61CABDA6BBC1E53E54D27BA4D55A9D3BE829B6DD1A596F4A7B31B1CC7849`。浏览器和 Playwright 版本升级时须重新检查配置和沙箱行为。

Dockerfile 中 `XDG_CACHE_HOME=/tmp/nexus-cache` 为非 root 进程提供临时字体缓存，容器删除后缓存不保留；它不用于数据库或截图结果持久化。

镜像默认异步截图结果目录为 `/var/lib/nexus/screenshot-jobs`，由 UID/GID 10001 拥有。该目录只有挂载独立数据卷后才能跨容器重建保留；已有卷的权限不会被镜像中的目录权限自动修复。数据库中的任务元数据和截图文件必须一起考虑；结果按现有业务规则只保留一小时，不是永久归档。同步截图直接返回 PNG，不写入此目录。

## 本地 Compose 学习环境

Java 21、Playwright 1.63.0、Nginx 1.27 和 MySQL 8.4 使用标签加 SHA-256 摘要固定。摘要阻止标签指向新内容时静默变更，但也不会自动获得安全更新；升级时须核对官方镜像、审查摘要、重新构建并验证。Playwright 镜像包含 Java 25，本项目从固定的 Temurin 镜像复制并使用 Java 21。摘要固定不保证本地 JAR、前端 `dist` 或外部网页数据可复现。

`compose.yaml` 创建独立项目 `nexus-docker-compose-lab`，包括 MySQL、Nexus、专用网络以及数据库和截图结果两个数据卷。MySQL 不发布主机端口；Nexus 只发布 `127.0.0.1:18091`。Webhook 调度当前关闭，日志写入容器的临时目录。该环境不复用原有手工实验或 FlowOps 数据。

时间约定统一为：内部 UTC、API 明确携带时区、控制台按北京时间显示。MySQL 显式使用 `--default-time-zone=+00:00`；应用 JDBC 使用 `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`，同时约束驱动及连接会话。JVM 默认 UTC，日志时间带 `Z`，日志文件按 UTC 日期滚动。覆盖 `JAVA_TOOL_OPTIONS` 添加其他 JVM 参数时，须保留 `-Duser.timezone=UTC`。Usage 的自然日统计仍按北京时间，配额周期仍为固定 720 小时。

2026-11-08 开发者已明确授权清理本机 `nexus-docker-compose-lab` 的两个旧实验数据卷，随后完成新镜像和 UTC 配置升级；保留了原秘密文件。升级不自动适用于其他环境：旧镜像对 Application 时间的解释与新代码不同，已有 `DATETIME` 及配额计数不能仅凭当前服务器时区判断含义。对其他旧库须先确认数据处理范围，再一起更新镜像与运行配置；不能只修改时区或给历史时间直接补 `Z`。详见 [时间约定](../../docs/UTC_TIME_DESIGN.md)。

前端使用 `nexus-console` 已构建的 `dist` 和 Nginx 镜像，只发布 `127.0.0.1:18092`。Nginx 通过 Compose 服务名 `nexus:8080` 转发 `/api`、`/v1`、`/s`，对 `/actuator` 返回 404，其他前端路径使用 Vue Router 回退。变更前端源码后先在 `nexus-console` 运行 `npm run build`，再执行 `docker compose ... up -d --no-deps --build frontend`；前端 Docker 构建不会自动执行 npm 构建。Nginx 使用 Docker 内部 DNS 定期重新解析后端名称；解析结果最多缓存 10 秒，重建期间或缓存过期前请求可能暂时失败。

运行前准备 Git 忽略的 `D:\Nexus\data\docker-compose-lab\local.env`：包含 `NEXUS_LAB_DB_PASSWORD`、`NEXUS_LAB_DB_ROOT_PASSWORD`、`NEXUS_LAB_JWT_SECRET`、`NEXUS_LAB_WEBHOOK_KEY` 和 `NEXUS_LAB_SECCOMP_PATH`。JWT 和 Webhook 密钥分别使用随机 32 字节的标准 Base64；seccomp 路径指向本目录下的配置文件。秘密文件为明文，不要提交或输出。环境变量会覆盖同名文件值；数据库首次初始化后，改文件不会自动更改数据库密码。保存好 Webhook 密钥，变更会影响已有加密数据的读取。

### 首次使用

以下操作面向 Windows PowerShell 7、Docker Desktop Linux 容器、本地 Java 21、Maven 3.9+ 和项目要求的 Node 版本。首次构建可能需要下载 Maven/npm 依赖和固定摘要镜像。先确认 Docker Desktop 已启动，且 `18091`、`18092` 均没有其他监听：

```powershell
docker version
Get-NetTCPConnection -State Listen -LocalPort 18091,18092 -ErrorAction SilentlyContinue
docker compose ls --all
docker volume ls --filter 'name=nexus-docker-compose-lab'
```

若已有同名项目或卷，先确认它属于此学习环境并核对是否需要保留；不要重建秘密文件或删除数据。端口若已占用，先排查，不要停止其他应用抢占端口。

在仓库根目录构建 JAR 和前端静态文件：

```powershell
Set-Location D:\Nexus\nexus-server
mvn -DskipTests package
# 前一条成功后再继续。
Set-Location D:\Nexus\nexus-console
npm ci
npm run build
```

`mvn -DskipTests package` 仍编译测试，但不执行测试；完整测试应先按 `docs/LOCAL_TESTING.md` 准备专用测试库。`npm ci` 会重建 `node_modules`；若已有需保留的本地依赖状态，先确认。构建产物不是秘密，也不随 Git 提交。

首次创建秘密文件：

```powershell
Set-Location D:\Nexus
.\tools\docker\New-LocalEnv.ps1
$labEnvFile = 'D:\Nexus\data\docker-compose-lab\local.env'
```

脚本使用系统随机数生成 MySQL 密码和两个独立密钥；会检查当前进程是否有会覆盖配置的同名环境变量，并通过独占创建拒绝覆盖已有文件。文件继承父目录访问权限，必须自行检查并限制对该明文文件的访问；不要输出内容、不要把它放到云同步或共享位置。默认文件已经存在时不要重跑；必须保留原文件，尤其是已存在数据库或 Webhook 加密数据时。脚本生成的 seccomp 路径是当前机器的绝对路径，移动仓库后需要在本机秘密文件中更新该路径。

首次构建并启动：

```powershell
docker compose --env-file $labEnvFile -f D:\Nexus\tools\docker\compose.yaml config --quiet
docker compose --env-file $labEnvFile -f D:\Nexus\tools\docker\compose.yaml up -d --build
docker compose --env-file $labEnvFile -f D:\Nexus\tools\docker\compose.yaml ps
docker compose --env-file $labEnvFile -f D:\Nexus\tools\docker\compose.yaml logs --tail 40 nexus
```

仅在配置检查成功后启动。首次启动预期 MySQL 健康、Nexus 在 `nexus_compose_lab` 完成 Flyway 迁移并启动；打开 `http://127.0.0.1:18092` 验证前端。不要运行会展开完整环境值的 `docker compose config` 并分享输出。Docker 容器配置对有相应 Docker 权限的用户可见，本方法仅用于隔离的本地学习环境。

在 PowerShell 中，以当前本地文件启动和停止：

```powershell
$labEnvFile = 'D:\Nexus\data\docker-compose-lab\local.env'
docker compose --env-file $labEnvFile -f D:\Nexus\tools\docker\compose.yaml up -d
docker compose --env-file $labEnvFile -f D:\Nexus\tools\docker\compose.yaml ps
docker compose --env-file $labEnvFile -f D:\Nexus\tools\docker\compose.yaml stop
```

`stop` 保留容器与数据卷；不要用 `down -v` 清理需要保留的数据。修改 Java 源码后须重新 Maven 打包，再构建镜像并重建容器；当前 Dockerfile 直接复制已打包 JAR，Compose 不会替代 Maven 打包或测试。MySQL 的 `mysqladmin ping` 健康检查只表示服务响应，不等于已验证业务账号、Flyway 或 Nexus 业务状态。

需要更新镜像时，先分别在 `nexus-server` 执行适合当前数据库边界的 Maven 打包、在 `nexus-console` 执行 `npm run build`；`-DskipTests` 会跳过测试执行，不代表回归通过。然后可用 `docker compose --env-file $labEnvFile -f D:\Nexus\tools\docker\compose.yaml up -d --build` 更新服务。镜像使用本地 JAR 和 `dist`，如果不重新生成，它们不会自动包含源码修改。

2026-11-08 开发者在 Docker Desktop 本地验收：新库 Flyway V1–V13 成功，注册和登录成功；停止再启动、重建 MySQL 和 Nexus 容器后原用户仍可登录。Compose 异步截图成功，重建 Nexus 容器后下载同一结果，前后 SHA-256 一致。一次同步截图曾返回 502，重试成功，失败原因未确认。此记录不代表 ECS、公开部署、完整功能回归或生产安全验收。

2026-11-08 前端接入本地 Compose：前端构建及类型检查通过，Nginx 配置检查通过；HTTP 验证登录页、SPA 回退、管理和机器接口代理、短链路由及 Actuator 拒绝，真实浏览器登录和真实 UUID 调用成功。经前端代理的首次截图请求返回 502，Nexus 日志为 `SCREENSHOT_PAGE_FETCH_FAILED` / `DNS_FAILURE`；当时容器及 Windows 对目标域名均解析到 198.18.0.0/15 特殊用途地址，按现有 DNS 策略拒绝。随后开发者报告重新请求返回 200、112976 字节 PNG，Agent 检查图片包含目标文章；资源请求上限标记为 true。此次成功请求未提供 DNS 查询结果，不能据此推断网络环境的具体变化；DNS 策略未放宽。

同日调整前端 DNS 解析后，Nginx 配置检查通过；单独重建 Nexus、保持前端容器不变后，代理恢复，注册、登录、带分页参数的 Application 查询及 Actuator 404 通过。随后确认无未完成截图任务，用专用临时容器占用旧地址并重建 Nexus：后端 IP 从 172.21.0.3 变为 172.21.0.5，前端容器 ID 不变，UUID 未认证探针恢复预期 401，真实注册、登录和带分页参数的查询通过；临时容器已删除，MySQL 和截图数据卷仍挂载。IP 地址仅是本次观察，不应写入配置。

同日锁定四个基础镜像摘要后，固定摘要镜像构建通过；临时无网络容器验证 Java 21、UID 10001、可写字体缓存和启用沙箱的 Chromium 空白页，前端 Nginx 配置检查通过。前端 E2E 类型检查通过，模拟浏览器回归 92/92。后端使用仅监听本机 13385、测试结束即删除的 MySQL 8.4 内存临时容器及专用 `nexus_test` 用户执行 `mvn -o test`，最终 619/619、0 失败/错误/跳过，其中包含真实 Chromium 与 Webhook 进程崩溃恢复测试；不是 `clean test`。首次执行因临时 MySQL 默认 UTC 导致 15 个配额时区断言失败，额外设置 Flyway 独立用户时连接池 JDBC 指标断言失败；首次报告保存在 Git 忽略的 `data/docker-acceptance/20261108-first-reports/`。后续一次针对性复验因错误地将 Flyway 环境变量设为空值导致空用户连接失败，报告保存在同目录下的 `20261108-second-reports/`；改为明确移除变量、数据库时区设为 UTC+08 并核实后，失败场景复验及完整回归通过。未更改业务断言。

固定摘要配置随后在本地 Compose 中生效：重新创建服务后 MySQL 健康、Flyway V13 无需迁移，前端代理 401、注册、登录、创建 Application/Key 和 UUID 调用通过；此次验收使用的数据写入 Compose 隔离库。未重新进行外部真实网页截图；此前用户验收的截图与本次 Chromium 空白页验证属于不同证据。


### UTC 调整后的隔离验收（2026-11-08）

时间约定、配置和详细证据以 [UTC 时间设计](../../docs/UTC_TIME_DESIGN.md) 为准。完整后端 638/638、前端模拟浏览器 103/103、替代 JVM 时区聚焦 67/67 通过。新项目 nexus-utc-acceptance 在 18191/18192 使用新卷，验证真实代理注册/登录、Application/Key、UUID、UTC 响应、MySQL UTC 和 Chromium 沙箱；本轮临时资源已移除。原 nexus-docker-compose-lab 的三个容器 ID 和数据卷未变，尚未应用这次 UTC 代码及配置，不能把旧验收记录当成新版本已升级。


2026-11-08 随后经开发者确认，清理并重建本机两个 Compose 实验卷，使用原秘密文件启动新版本。注册、登录、Application/Key、UUID 和重建后登录/Key 验证通过；新环境的外站异步截图任务失败，本轮未完成截图文件跨容器重建验证，见 [UTC 时间设计](../../docs/UTC_TIME_DESIGN.md)。

随后开发者报告网络调整后异步截图任务成功，Nexus 容器重建后重新下载的 PNG 与重建前 SHA-256 一致；结果仍遵循过期规则。
