# 静态网页截图：第一版

已确认的需求：关闭网页 JavaScript、同步返回 PNG、不保存截图历史。截图模块位于 `nexus-server/src/main/java/com/nexus/screenshot/`。

## HTTP 契约

`POST /v1/web/screenshot` 使用 `Authorization: ApiKey <完整密钥>`，请求类型为 `application/json`：

```json
{"url":"https://blog.csdn.net/<作者>/article/details/<文章ID>"}
```

只接受 `blog.csdn.net` 和 `zhuanlan.zhihu.com` 的精确 HTTPS 主机名，URL 最多 2048 字符；不能含登录信息、非标准端口或片段。不跟随 HTTP 重定向，不支持登录、验证码绕过或客户端自定义脚本。

成功为 HTTP 200，响应体是 **PNG 字节，不是 ApiResponse JSON 或 Base64**：

```http
Content-Type: image/png
Cache-Control: no-store
Content-Disposition: inline; filename="screenshot.png"
X-Nexus-Blocked-Resources: 0
X-Nexus-Resource-Limit-Reached: false
```

固定截图当前视口 1280×720，不是整页长图。`X-Nexus-Blocked-Resources` 是被拒绝、获取失败或停止加载的次要资源数量；大于零意味着图片、样式等可能缺失。`X-Nexus-Resource-Limit-Reached: true` 表示触达请求次数上限，后续资源已停止加载，本次为部分截图。false 只表示没有触发次数上限，不保证所有资源完整。主页面失败不会返回成功的空白截图；超时或大小超限仍会失败。

所有错误仍使用统一 JSON 外层 `code`、`message`、`data`：

| HTTP | code | 含义 |
| --- | --- | --- |
| 401 / 403 | 原有机器认证错误码 | 无效凭据 / 当前禁用 |
| 400 | `VALIDATION_ERROR` / `INVALID_REQUEST_BODY` / `INVALID_SCREENSHOT_REQUEST` | 请求体或 URL 不合要求 |
| 502 | `SCREENSHOT_PAGE_FETCH_FAILED` | 主页面拒绝、跳转、网络错误或资源类型不符 |
| 504 | `SCREENSHOT_TIMEOUT` | 资源获取或渲染预算超时 |
| 422 | `SCREENSHOT_LIMIT_EXCEEDED` | 单资源、累计下载或 PNG 大小超限 |
| 503 | `SCREENSHOT_BUSY` | 当前已有截图任务，立即拒绝，不排无限队列 |
| 503 | `SCREENSHOT_ENGINE_UNAVAILABLE` | Chromium 缺失、启动或运行异常 |

机器身份通过 Controller 检查后，成功和失败仍由 `@UsageApi` 记录为 `web.screenshot`；Filter 拒绝的 401/403 不进入 Usage。

## 调用链与安全边界

```text
机器 Filter → Controller require(identity) → ScreenshotService
    → 页面 URL 校验 → 单任务并发许可
    → ScreenshotRenderer 创建当前线程独立的 Chromium/Context
        → 禁用脚本、Service Worker 和下载，设置离线模式
        → Chromium 使用不转发请求的本机拒绝代理，关闭其 DNS 解析/预取
        → 每个普通资源请求都进入 context.route
            → URL、资源类型、请求预算检查
            → ScreenshotResourceFetcher 的 Java HTTP 客户端获取资源
                → 公网 DNS 地址检查 → 使用同一次解析结果建立 TLS 连接
            → route.fulfill 交给浏览器渲染
    → 返回 PNG → 关闭浏览器/代理 → 释放并发许可
```

共享的 `common/net/PublicWebDnsResolver` 与 `PublicIpAddresses` 被正文提取和截图共同使用。共享的是连接安全判断；模块的域名白名单和内容规则分开。现有正文 Fetcher 返回 HTML 字符串，截图 Fetcher 返回原始字节、内容类型及必要 CORS 头，支持 HTML、CSS、指定图片/字体类型。

资源额外允许以下精确主机：`csdnimg.cn`、`g.csdnimg.cn`、`i-blog.csdnimg.cn`、`img-blog.csdnimg.cn`、`static.zhihu.com`、`pic1.zhimg.com`、`pic2.zhimg.com`、`pic3.zhimg.com`、`pic4.zhimg.com`、`pica.zhimg.com`、`picx.zhimg.com`。这些资源主机不自动获得入口页面权限；其实际 DNS 地址仍须通过检查。

不调用 `route.resume()` 或 `route.fetch()` 让其他网络客户端重新解析连接；不转发 API Key、Bearer JWT、Cookie 或上游 Set-Cookie。上游异常链可能含完整 URL，因此业务异常只使用固定消息；用户查询参数不进入日志。

