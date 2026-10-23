# Nexus 部署配置边界

> 2026-10-22 平台变更已落地：ECS 当前运行 Ubuntu 24.04.5 LTS。本文后续 Windows 章节保留为已经验证的学习记录，不再代表待上线方案；跨平台仍有效的回环监听、外部配置、不可变发布、最小公网入口和可回滚原则继续保留。当前 Linux 的 systemd 身份、目录权限、UFW、不可变首个 release、固定 Chromium、Java 回环 8080 与 Nginx 回环 18080 均已实际验收；80/443、域名和 HTTPS 尚未启用。

## 要解决的问题

Nexus 在 Java 进程启动时选择环境，不根据 `localhost`、公网域名或 HTTP `Host` 请求头推断环境。数据库连接池、JWT 和监听地址在接收请求前就必须确定，而且请求头不是可信的部署信号。

当前配置边界如下：

| 层次 | 职责 | 是否进入 Git |
| --- | --- | --- |
| `src/main/resources/application.yml` | 公共 Flyway 与日志滚动默认值 | 是 |
| `config/application-local.yml` | 当前开发机器的真实配置 | 否，已忽略 |
| `config/application-prod.yml.example` | 生产结构、固定非秘密值和变量占位符 | 是 |
| `<部署根目录>/config/application-prod.yml` | 服务器实际配置 | 否，不复制回仓库 |
| Java 进程环境变量 | 数据库密码、JWT 密钥和其他部署值 | 绝不提交 |

公共配置不再导入 `application-local.yml`，也不指定默认活动 Profile。没有显式选择 Profile 的进程不会静默退回本地配置。

## Profile 加载

本地从 `nexus-server` 目录启动，使 Spring Boot 能发现 `./config/application-local.yml`：

```powershell
$env:SPRING_PROFILES_ACTIVE = 'local'
.\mvnw.cmd spring-boot:run
```

IDEA 也可以在 Spring Boot Run Configuration 的 **Active profiles** 中填写 `local`。

生产环境先把 `config/application-prod.yml.example` 复制为 `<部署根目录>/config/application-prod.yml`，保证它不进入 Git，再显式指定 Profile 和配置目录：

```powershell
java -jar .\nexus-server.jar `
  --spring.profiles.active=prod `
  --spring.config.additional-location=file:D:/NexusDeploy/config/
```

Windows 服务的工作目录不一定是部署根目录，因此生产启动命令使用绝对 `spring.config.additional-location`，同时保留 JAR 内公共配置。不能同时激活 `local,prod`。

## 生产环境变量契约

生产模板要求 Java 进程提供以下值：

| 变量 | 是否秘密 | 用途 |
| --- | --- | --- |
| `NEXUS_DB_URL` | 否 | MySQL JDBC 地址与 schema |
| `NEXUS_DB_USERNAME` | 通常否 | MySQL 运行账号名，只持有业务 DML 权限 |
| `NEXUS_DB_PASSWORD` | 是 | MySQL 运行账号密码 |
| `NEXUS_FLYWAY_USERNAME` | 通常否 | Flyway 迁移账号名，持有 schema 迁移所需 DDL/DML 权限 |
| `NEXUS_FLYWAY_PASSWORD` | 是 | Flyway 迁移账号密码 |
| `NEXUS_DB_POOL_MAX_SIZE` | 否 | 随设备变化的 Hikari 最大连接数 |
| `NEXUS_DB_POOL_MIN_IDLE` | 否 | 随设备变化的 Hikari 最小空闲连接数 |
| `NEXUS_JWT_SECRET` | 是 | Base64 编码且解码后至少 32 字节的 HS256 密钥 |
| `NEXUS_LOG_DIR` | 否 | 部署日志绝对目录 |
| `NEXUS_SCREENSHOT_JOB_RESULT_DIR` | 否 | 异步截图 PNG 的可写绝对目录；Ubuntu 计划使用 `/var/lib/nexus/screenshot-jobs` |
| `PLAYWRIGHT_BROWSERS_PATH` | 否 | Playwright 管理的 Chromium 安装目录 |

