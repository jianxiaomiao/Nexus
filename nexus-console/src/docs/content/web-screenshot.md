## 概览

`POST /v1/web/screenshot` 获取公开网页的静态截图，保留原站 HTML 排版，不改成阅读模式。成功返回 PNG 图片字节，不是 JSON 或 Base64；失败返回统一错误 JSON。

| 项目 | 值 |
| --- | --- |
| 认证 | `Authorization: ApiKey <完整密钥>` |
| 请求内容类型 | `application/json` |
| 成功响应 | HTTP 200，`image/png` |
| 截图范围 | 当前视口 1280 × 720，不是整页长图 |
| 脚本与历史 | 不执行网页 JavaScript，不保存截图历史 |

调试台中的 Key 和图片仅在当前页面内存使用；重新请求、切换 API 或离开页面会清除旧图片，离开调试台清除 Key。

## 请求参数

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `url` | string | 是 | 完整 HTTPS 地址，最多 2048 字符 |

只接受 `blog.csdn.net` 和 `zhuanlan.zhihu.com` 的精确 HTTPS 主机名。不能包含登录信息、非标准端口或 `#` 片段，不跟随重定向，不绕过原站登录、验证码或访问限制。Key 必须来自当前后端连接的数据库。

## 请求示例

原始 HTTP 请求：

```http
POST /v1/web/screenshot
Authorization: ApiKey <完整密钥>
Content-Type: application/json

{"url":"https://blog.csdn.net/<作者>/article/details/<文章ID>"}
```

curl（替换密钥和文章 ID，成功后将图片保存为文件）：

```bash
curl --fail-with-body -X POST 'http://localhost:8080/v1/web/screenshot' \
  -H 'Authorization: ApiKey <完整密钥>' \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://zhuanlan.zhihu.com/p/<文章ID>"}' \
  --output screenshot.png
```

失败时命令返回非零退出码，输出文件可能是错误 JSON；不要当作 PNG 打开。不使用 `-i`，避免把响应头混进图片文件。

Windows PowerShell：

```powershell
$apiKey = Read-Host '输入完整 API Key'
$pageUrl = Read-Host '输入网页的完整 HTTPS URL'
$body = @{ url = $pageUrl } | ConvertTo-Json -Compress
try {
    $response = Invoke-WebRequest -UseBasicParsing -Uri 'http://localhost:8080/v1/web/screenshot' `
        -Method Post -Headers @{ Authorization = "ApiKey $apiKey" } `
        -ContentType 'application/json; charset=utf-8' `
        -Body ([Text.Encoding]::UTF8.GetBytes($body)) -OutFile '.\screenshot.png' -PassThru -ErrorAction Stop
    "HTTP $($response.StatusCode)，部分截图：$($response.Headers['X-Nexus-Resource-Limit-Reached'])"
    Invoke-Item '.\screenshot.png'
} catch {
    if ($_.ErrorDetails.Message) { $_.ErrorDetails.Message }
    else { '请求失败；不要打开输出文件，请检查后端错误日志。' }
} finally {
    Remove-Variable apiKey -ErrorAction SilentlyContinue
}
```

本地后端默认端口为 `8080`。只在请求成功后打开图片；错误响应也可能写入输出文件，需查看 JSON 错误码和后端日志。

## 成功与部分截图

```http
HTTP/1.1 200 OK
Content-Type: image/png
Cache-Control: no-store
X-Nexus-Blocked-Resources: 58
X-Nexus-Resource-Limit-Reached: true

<PNG 图片字节>
```

`X-Nexus-Blocked-Resources` 是被拒绝、获取失败或停止加载的次要资源数量。

主页面获取成功后，请求次数触达 64 次上限就停止获取后续资源，返回已有内容的**部分截图**，并标记 `X-Nexus-Resource-Limit-Reached: true`。HTTP 200 表示 PNG 已生成，不保证图片、图标、样式全部完整。false 仅表示次数上限未触发，也不保证资源完整。请求尝试包含随后被白名单拒绝的资源，不等于实际下载次数。

调试台可以预览、放大和下载 PNG；“响应信息”显示尺寸、大小及资源状态。失败 JSON 显示在响应区，不当作图片渲染。

## 外观与安全边界

关闭脚本、固定视口、未登录的新浏览器环境与资源白名单可能影响外观；动态导航、懒加载内容或原站限制可能导致缺失。服务端截图只包含网页区域，不包含浏览器地址栏、侧栏或系统任务栏。

每个外部资源仍受精确域名白名单和连接时公网 DNS 校验约束；单资源 2 MiB、累计下载 10 MiB、PNG 5 MiB、处理预算 20 秒。每个后端进程同时接受一个任务。部分截图不是放开安全检查，主页面失败、大小超限和超时仍返回错误。

## 错误说明

URL校验通过并取得截图执行名额后，开始执行前消耗 Application 额度1次。`SCREENSHOT_BUSY` 不扣；通过配额准入后引擎不可用、抓取/渲染失败或超时仍扣。额度耗尽返回429错误JSON，不是PNG。周期与计次说明见 [调用配额](/docs/quota)。

| HTTP | code | 说明 |
| --- | --- | --- |
| 401 | `INVALID_API_KEY_CREDENTIAL` | Key 无效或已删除 |
| 403 | `API_KEY_FORBIDDEN` | Key、应用或账号禁用 |
| 400 | `VALIDATION_ERROR` / `INVALID_SCREENSHOT_REQUEST` | 请求体或 URL 不合要求 |
| 429 | `APPLICATION_QUOTA_EXHAUSTED` | 应用当前720小时周期的额度耗尽 |
| 422 | `SCREENSHOT_LIMIT_EXCEEDED` | 资源或 PNG 大小超限 |
| 502 | `SCREENSHOT_PAGE_FETCH_FAILED` | 主页面获取失败、跳转或网络/DNS 异常 |
| 503 | `SCREENSHOT_BUSY` / `SCREENSHOT_ENGINE_UNAVAILABLE` | 服务繁忙或 Chromium 不可用 |
| 504 | `SCREENSHOT_TIMEOUT` | 截图超时 |

若 VPN/Fake-IP 把域名解析到 `198.18.0.0/15` 等特殊地址段，后端会拒绝连接。应修正 DNS/代理配置并重启后端，不应移除公网 IP 检查。错误响应示例：

```json
{"code":"SCREENSHOT_PAGE_FETCH_FAILED","message":"目标页面无法获取或不是允许的资源类型","data":null}
```