每个任务 64 次请求、单资源 2 MiB、累计下载 10 MiB、PNG 5 MiB，处理预算为 20 秒。资源读取过程中持续计数；到截止时取消实际 HTTP 请求，避免慢速响应仅靠空闲超时拖延。启动或运行环境异常仍按浏览器不可用处理。第一版每进程同时只接受一个任务。

64 次指 **浏览器资源请求尝试**，包含主页面和随后被白名单拒绝的资源，不是 64 次实际下载。主 HTML 成功后，第 65 次开始停止获取后续次要资源，等待已获取内容完成静态渲染并返回 HTTP 200 PNG。不会续增下载额度或放宽 DNS 检查；主页面失败、超时、单资源/累计/PNG 字节超限仍保留原有失败行为。

异常日志新增安全诊断 `diagnostic`，只包含枚举、当前值 `observed` 和上限 `limit`，不记录目标 URL、查询参数或上游异常消息。请求数、单资源、累计字节、PNG 分别为 `REQUEST_COUNT`、`RESOURCE_BYTES`、`TOTAL_BYTES`、`PNG_BYTES`；抓取失败可区分 `DNS_FAILURE`、`NETWORK_IO`、`UPSTREAM_STATUS` 和 `RESOURCE_TYPE`。非数值型诊断的 observed/limit 为 -1。HTTP 错误码与 JSON 结构不变，4xx 仍不打印异常栈。

上述约束针对当前静态截图。公开部署仍应以低权限、隔离的浏览器运行环境和网络层出站限制形成额外边界，不能把请求路由当成操作系统防火墙。当前并未实现动态脚本、任务队列、持久化或分布式限流。

## 本地准备与手工验收

Java Playwright 版本为 1.63.0。使用运行后端的同一 Windows 用户预先安装 Chromium：

```powershell
Set-Location D:\Nexus\nexus-server
mvn exec:java '-Dexec.mainClass=com.microsoft.playwright.CLI' '-Dexec.args=install chromium'
```

机器请求不会自动下载浏览器。安装后重启后端，再使用当前库中创建的完整 Key。关闭脚本意味着客户端渲染内容、懒加载图片或反爬页面可能无法完整显示；PNG 反映公开 HTML 的静态呈现，并不保证文章站点总能获取。

PowerShell 保存本次结果到一个全新的本地文件（服务端不保存历史）：

```powershell
$screenshotApiKey = Read-Host '输入当前后端数据库中的完整 API Key'
$screenshotUrl = Read-Host '输入公开页面的完整 HTTPS URL'
$screenshotBody = @{ url = $screenshotUrl } | ConvertTo-Json -Compress
$screenshotPath = Join-Path $env:TEMP ('nexus-screenshot-' + [Guid]::NewGuid().ToString('N') + '.png')
try {
    $response = Invoke-WebRequest -UseBasicParsing -Uri 'http://localhost:8080/v1/web/screenshot' `
        -Method Post -Headers @{ Authorization = "ApiKey $screenshotApiKey" } `
        -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($screenshotBody)) `
        -OutFile $screenshotPath -PassThru -ErrorAction Stop
    "HTTP $($response.StatusCode)，资源阻止数：$($response.Headers['X-Nexus-Blocked-Resources'])，次数上限已触达：$($response.Headers['X-Nexus-Resource-Limit-Reached'])"
    "PNG 已保存：$screenshotPath"
    Invoke-Item $screenshotPath
} catch {
    $failure = $_
    if ($failure.ErrorDetails.Message) { $failure.ErrorDetails.Message }
    elseif ($failure.Exception.Response -is [System.Net.HttpWebResponse]) {
        $reader = [IO.StreamReader]::new($failure.Exception.Response.GetResponseStream())
        try { $reader.ReadToEnd() } finally { $reader.Dispose() }
    } elseif ($failure.Exception.Response -is [System.Net.Http.HttpResponseMessage]) {
        $failure.Exception.Response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    } else { '未收到可读取的错误响应；请检查网络和后端日志。' }
} finally {
    Remove-Variable screenshotApiKey -ErrorAction SilentlyContinue
}
```

## 验证范围

测试包含 URL 白名单、共享 DNS 返回值、真实请求取消、字节/MIME/重定向、并发许可释放、PNG 与 JSON 契约，以及真实 Chromium 固定样本渲染。浏览器样本以 Java Fetcher 的固定响应提供资源；样本哨兵验证不允许的本地请求没有到达，PNG 像素验证 CSS/图片生效而脚本没有执行。

另有真实 Spring/HTTP/MySQL 集成测试，用新生成的 Key 验证 Filter 401/403、有效 Key 的 PNG、无效目标 JSON 400 和 200/400 的 `web.screenshot` Usage。集成测试替换上游资源获取为固定样本，保留真实浏览器、业务服务与数据库；它不能证明知乎/CSDN 当前允许真实抓取。运行数据库测试必须按 `LOCAL_TESTING.md` 使用专用 `nexus_test` 和对应测试账号。

2026-10-11：干净构建的后端全套 229/229 通过，零失败/错误/跳过。真实公开站点与截图前端尚未验收。

### 2026-10-11 真实 CSDN 失败诊断

本节描述部分截图规则修改前的失败定位；当前行为和验收结果见下节。

本地配置按开发者要求切到专用 `nexus_test` 及对应账号（旧日常设置保留为注释，密码未提交）。使用独立 18080 后端，启动日志确认 Flyway schema 为 `nexus_test`，PowerShell 每轮通过真实管理 API 创建随机账号、Application 和全新 Key：

- Fake-IP 环境：目标解析为 `198.18.0.27`，HTTP 502，日志 `reason=DNS_FAILURE`；这是 DNS 获取失败/安全拒绝路径，不是认证问题。
- 公网 DNS 恢复为 `117.149.203.25` 后重启该后端，测试同一篇文章 `https://blog.csdn.net/qq_46429858/article/details/115696591`：HTTP 422，`Diagnostic[reason=REQUEST_COUNT, observed=65, limit=64]`。确定是资源请求次数超限，未改配额或 SSRF 策略。