当前生产模板固定 `issuer: nexus` 和 `access-token-ttl: 30m`。设置任一 `spring.flyway.user` 会让 Flyway 使用独立数据源；未单独设置 Flyway URL 时继续复用 `NEXUS_DB_URL`。因此启动迁移使用迁移账号，Hikari 运行池只使用 DML 账号。日志滚动沿用公共默认值：单文件 10 MB、保留 30 期。模板把 Java 固定在 `127.0.0.1:8080`，只允许同机 Nginx 连接。

环境变量的作用是避免秘密进入 Git，不代表它能防住 Windows 管理员或以同等账号运行的其他进程；操作系统账号与服务权限属于后续部署边界。

## Ubuntu 服务身份与目录边界

Ubuntu 正式 Java 进程使用静态系统账号 `nexus`：没有登录 Shell，不使用个人账号，也不持有 sudo。发布与切换由管理员执行，服务账号只读取当前 release，并只写共享日志和运行时数据。`nexus-readers` 是 Java 和发布验收所需的发布物只读组；Nginx 的 `www-data` 不加入该组，避免顺带读取 JAR 与固定浏览器目录。Nginx 只通过精确 POSIX ACL 遍历当前 release 的父路径并读取 `console/dist`。

| 路径 | 所有者/组 | 模式 | 用途 |
| --- | --- | --- | --- |
| `/opt/nexus` | `root:nexus-readers` | `0750` | 部署根，只允许管理员修改 |
| `/opt/nexus/releases` | `root:nexus-readers` | `0750` | 不可变版本目录 |
| `/opt/nexus/browsers` | `root:nexus-readers` | `0750` | 按 Playwright 版本固定的浏览器 |
| `/opt/nexus/state` | `root:root` | `0750` | 发布成功与回滚指针 |
| `/etc/nexus` | `root:nexus` | `0750` | Java 可遍历的配置目录 |
| `/etc/nexus/nexus.env` | `root:root` | `0600` | systemd 以 root 读取的秘密环境文件，服务进程不能直接读取 |
| `/etc/nexus/application-prod.yml` | `root:nexus` | `0640` | 非秘密生产配置 |
| `/var/log/nexus` | `nexus:nexus` | `0750` | Java 日志 |
| `/var/lib/nexus` | `nexus:nexus` | `0750` | 服务 HOME 与可变运行数据 |
| `/var/lib/nexus/tmp` | `nexus:nexus` | `0750` | JVM/Playwright 临时目录 |
| `/var/lib/nexus/screenshot-jobs` | `nexus:nexus` | `0750` | 异步截图结果文件；不进入不可变 release |

`current` 稍后由 root 创建为指向某个 release 的符号链接。服务账号不得创建 release、切换 `current`、改写配置或读取环境文件；systemd 在降权启动 Java 前读取 `EnvironmentFile` 并传递所需变量。

Linux Java 单元模板位于 `tools/deployment/linux/nexus.service`。它固定使用 Java 21、`prod` Profile、外部配置目录、128–512 MiB JVM 堆和版本化 Playwright 浏览器目录；以 `nexus` 身份运行，只允许写 `/var/lib/nexus` 与 `/var/log/nexus`。单元采用 `Restart=on-failure` 和 5 分钟最多 3 次的启动限制；当前 JVM 在正常 SIGTERM 后返回 143，因此 `SuccessExitStatus=143` 把管理员停止记录为成功且不触发自动恢复。首次安装必须先通过 `systemd-analyze verify`，并保持 disabled/stopped，直到 `current` 候选、浏览器目录和启动前检查都准备完成。

Ubuntu 24.04 默认通过 AppArmor 限制下载版 Chromium 使用非特权 user namespace。Playwright headless-shell 首次直接启动因此会明确报 `No usable sandbox`；不能用 `--no-sandbox` 或全局把 `kernel.apparmor_restrict_unprivileged_userns` 改为 0。当前仅为固定版本、root 拥有且服务账号不可写的 headless-shell 精确绝对路径加载 `userns` 例外，模板为 `tools/deployment/linux/apparmor-nexus-playwright-headless-shell`。升级 Playwright 会改变路径和修订号，必须创建并验证新的精确规则，不能改成可由服务账号写入的通配目录。

## Ubuntu Nginx 回环隔离边界

