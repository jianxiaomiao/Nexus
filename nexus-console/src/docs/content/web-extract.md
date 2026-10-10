## 概览

`POST /v1/web/extract` 从公开文章页面中识别正文，返回标题、纯文本，以及保留段落和图片位置的净化 HTML。适合把文章文本交给程序处理，或在调试台查看阅读预览。

| 项目 | 值 |
| --- | --- |
| 方法与路径 | `POST /v1/web/extract` |
| 认证 | `Authorization: ApiKey <完整密钥>` |
| 内容类型 | `application/json` |
| 成功状态 | HTTP 200 |

这里使用机器 API Key；登录控制台的 Bearer JWT 不能代替它。调试台输入的完整 Key 只保存在当前页面内存中。

## 请求参数

| JSON 字段 | 类型 | 是否必填 | 规则 |
| --- | --- | --- | --- |
| `url` | string | 是 | 完整 HTTPS 文章地址，最多 2048 个字符 |

当前只接受以下**精确主机名**，与短链接的目标白名单分开：

- `zhuanlan.zhihu.com`：例如 `https://zhuanlan.zhihu.com/p/<文章ID>`。
- `blog.csdn.net`：例如 `https://blog.csdn.net/<作者>/article/details/<文章ID>`。

不能包含 URL 登录信息、非标准端口或 `#` 片段。只使用公开可访问的文章；不会绕过登录、验证码或访问限制，也不自动跟随重定向。Google 搜索结果、其他知乎主机和其他站点当前不在白名单内。

## 请求示例

原始 HTTP 请求（请替换文章 ID）：

```http
POST /v1/web/extract
Authorization: ApiKey <完整密钥>
Content-Type: application/json

{"url":"https://zhuanlan.zhihu.com/p/<文章ID>"}
```

macOS / Linux 终端：

```bash
curl -i 'http://localhost:8080/v1/web/extract' \
  -H 'Authorization: ApiKey <完整密钥>' \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://zhuanlan.zhihu.com/p/<文章ID>"}'
```

Windows PowerShell（输入真实文章地址，可分别测试知乎专栏和 CSDN）：

```powershell
$apiKey = Read-Host '输入完整 API Key'
$articleUrl = Read-Host '输入文章的完整 HTTPS URL'
$body = @{ url = $articleUrl } | ConvertTo-Json -Compress
$response = Invoke-RestMethod -Uri 'http://localhost:8080/v1/web/extract' `
  -Method Post -Headers @{ Authorization = "ApiKey $apiKey" } `
  -ContentType 'application/json; charset=utf-8' -Body $body
$response.data | Format-List title, textContent, contentHtml
```

本地后端默认端口为 `8080`。Key 必须来自**当前后端连接的数据库**；测试库 `nexus_test` 中创建的 Key 无法在日常库 `nexus` 中认证，反之亦然。

## 成功响应

以下是结构示意，内容随文章变化：

```json
{
  "code": "SUCCESS",
  "message": "内容提取成功",
  "data": {
    "title": "文章标题",
    "textContent": "第一段正文……第二段正文……",
    "contentHtml": "<p>第一段正文……</p><img src=\"https://cdn.example.com/article.png\" alt=\"配图\"><p>第二段正文……</p>"
  }
}
```

三个字段都是字符串，不是图片 URL 数组：`textContent` 用于处理纯文本；`contentHtml` 保留正文与图片的相对顺序。服务端移除脚本和事件处理器等不安全内容，但调用方仍应将外站内容视为不可信输入，按自己的展示场景限制标签、属性与 URL。

## 图片与提取限制

图片只保留绝对 HTTPS URL，**服务端不下载图片**。调试台由浏览器直接加载图片；图片防盗链、失效地址或网络限制都可能导致加载失败，浏览器通常不能给出确切原因。加载失败不代表整篇正文提取失败。

当前只获取 HTML，不执行目标页面的 JavaScript。单次 HTML 响应体上限为 2 MiB，提取出的正文至少需要 80 个字符。正文识别是启发式过程，不能保证每篇文章都成功，也不能保证广告完全剔除。

## 错误说明

| HTTP 状态 | `code` | 原因与处理 |
| --- | --- | --- |
| 401 | `INVALID_API_KEY_CREDENTIAL` | Key 无效或已删除；检查完整 Key 和当前数据库 |
| 403 | `API_KEY_FORBIDDEN` | Key、Application 或账号禁用；检查当前状态 |
| 400 | `VALIDATION_ERROR` / `INVALID_WEB_EXTRACT_REQUEST` | URL 缺失、超长、格式错误或不在白名单内 |
| 422 | `WEB_CONTENT_UNAVAILABLE` | 页面可读取，但没有足够的可识别正文；改用公开文章页 |
| 502 | `WEB_PAGE_FETCH_FAILED` | 网络失败、上游拒绝、跳转、非 HTML 或响应过大 |

认证失败时不会向目标网站发起请求。502 时先查看错误信息，再检查目标网站和本地网络；如果代理把域名解析到 `198.18.0.0/15` 等特殊地址段，安全策略会拒绝连接。应修正本地 DNS/代理解析条件，不应放开这些地址段来让验收通过。
