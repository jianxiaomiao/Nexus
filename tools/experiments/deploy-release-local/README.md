# Windows 本地版本化发布与回滚实验

## 目的

用隔离的假 JAR、假 `dist` 和健康状态文件验证 Nexus 的生产目录模型。本实验不构建或启动真实 Nexus，不连接 MySQL，不监听端口，不修改日常配置，也不写入真实 `D:\NexusDeploy`。

它只回答以下问题：

- JAR 与 `dist` 如何组成一个版本发布单元；
- “发布后不可修改”如何变成可检查的规则；
- `current`、当前成功版本和回滚版本分别表示什么；
- 配置、日志和 Chromium 为什么独立于 release；
- 候选版本启动失败时如何恢复到最后成功版本。

## 预测

1. `V1.0.0__initial-deployment` 健康检查成功，成为第一个成功版本。
2. `V1.1.0__console-update` 健康检查成功，成为当前版本；V1 成为回滚版本。
3. 同名 V2 再次发布被拒绝，不覆盖原文件。
4. `V1.1.1__broken-candidate` 被切为临时当前版本，但模拟健康检查失败后自动回到 V2。
5. 最终 `current` 指向 V2，`last-successful.txt` 为 V2，`rollback-release.txt` 为 V1。
6. release 中的文件为只读且哈希不变；配置、日志和浏览器目录始终在 release 外。
7. 每次候选部署保留当时的非秘密配置快照，但不把活动配置复制进 release。

## 运行

从仓库根目录执行，无需管理员权限：

```powershell
Set-Location D:\Nexus
& .\tools\experiments\deploy-release-local\run.ps1
```

每次运行都会在忽略的 `runtime/` 下创建一个新的独立 `NexusDeploy-*`，不会删除此前结果。最后一行出现 `PASS` 才表示完整实验通过。

本实验中的健康检查只是 release 内的固定标记，不代表真实 HTTP 健康检查；只读属性也不是对管理员的安全隔离。下一步才会用真实 JAR、真实 `dist` 和本机 HTTP 请求替换这些探针。

## 2026-10-18 结果

脚本连续独立运行通过，并额外从仓库之外的临时工作目录运行通过，证明路径解析不依赖调用者当前目录。最终状态均为：`current` 与 `last-successful.txt` 指向 V2，`rollback-release.txt` 保留 V1；V3 的日志顺序为候选激活、健康检查失败、回滚到 V2。重复 V2 被拒绝，release 全部文件保持只读且清单哈希未变，三个候选部署各自留下相同的外部非秘密配置快照；活动配置、日志和浏览器目录没有进入 release。

这证明了目录切换模型和失败恢复状态机，不证明真实 Nexus 能启动，也不证明 Flyway 迁移可以随 JAR 回滚。

## 真实候选验收

开发者完成真实 JAR、`dist` 与 Playwright 1.63.0 浏览器目录复制后，使用以下脚本核对并激活第一个本地候选：

```powershell
Set-Location D:\Nexus
& .\tools\experiments\deploy-release-local\accept-real-release.ps1
```

脚本逐项重算 `release-info.json` 中所有 JAR/前端哈希，并与当前构建产物对照；四个 Playwright 可执行文件也与本机安装缓存对照。通过后创建 `current` 目录联接，从 `D:\NexusDeploy` 工作目录启动其中的 JAR，显式加载仓库内 `local` Profile，只注入专用 `nexus_test` 设置、随机 JWT 和固定浏览器路径。真实 HTTP 探针必须在 `127.0.0.1:18081` 到达机器认证边界并返回预期 JSON 401，才保存配置快照并写入 `last-successful.txt`。Java 随后停止，`current` 作为已选择的本地版本保留；失败则恢复先前联接且不推进成功状态。

该验收不会使用 `prod` Profile，也不会证明外部生产秘密、Nginx 静态文件或 Chromium 截图成功。它只在本机以测试库验证复制后的 release 可以真实启动。

要进一步证明 release Java 能从固定目录真实启动 Chromium，增加截图开关：

```powershell
& .\tools\experiments\deploy-release-local\accept-real-release.ps1 `
  -VerifyScreenshotBrowserPath