Ubuntu 官方 Nginx 安装默认会创建开机启用链接并尝试立即启动。首次安装因此先临时放置只拒绝 Nginx `start/restart/reload` 的 `/usr/sbin/policy-rc.d`，其余服务动作不受影响；APT 日志必须观察到返回 101 且拒绝启动。安装后先执行 `disable --now`、删除 `/etc/nginx/sites-enabled/default` 链接，再移除临时 policy。默认站点源文件保留在 `sites-available`，但整个安装过程中 80/443/18080 都不得出现监听。

回环站点模板为 `tools/deployment/linux/nginx-loopback.conf`，可重复安装和验收脚本分别为 `install-nginx-without-start.sh` 与 `accept-nginx-loopback.sh`。有效配置只能包含 `listen 127.0.0.1:18080`：`/api`、`/v1`、`/s` 代理到 `127.0.0.1:8080`，其余路径由 `/opt/nexus/current/console/dist` 提供并使用 Vue Router history fallback。代理层覆盖而不是追加客户端提供的 X-Forwarded-For，并清空 `Forwarded`，为以后把 Nginx 作为唯一可信入口保留清晰边界。

Nginx worker 使用发行版账号 `www-data`。它不加入 `nexus-readers`，只在 `/opt/nexus`、`releases`、当前 release 和 `console` 获得遍历 ACL，在当前 `dist` 的目录获得 `r-x`、文件获得 `r--`。发布新 release 时必须给新的 `console/dist` 重建同样 ACL；切换 `current` 本身不会把旧 release 的 ACL复制给新目录。

2026-10-22 的验收结果为：Nexus PID 38023 未改变；Java 与 Nginx 均为 `active/disabled`，分别只监听回环 8080 和 18080；首页、`/applications` SPA 回退和实际 asset 响应与 release 文件逐字节一致；无效 API Key 通过 Nginx 到 Java 后保持预期 401 JSON。UFW 没有 80/443/18080 Allow，阿里云安全组也没有新增 Web 规则。该结果只证明同机回环链路，不等于公网、域名或 HTTPS 已就绪。

同日继续完成零费用 TLS 回环实验，模板为 `tools/deployment/linux/nginx-tls-loopback.conf`，验收脚本为 `accept-nginx-tls-loopback.sh`。实验使用保留测试名 `nexus.test` 和 root-only 本地 CA：CA 证书 365 天，服务器证书 30 天且 SAN 只有 `DNS:nexus.test`；服务器私钥和 CA 私钥均为 `root:root 0600`。`127.0.0.1:18080` 只返回保留 URI 的 308，实际 Vue 与 Java 代理由 `127.0.0.1:18443` 的 TLS 1.2/1.3 提供。客户端不信任 CA 或使用错误主机名时都必须失败，只有显式信任实验 CA 并使用正确主机名才成功。该 CA 不导入系统或浏览器全局信任，不替代公开 CA。

ECS 位于中国内地成都，开发者当前没有自有域名。域名字符串不能由 Nginx 单方面创建，只有主域名所有者才能配置其子域和 DNS。中国内地服务器无论按域名还是公网 IP 对外提供 HTTP/HTTPS 网站都涉及 ICP 前置要求；因此当前不开放 80/443，也不使用免费二级域名、公网隧道或约六日有效的 Let’s Encrypt IP 证书规避备案。Certbot 也不在没有实际签发目标时提前安装；正式方案要等可控域名、实名认证/备案条件和公开入口范围都明确后再实施。

公开证书续签不属于 TLS 握手请求路径。Nginx 在每次握手中只出示磁盘上的现有证书；通常由 Certbot 的 systemd timer 定期执行 `renew`，成功后通过 deploy hook reload Nginx。届时必须验证 timer、`renew --dry-run`、失败日志和 reload 后证书序列/有效期变化，不能仅因安装了 Certbot 就宣称自动续签成立。

## 最小生产入站边界

生产服务器遵循入站白名单：只有确实需要跨机器访问的入口才同时在阿里云安全组和 Windows 防火墙放行。同机组件之间的连接不因使用了端口就对公网开放。

