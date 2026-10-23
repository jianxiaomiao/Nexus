# DEPLOY-1：Spring Boot 回环监听边界

这是部署阶段的第一个隔离实验。它使用真实 Nexus Spring Boot JAR、真实 `nexus_test` 和独立 Nginx，验证 Java 只绑定 IPv4 回环地址后，仍能被同机 Nginx 代理，但不能通过本机非回环 IPv4 地址直连。

实验不修改日常 `application.yml`、本地忽略配置或正式 Nginx 配置，不启动、停止或占用日常 80/8080。实验端口默认为 `18080 → 18081`，运行产物保存在忽略的 `runtime/`。

## 预测

| 观察 | 预期 |
| --- | --- |
| Java 监听地址 | 仅 `127.0.0.1:18081`，不存在同端口的通配或非回环监听 |
| 直接请求 `127.0.0.1:18081/v1/utils/uuid` | 到达机器认证 Filter，因未提供 API Key 返回 JSON 401 |
| 请求任一本机非回环 IPv4 的 18081 | TCP 连接失败 |
| 请求 `127.0.0.1:18080/v1/utils/uuid` | 经隔离 Nginx 到同一 Java，返回相同 JSON 401 |
| 日常 80/8080 监听快照 | 实验前后不变 |

401 是本实验的成功观察：它证明请求到达真实 `MachineApiKeyFilter`，同时不创建测试用户、Application、Key、Usage 或 Quota 数据。

## 运行

从仓库根目录的 PowerShell 执行：

```powershell
& .\tools\experiments\deploy-1-loopback\run.ps1
```

脚本显式激活 `local` Profile，从忽略的本地配置中选择已保留的 `nexus_test` 专用 URL、账号和密码，只对自己启动的 Java 进程注入；JWT 使用当次随机 32 字节密钥。它会打包 JAR、生成隔离 Nginx 配置、检查监听与只读 HTTP 结果，最后只关闭本轮进程，并恢复调用者原有的进程环境变量。不会输出数据库密码、JWT、机器非回环地址或凭证。

需要 Java 21、Maven Wrapper、MySQL 中已准备好的 `nexus_test` 专用账号，以及 `D:/nginx/nginx-1.31.6/nginx.exe`。其他 Nginx/JDK 路径可通过参数指定。

已经准备版本化 release 后，可以跳过重新打包，直接验收 `current` 中的真实 JAR 和 `dist`：

```powershell
& .\tools\experiments\deploy-1-loopback\run.ps1 `
  -ReleaseJar 'D:\NexusDeploy\current\server\nexus-server.jar' `
  -FrontendDist 'D:\NexusDeploy\current\console\dist' `
  -BackendWorkingDirectory 'D:\NexusDeploy' `
  -PlaywrightBrowsersPath 'D:\NexusDeploy\browsers\playwright-1.63.0'
```

此模式让 Nginx 直接提供 release 的 Vue 静态文件，只有 `/api`、`/v1` 和 `/s` 转发给 Java；`try_files` 把前端路由回退到 `index.html`。脚本比较 HTTP 返回字节与 release 文件 SHA-256，不只判断状态码。

## 边界

同一台机器访问自己的非回环地址，可以证明 Spring Socket 没有绑定那些本地地址；它不能替代未来从另一台机器对云服务器执行的公网直连失败验收。回环监听也不阻止服务器本机的其他进程连接 Java。

## 2026-10-17 实际结果

真实 Spring Boot 使用进程参数 `--server.address=127.0.0.1 --server.port=18081` 启动，启动日志确认 Flyway 指向 `nexus_test`，监听表确认该端口只有一个 `127.0.0.1` Listener 且属于本轮 Java 进程。

- 直接回环请求返回 JSON 401，错误码为 `INVALID_API_KEY_CREDENTIAL`；
- 枚举到的 3 个本机非回环 IPv4 地址均无法连接 18081，脚本不输出这些地址；
- 隔离 Nginx 配置检查通过，经 18080 请求返回相同 JSON 401，访问日志确认上游为 `127.0.0.1:18081`、上下游状态均为 401；
- 日常 80/8080 监听快照前后一致；
- 脚本结束后 18080/18081 均已关闭，没有创建业务数据。

首次启动在打包阶段因受限执行环境不能下载缺失的 Maven 父 POM而停止，尚未启动进程或访问数据库；允许依赖下载后继续。第一次有效运行完成了全部网络观察，但最终快照断言把“前后均为空的监听集合”传给 `Compare-Object`，被 PowerShell 当成 `null` 后失败。改为比较规范化字符串签名并完整重跑，最终通过；没有把前次核心观察代替完整成功。

## 2026-10-18 版本化 release 的 Nginx 结果

隔离链路使用 `D:\NexusDeploy\current` 中的 V1，运行于 `127.0.0.1:18080 → 127.0.0.1:18081`。真实 Java 使用 `local` 与专用 `nexus_test`，直接及代理 `/v1/utils/uuid` 都返回预期 JSON 401；枚举到的 3 个本机非回环 IPv4 地址均不能直连 Java。

Nginx 返回的 `/`、SPA 路由 `/applications` 和一个真实构建 JS 文件分别与 release 中的 `index.html`、同一 `index.html` 和对应 asset 字节/哈希完全相同。访问日志中 API 请求有 `127.0.0.1:18081` 上游及 401，三个静态请求均无上游且为 200，证明静态文件没有绕到 Java。日常 80/8080 快照未变化，敏感日志模式命中 0；完成后 18080/18081 均关闭，`current` 和 `last-successful` 仍为 V1。