```

脚本在截图请求期间观察新启动的 `chrome.exe` 或 `chrome-headless-shell.exe`。只有诊断脚本返回 HTTP 200 合法 PNG，并且至少观察到一个可执行文件位于 `D:\NexusDeploy\browsers\playwright-1.63.0`，才算通过。运行前若该固定目录已有浏览器进程会拒绝开始，避免把旧进程误当作本轮证据。

2026-10-19 首次真实截图路径验收未通过，且不能记作 Chromium 失败：release 完整性和专用 `nexus_test` 预检通过，临时 Application 1271/Key 835 创建后，主页面抓取在浏览器启动前返回 HTTP 502 `SCREENSHOT_PAGE_FETCH_FAILED`；服务端诊断为 `DNS_FAILURE`。系统解析 CSDN 和知乎分别得到 `198.18.0.26/27`，即特殊用途 `198.18.0.0/15`，直接指定 `223.5.5.5` 与 `1.1.1.1` 查询也被透明改写为相同结果，符合 VPN/TUN/Fake-IP DNS 行为。Nexus 按既定连接时 DNS 防护拒绝，没有放宽或绕过。

本轮 Application/Key 已软删除，随机测试账号留在 `nexus_test`；Java、18081 和固定目录浏览器进程均已清理，`current`/`last-successful` 仍为 V1，日志敏感模式命中 0。关闭产生 Fake-IP 的本机或网络代理、确认系统解析不再落入私有/特殊用途地址后，使用相同命令完整重跑。

关闭虚拟网卡后，同日重跑通过。系统 DNS 已恢复为公网地址；真实截图请求返回 HTTP 200 `image/png`，PNG 为 78689 字节，`blocked=58`、`limitReached=true`。进程观察器在请求期间捕获到 `D:\NexusDeploy\browsers\playwright-1.63.0\chromium_headless_shell-1243\chrome-headless-shell-win64\chrome-headless-shell.exe`，证明 release 中的 Java 确实使用固定部署目录，而不只是文件存在或环境变量已设置。画面核对标题与正文可读；部分图片缺失符合资源白名单和请求上限设计。

验收后 Application 1272 及其 Key 836 已软删除，随机账号留在可丢弃的 `nexus_test`；18080/18081 与固定目录浏览器进程均无残留，`current`/`last-successful` 仍为 V1。因为没有更早成功版本，`rollback-release.txt` 仍不存在；验收日志敏感模式命中 0。首次失败因此归因于虚拟网卡造成的 Fake-IP DNS，而不是 Chromium 安装或固定路径配置。

### 2026-10-18 真实候选结果

`V1.0.0__initial-deployment` 首次真实验收通过。清单中的 44 个 JAR/前端产物逐项通过长度和 SHA-256 检查，并与刚构建的源 JAR/`dist` 一致；release 全文件保持只读，四个 Playwright 关键可执行文件与本机 1.63.0 缓存哈希一致。

脚本从 `D:\NexusDeploy` 工作目录经 `current` 启动真实 JAR，显式激活 `local`，只注入已核实的 `nexus_test` 专用账号、Hikari 1..2、一次性 JWT、绝对日志目录和 `D:\NexusDeploy\browsers\playwright-1.63.0`。启动日志确认数据库为 `nexus_test`，Java 只监听 `127.0.0.1:18081`；`GET /v1/utils/uuid` 返回预期 JSON 401 `INVALID_API_KEY_CREDENTIAL`，因此候选被提升为首个本地成功版本。

退出后 `current` 和 `last-successful.txt` 均为 V1；由于没有更早的成功版本，`rollback-release.txt` 不存在。生产配置快照位于 release 外、只读且与活动文件哈希一致；18080/18081 均无监听，验收/Java 日志的凭证模式扫描为 0。Chromium 本轮只核对复制与固定路径，没有真正启动截图进程；`prod` Profile、Nginx 静态前端和数据库迁移回滚也仍未验证。

## 真实失败候选回滚实验

运行以下命令会从已验证的 V1 复制出一个新的只读候选，并在验收进程中额外导入一个确定不存在、且没有 `optional:` 前缀的配置文件：

```powershell
Set-Location D:\Nexus
& .\tools\experiments\deploy-release-local\test-real-failed-rollback.ps1
```

该故障只存在于本次启动参数中，不修改候选 JAR、`dist`、日常配置或数据库。脚本要求真实 Java 在监听 18081 前以“配置不存在”退出，并检查验收脚本已把 `current` 恢复到原来的成功版本、成功状态文件没有变化、失败候选没有配置快照、已知成功 release 哈希不变，且端口和浏览器进程无残留。同名候选不会被覆盖；再次演练必须通过 `-CandidateReleaseName` 提供新名称。

2026-10-19 使用 `V1.0.1__failed-startup-drill` 完成演练。候选 44 个清单产物与当前构建一致，45 个文件（含清单）全部只读；切换后真实 JAR 因 `intentionally-missing.yml` 不存在而在监听前退出。最终 `current` 与 `last-successful.txt` 均保持 V1，`rollback-release.txt` 和失败候选配置快照均不存在，V1 指纹未变，80/443/8080/18080/18081 与固定浏览器进程均无残留，日志敏感模式命中 0。

这里的状态含义是：失败候选只是被拒绝，不会写入 `rollback-release.txt`。该文件保存的是“当前成功版本的前一个成功版本”，只有第二个候选真正验收成功后才会出现。