| TCP 端口 | 用途 | 阿里云安全组 | Windows 防火墙 | 服务监听与约束 |
| --- | --- | --- | --- | --- |
| 443 | Nginx HTTPS 正式入口 | 允许公网访问 | 允许入站 | Nginx 对外监听，承载 Vue、`/api/*`、`/v1/*` 和 `/s/*` |
| 80 | HTTP 入口 | 允许公网访问 | 允许入站 | Nginx 对外监听，只跳转到 HTTPS，不承载明文业务会话 |
| 3389 | Windows 远程桌面管理 | 仅在使用 RDP 时允许开发者当前公网 IP | 同样限制来源；不用 RDP 时不开放 | 管理入口，不是业务入口；禁止向 `0.0.0.0/0` 开放 |
| 8080 | Java 上游 | 不开放 | 不创建外部 Allow 规则 | Spring Boot 只监听 `127.0.0.1:8080`，仅供同机 Nginx 使用 |
| 8081 | 现有 Tomcat 上游 | 不开放 | 不创建外部 Allow 规则 | 迁移验收后限制为回环监听；保留现有图书管理系统 |
| 8082 | 现有 IIS 站点上游 | 最终不开放 | 最终不创建外部 Allow 规则 | 迁移期间可仅向开发者当前公网 IP 临时放行；最终由 Nginx 经回环访问 |
| 3306 | MySQL | 不开放 | 不创建外部 Allow 规则 | 单机部署只接受本机 Java 连接，数据库也应限制为本机监听 |
| 5173 | Vite 开发服务器 | 不开放 | 不开放 | 生产不运行 Vite；构建后的 `dist` 由 Nginx 提供 |
| 18080、18081、18090 | 隔离实验 | 不开放 | 不保留实验规则 | 实验结束后不得存在监听器 |
| 22、5985、5986 | SSH、WinRM | 当前不开放 | 当前不开放 | 将来有明确远程管理需求时另行设计 |

因此，当前最小公网业务入站集合为 TCP `80`、`443`；管理入口与业务入口分开处理。若使用 Windows RDP，再增加仅面向开发者当前公网 IP 的 TCP `3389`；若只使用阿里云控制台提供的远程连接能力，则不增加公网 3389。

Vue 静态文件和 Chromium 都不需要新增入站端口。Chromium、DNS、Windows 更新以及网页提取/截图访问目标站点属于出站通信，不得误加为公网入站规则。当前阶段保持系统默认出站策略，截图服务的更细出站网络隔离仍是独立后续边界。

实际可达范围由三层共同决定：

```text
程序监听地址 ∩ Windows 防火墙 ∩ 阿里云安全组 = 实际可访问范围
```

正式云端验收必须从服务器外部验证：80 只跳转 HTTPS、443 正常、8080 与 3306 无法连接；同时在服务器上核对 Java 只监听回环、Nginx 是唯一业务对外监听进程。没有创建 Allow 规则不等于永远安全，还需检查是否存在历史遗留的宽泛规则。

## 云端现有 IIS/Tomcat 共存边界

2026-10-21 的 ECS 只读盘点纠正了“空服务器”的早期假设：IIS `DefaultAppPool` 正在 `*:80` 提供现有站点，IIS 站点“我的个人网页”绑定 `*:8082`，现有 Tomcat Java 进程监听 `127.0.0.1:8005` 和 `*:8081`，图书管理系统原入口为公网 `:8082/login`。这些现有应用必须保留，不能为部署 Nexus 直接停止或覆盖。

生产决定仍由 Nginx 统一接收公网 `80/443`，但按域名把请求分流给 Nexus、现有 IIS 站点和 Tomcat。最终只有 Nginx 对外监听 Web 端口；IIS、Tomcat、Nexus Java 与 MySQL 均作为同机回环上游。现有系统优先保持原调用链，只有在验证 IIS 与 Tomcat 的关系后才考虑减少代理层级。

迁移必须可回滚且按以下顺序进行：