本轮定向测试 26/26，另加真实 HTTP/MySQL/Chromium 集成测试 3/3；不是重新执行全量。两轮 Application 685/686 和对应 Key 415/416 已软删除，随机账号及 Usage 留在可丢弃测试库。临时测试后端结束后停止；尚未得到该真实文章的成功 PNG。

可重复诊断脚本：`nexus-server/scripts/diagnose-screenshot.ps1`。在测试后端启动时将 stdout 重定向到独立日志文件，启动完成后运行：

```powershell
Set-Location D:\Nexus\nexus-server
.\scripts\diagnose-screenshot.ps1 -StartupLogPath '<本次后端启动日志的完整路径>'
```

默认测试 `http://127.0.0.1:18080`，可传入 `-BaseUri` 和 `-ArticleUrl`。脚本在写入前校验启动日志中的测试 schema、端口与当前监听 PID，拒绝复用无关后端；使用 HttpClient 正常读取非 2xx JSON，避免 PowerShell 的 `-OutFile`/`ErrorDetails` 差异掩盖失败正文。不会打印或保存密码、JWT、完整 Key；退出时软删除本次 Application/Key。

### 2026-10-11 部分截图与资源数量验收

前端现已接入调试台第五个“网页截图”入口、第八篇教程 `/docs/web-screenshot`。PNG Blob 用临时 URL 展示，支持 Element Plus 预览/放大/下载和部分资源提示；错误 JSON 单独显示。Key 不进入存储或示例，临时图片地址在替换/切换/离开时释放。构建与E2E类型检查、完整模拟响应浏览器回归75/75通过；本轮没有真实浏览器到后端联调。教程与视觉稿仅说明界面，真实后端证据仍是下文 PowerShell。

开发者选择停止后续加载并返回部分截图。主 HTML 成功、次数上限触发时不再整次失败；HTTP 200 PNG 增加 `X-Nexus-Resource-Limit-Reached`，并保留阻止数、no-store 和 Usage 200。新增固定资源类型统计，只记录数量，不记录 URL 或页面内容。

同一真实 CSDN 文章的受限安全 HTML 获取：272460 字节，109 个 img 标签，其中正文 `#content_views` 有 35 个，其余 74 个在正文外；15 个外部样式表，35 个外部脚本。img 的 src 分布于 `csdnimg.cn`（45）、`i-blog.csdnimg.cn`（37）及其他 CSDN 图片主机。脚本关闭，标签不等于脚本实际加载；缓存、懒加载与重复资源也使 HTML 标签数不等于浏览器请求数。

使用 `nexus_test`、独立 18080 后端和全新 Key 的真实 PowerShell 结果：HTTP 200，PNG 78984 字节，1280×720，`blocked=58`、`limitReached=true`。浏览器统计 `observed=102, fetched=44, blocked=58, truncated=38, byType={document=1, image=86, stylesheet=15}`。前 64 次中成功获取 44、拒绝/获取失败 20，随后 38 次停止获取。视觉核对标题和正文可读，头像及部分图标缺失；这不是完整页面的承诺。首次真实文章成功 PNG 已确认。

截图相关、共享 DNS、日志/Filter 的定向回归共 42/42（含真实 HTTP/MySQL/Chromium 的 4 个集成用例），不是全量回归。测试验证超额允许资源不再 fetch、超额拒绝资源返回 PNG、资源大小超限仍失败、响应头及部分截图 Usage 200。Application 693 与 Key 423 已软删除，随机账号及 Usage 留在测试库；临时后端已停止。配额、白名单和 DNS 安全判断均未放宽。