1. 保持 IIS、Tomcat 和现有 80/8082 入口不变，先完成只读盘点与本机健康检查；若需恢复个人访问，8082 只向开发者当前公网 IP 临时放行。
2. 在隔离端口运行 Nginx，按候选域名验证现有 IIS/Tomcat 和 Nexus，不能先抢占 80。
3. 为 IIS 增加并验证回环绑定；确认旧页面、登录、静态资源和后端请求均正常后，才安排切换窗口。
4. 切换时让 IIS 释放 80，由 Nginx 接管 80/443；失败则停止 Nginx 并恢复原 IIS 绑定。
5. 外部验收通过后移除临时 8082 入站规则，并把 IIS 8082、Tomcat 8081、Nexus Java 8080 和 MySQL 3306 限制在回环边界。

当前 ECS 的 Windows 防火墙三个 Profile 均为关闭状态；在创建并验证 80、443 和受限来源 3389 规则前不得直接启用，以免中断现有网站或锁死 RDP。阿里云安全组没有 8082 规则时，旧图书系统即使在 IIS 内为 `Started`，公网 `:8082` 也不可达。

同日的真实故障检查进一步确认旧系统当前是双公网端口结构：浏览器从 IIS 8082 加载前端，生产构建再直接请求 Tomcat 8081 的 `/tsglxt/api`、图片和文件。移除安全组 8081 后，页面仍能打开，但“查询用户类型”失败；服务器回环请求返回 200，外部端口测试由失败变为临时放行后的成功，业务随即恢复，证明不是 Tomcat 或 MySQL 崩溃。8081、8082 当前都只能作为迁移期的受限来源临时规则。

旧前端源码位于本机 `D:\WebTsglxt\demo\frontend`。其 `.env.production` 当前把 API、图片和文件基址固化为公网 HTTP 8081，已构建 `dist` 的多个 JS 也包含该地址。最终迁移必须重新构建为同域相对地址：API 使用 `/tsglxt/api`，图片和文件使用空基址；Nginx 再把 `/tsglxt/`、`/forum_images/`、`/chat_files/` 等旧系统路径转发到回环 Tomcat。这样才能在 HTTPS 下避免混合内容，并让浏览器不再直连 8081。具体资源路径仍需由候选环境的页面、SSE、图片和文件回归确认。

Vite 会把 `public` 目录原样复制到构建输出。旧项目的 `public/dist` 同时保存了业务图片/GIF和一套过期的 `index.html`、JS、CSS，因此仅检查主入口不足以证明候选干净；迁移包必须在独立暂存目录排除这三份旧构建文件，并对整个 ZIP 解包后扫描旧公网 IP、`localhost:8081` 等旧后端地址。不得为了打包而删除源目录中的旧文件，后续应另行梳理其归属。

旧图书系统存在 SSE 长连接，Nginx 的正常停止或重载不能只依赖客户端及时断开。配置使用有限的 `worker_shutdown_timeout 120s`：普通请求有时间完成，超过边界的 SSE/长请求会被关闭并由客户端重连。该上限不限制服务正常运行时的请求时长，只约束工作进程已进入优雅退出后的最长等待。

## 版本化发布目录边界

生产部署根目录采用以下职责划分：

```text
D:\NexusDeploy\
├─ config\
│  ├─ application-prod.yml
│  └─ history\<release>\application-prod.yml
├─ releases\
│  └─ V<版本>__<描述>\
│     ├─ server\nexus-server.jar
│     ├─ console\dist\
│     └─ release-info.json
├─ current\
├─ state\
│  ├─ last-successful.txt
│  └─ rollback-release.txt
├─ browsers\playwright-<版本>\
├─ logs\java\、logs\nginx\、logs\deploy\
└─ scripts\
```

`current` 是指向实际 release 的 Windows 目录联接，不承载独立配置。Java 使用稳定路径 `D:\NexusDeploy\current\server\nexus-server.jar`，Nginx 使用 `current\console\dist`；活动配置始终从 `D:\NexusDeploy\config` 读取，日志始终写入共享 `logs`。启动命令使用绝对路径，因此 PowerShell 工作目录固定为部署根目录只是操作约定，不是正确性的前提。

release 采用写入一次的规则：名称不可重复，JAR、`dist` 与 `release-info.json` 完成后设为只读，并通过 SHA-256 清单检查。修复必须创建新版本，不能原地覆盖旧版本。只读属性用于防止误操作，不构成对 Windows 管理员的安全隔离；未来的服务账号应只有 release 读取权限和日志目录写入权限。

部署状态不写进 Spring 配置：`current` 的联接目标是实际活动版本，`last-successful.txt` 是最近通过健康检查的版本，`rollback-release.txt` 是其前一个成功版本。候选健康检查失败时回到 `last-successful`；成功后才推进两个成功版本状态。每次候选部署在 `config/history/<release>` 保留当时的非秘密配置快照，数据库密码和 JWT 密钥等环境变量不复制进历史文件。

Chromium 按 Playwright 版本安装到独立目录，例如 `browsers\playwright-1.63.0`，Java 进程的 `PLAYWRIGHT_BROWSERS_PATH` 指向该绝对路径。升级 Playwright 时创建新的浏览器目录而不是覆盖旧目录，使旧 release 仍具备回滚所需的浏览器版本。

2026-10-18 的隔离实验使用假 JAR、假 `dist` 和固定健康标记，连续独立运行通过，并从不同 PowerShell 工作目录通过：V1、V2 成功后，V3 健康检查失败并自动回到 V2；V1 保留为回滚版本，重复版本发布被拒绝，release 哈希、只读属性、外部配置快照以及共享日志/浏览器边界均通过断言。可重复脚本见 `tools/experiments/deploy-release-local/`。该结果尚未覆盖真实 JAR、真实前端、HTTP 健康检查、进程托管或数据库迁移兼容性。

同日，第一个真实 `V1.0.0__initial-deployment` 候选在本机通过：44 个 JAR/`dist` 产物清单与当前构建逐项匹配，release 只读，Playwright 1.63.0 四个关键可执行文件与源缓存哈希一致。`current` 中的真实 JAR 从部署根目录启动，显式使用 `local`、专用 `nexus_test`、随机 JWT、绝对日志和固定浏览器路径，只监听 `127.0.0.1:18081`；真实 HTTP 请求返回预期机器认证 JSON 401 后才写入 `last-successful.txt`。进程随后停止，隔离端口无残留，配置快照位于 release 外且敏感日志扫描为 0。此结果不包含 `prod`、Nginx 静态前端或真实 Chromium 启动。

随后在 `127.0.0.1:18080 → 127.0.0.1:18081` 完成 release 的隔离 Nginx 验收。Nginx 从 `current\console\dist` 返回的首页、SPA 路由回退和真实 JS asset 与磁盘文件哈希完全一致，访问日志显示三者没有 Java 上游；`/v1` 则代理到 `current` 中的 Java 并返回预期认证 401。非回环 IPv4 仍不能直连 Java，日常 80/8080 不变，日志无敏感模式命中，结束后两个隔离端口关闭。该结果仍未启动 Chromium，也不代表正式 80/443、证书或公网边界已经验收。

2026-10-19 关闭产生 Fake-IP DNS 的虚拟网卡后，固定 Chromium 路径验收通过：release Java 在 18081 上通过真实机器认证请求生成 78689 字节的合法 PNG，进程观察器同时捕获到 `D:\NexusDeploy\browsers\playwright-1.63.0\chromium_headless_shell-1243\chrome-headless-shell-win64\chrome-headless-shell.exe`。退出后 Java、隔离端口和浏览器进程均清理，V1 指针不变，日志敏感模式命中 0。由此确认固定浏览器目录可被 release 实际使用；这仍不代表生产账号权限、进程托管或浏览器出站网络隔离已完成。

同日使用真实 `V1.0.1__failed-startup-drill` 验证失败回滚。候选在切换为 `current` 后，由本次实验参数强制导入不存在的外部配置，真实 Java 因此在监听 18081 前退出；验收脚本把 `current` 恢复到 V1，且未推进 `last-successful.txt`、未创建 `rollback-release.txt`、未保存失败候选的配置快照。已知成功 release 哈希不变，所有隔离及日常端口无残留。失败注入不在 release 内容中，因此本实验验证的是部署状态机与清理路径，不证明该候选二进制自身有缺陷。

保留旧 JAR 和 `dist` 只能回滚应用文件，不能撤销 Flyway 已执行的数据库迁移。涉及 schema 的发布必须使用与旧应用兼容的迁移，或在发布前另行制定数据库恢复方案，不能把目录切换等同于数据库回滚。

## Windows Java 服务边界

Java 不以登录用户或管理员身份长期运行。本机隔离实验选择 Windows 虚拟服务账号 `NT SERVICE\NexusJavaLab`：该账号由系统随服务创建和管理，不需要保存密码。正式部署应使用新的正式服务名及其对应虚拟账号，不能复用实验服务。

最小权限边界为：

| 路径 | Java 服务账号权限 | 原因 |
| --- | --- | --- |
| `current`、目标 release | 读取和执行 | 加载 JAR 与静态资源，不允许原地修改发布物 |
| `config` | 读取和执行 | 读取外部配置，不允许 Java 修改配置 |
| 固定 Chromium 目录 | 读取和执行 | 启动浏览器文件，不允许覆盖浏览器版本 |
| `logs\java` | 修改 | 创建、追加和滚动应用日志 |
| Java 临时目录 | 修改 | Playwright 与 JVM 临时文件 |
| `logs\nginx` | 无需授权 | Java 不写 Nginx 日志 |
| `state`、`releases` 根目录、部署脚本 | 默认不写 | 发布切换与状态推进属于部署操作者职责 |

进程托管使用手动启动的 Windows 服务：机器启动时不自动运行，管理员明确执行 `Start-Service` 后才启动；`Stop-Service` 是计划内停止，不应触发失败恢复；进程异常退出时可按有上限的恢复策略重启。当前实验策略为 10 秒后第一次重启、再次失败 30 秒后第二次重启、随后停止尝试，并在一小时后重置失败计数。它解决的是进程存活，不等于发布健康检查、自动回滚或告警。

2026-10-19 的隔离实验使用真实 Nexus JAR、`local` Profile、专用 `nexus_test` 和 `127.0.0.1:18082`。启动、正常停止、手动重启及强制结束监听 JVM后的自动恢复全部通过；服务最终保持已安装、`Stopped`、`Manual`，端口无残留。服务账号只能读取 release/config，并只能修改 Java 日志和临时目录；敏感日志扫描为 0。可重复脚本和完整证据说明见 `tools/experiments/windows-service-local/`。

服务包装器必须直接启动真实的 `JAVA_HOME\bin\java.exe`。Oracle `javapath\java.exe` 可能是额外的转发进程，导致包装器跟踪的 PID 与真正监听端口的 JVM 不同，使停止和故障恢复留下孤儿进程。验收必须同时核对服务子进程、监听端口所有者和 Java 日志 PID，不能只看 Windows 服务状态。

## Windows Nginx 服务边界

Nginx 与 Java 使用不同的 Windows 虚拟服务账号。2026-10-20 的本机实验使用 `NT SERVICE\NexusNginxLab`、Manual 启动和 `127.0.0.1:18084`：Nginx 程序、配置和隔离 `dist` 对服务账号只读，Nginx 日志、PID 和临时目录可写。正常停止、管理员手动重启、精确静态文件返回及日志写入均通过；最终服务保留为已安装但停止，实验监听和进程为 0。

Windows 版 Nginx master 被强制结束时，worker 可能成为孤儿，因此“WinSW 重启主进程”本身不足以保证旧进程消失。当前受限恢复链为 `WinSW → PowerShell 7 监督进程 → Nginx master → worker`。监督进程在每次 Nginx 启动前重新枚举进程，只终止同时满足以下三项的目标：实验 `nginx.exe` 的绝对路径、精确实验 `-p` 前缀、实验虚拟服务账号所有者。任何相关但不完全匹配的进程都会使启动失败；不使用全局的 `taskkill /IM nginx.exe`。终止后最多等待 10 秒并再次枚举，审计只记录时间、动作和 PID。

未采用 WinSW 2.12.0 的 `prestart` 钩子。实测钩子命令能成功执行，但 WinSW 随后以低权限虚拟账号访问服务控制管理器时被拒绝；给 Nginx 账号扩大 SCM 权限会破坏最小权限边界。改由受托管的监督进程先清理、再启动并等待 Nginx，不需要增加系统级授权。

最终异常恢复复验强制结束 master PID 23824，恢复前审计只清理旧 worker PID 12612，随后新 master PID 2708 启动并返回 HTTP 200；服务 stderr 为空。该机制只解决本机进程树恢复，不等于健康检查、发布回滚或失败告警。可重复实验见 `tools/experiments/windows-nginx-service-local/`，失败告警继续作为独立边界。

2026-10-21 已为 ECS 隔离端口阶段准备正式服务包 `tools/deployment/windows-nginx-service/`。正式服务名为 `NexusNginx`，账号为 `NT SERVICE\NexusNginx`，仍只监听 `127.0.0.1:18080` 且保持 `Manual`。ACL 不作用于整个部署根目录，只重建明确的 Nginx 程序、配置、当前候选 `dist`、服务脚本、日志、PID 与临时目录；服务账号对前四类只读执行，对后三类仅有修改权限。验收会保留 80/443 前后监听快照，验证正常停止、手动重启、异常恢复，并用旧系统现有的匿名 SSE 路由直接观察 `worker_shutdown_timeout 120s`。由于本机 PowerShell 7.6.6 空闲监督进程实测约 83 MiB 工作集，而 ECS 曾只剩 154–311 MiB 可用内存，验收启动前设置 256 MiB 瞬时内存闸门；这不替代后续容量观测。当前包尚未在 ECS 执行。

## 失败告警边界

自动恢复与告警职责分离：WinSW 和监督进程负责尽量恢复服务，告警负责让操作者知道服务持续不可用或发生重复抖动。计划内启动、停止和发布只记录，不应触发故障通知。

本地告警状态机使用 `主机/服务/故障类型` 作为稳定 `dedupeKey`。连续三次失败才打开 incident，连续两次健康才关闭；incident 打开时生成一个本地 UUID，`OPEN` 与 `RESOLVED` 共用该 `incidentId`，各条通知另有唯一 `eventId`。持续失败只更新次数，不生成新 UUID 或重复通知；关闭后再次发生才生成新 incident。UUID 由本地 PowerShell 生成，不能依赖可能正处于故障中的 Nexus UUID API。

状态和通知采用独占文件锁、原子状态替换以及带 `eventId` 的本地 outbox。维护静默只能用于尚未打开的 incident，不能隐藏已经发生的故障。告警内容不得包含数据库密码、JWT、完整 API Key 或大段原始日志。

2026-10-20 的隔离实验验证：两次瞬时失败不通知；12 个并发失败观察累计完整但只写入一条 `OPEN`；两次健康只写入一条同 incident 的 `RESOLVED`；五次维护期失败均被抑制；下一次故障使用新 UUID。两次 incident 合计四条通知且四个 `eventId` 唯一，并发子进程 stderr 为 0。脚本和说明见 `tools/experiments/windows-alert-local/`。

本地 outbox 不等于生产邮件。若将来需要外部通知，最小方案仍是由阿里云云监控监测外部 HTTPS、Nginx/Java 进程数和 ECS 系统事件并向已验证联系人发邮件，使服务器不保存 SMTP 密码或机器人密钥。2026-10-20 当前决定不承担外部告警成本：不安装本地告警计划任务，不配置云监控联系人、规则或邮件，也不部署 outbox 发送器。保留 WinSW 有限自动恢复、组件日志和本地实验代码；接受“服务最终停止后只能通过主动访问或人工巡检发现”的代价。

## 测试和隔离实验

后端测试和当前本地验收脚本显式选择 `local`。完整数据库测试还必须同时覆盖 URL、用户名和密码，使最终数据源指向可丢弃的 `nexus_test`；测试 classpath 的安全检查会在 Hikari/Flyway 建连前拒绝其他 schema。

Nginx 限流验收和 DEPLOY-1 回环实验只在自己的进程作用域设置 `SPRING_PROFILES_ACTIVE=local`，注入专用 `nexus_test` 配置与临时 JWT 密钥，结束时恢复调用者原有环境。

Profile 不能替代网络控制。正式部署仍必须同时验证：Java 只监听回环、Windows 防火墙不开放 8080、阿里云安全组不开放 8080，并从外部机器确认只能访问 Nginx 的公开 80/443 入口。
