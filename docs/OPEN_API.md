# Nexus Open API：第一个机器调用

## 获取与保管 API Key

用户先使用管理端 JWT 创建 Application，再调用管理接口 `POST /api/apiKey/create`，请求体包含 `applicationId` 和 `name`。创建响应中的 `apiKey` 是完整凭证，仅在这一次返回；请在安全的位置保存。普通列表只显示公开 ID 和遮蔽预览，不能找回完整凭证。不要把密钥写进代码仓库、日志或公开文档。

管理端使用 `Authorization: Bearer <JWT>`；机器 Open API 使用 `Authorization: ApiKey <完整密钥>`。两种凭证不可互换。部署后只通过 HTTPS 发送完整密钥。

当前一枚有效 API Key 可以调用全部 `/v1/*` 机器接口，包括工具和短链接口，不按 Scope 分权。这样客户端只需管理一种机器凭证；代价是无法把某枚 Key 限制为“只用工具”或“只管短链”。不同 Key 仍分别拥有自己的短链，持有一枚 Key 不代表可以操作另一枚 Key 的资源；每枚 Key 也可以单独禁用或删除。

### Application 30 天配额

每个 Application 默认每期 1000 次，旗下所有 Key 共用；新建或轮换 Key 不重置额度。
从 Application 创建时刻起每连续 720 小时为一期，不是自然月；到周期结束时刻进入下一期。
UUID、Hash、短链创建、网页正文提取和截图各消耗 1 次；短链列表/更新/删除、JWT 管理接口及公开短链跳转不扣。
认证或输入校验失败不扣。实际业务执行前同步扣减并提交；此后的抓取、渲染或短码生成失败也计次，截图部分 PNG 仍只计 1 次。
截图槽位繁忙时尚未进入执行，不扣额度。

耗尽返回 HTTP 429，例如：

```json
{"code":"APPLICATION_QUOTA_EXHAUSTED","message":"应用当前720小时周期的调用额度已耗尽","data":null}
```

拒绝时不执行实际业务，不新增消费次数；数据库故障与额度耗尽区分处理。
Usage 记录与配额计数职责不同，Usage 仍保留原有成功及失败事件。实现理由见 [配额设计](QUOTA_DESIGN.md)。

管理端配额查询为 `GET /api/application/{applicationId}/quota`，使用 Bearer JWT，检查应用归属；不消耗或创建配额记录。禁用应用仍可查，其他用户或已删除应用返回404。成功的 `data` 包含 `periodStart`、`periodEnd`、`quotaLimit`、`usedCount`、`restCount`；两个无偏移时间字符串按北京时间解释，周期结束时刻属于下一期。Application详情概览已接入，支持单独刷新。

Application 分页列表 `GET /api/application?current=1&size=10` 的 `data` 仍为 `{total,current,size,records}`，每个记录保留 `id/name/status/createdAt/updatedAt`，并新增嵌套 `quota`，字段与上述详情查询相同。后端只批量查询本页所属用户应用的各自当前周期，再按原排序组装；无记录显示零用量，不写库。已有周期取额度快照，没有周期才取应用配置。查询故障不会伪装为零用量。创建和更新响应保持原字段；列表页面直接展示嵌套quota，不逐卡请求。

开发者教程已接入控制台 `/docs/quota`，源文档见 [调用配额](../nexus-console/src/docs/content/quota.md)，包含各接口计次范围、失败阶段、查询示例和429排查；快速开始、认证、五篇接口参考及错误码文档均有对应说明。

### Application 请求速率

所有 `/v1/*` 在 API Key 认证成功后按 Application 限速。同一 Application 的所有 Key 和接口共享每秒 10 次额度，按 100ms 平滑放行；空闲不会积攒突发令牌。每个 Application 最多等待 10 个请求，最长等待 1 秒。认证失败不进入该队列；限流拒绝不扣 Application Quota。

| HTTP 状态 | `code` | 含义 |
| --- | --- | --- |
| 429 | `APPLICATION_RATE_QUEUE_FULL` | 此 Application 已有 10 个请求等待；响应带 `Retry-After: 1`。 |
| 429 | `APPLICATION_RATE_WAIT_TIMEOUT` | 请求等待许可超过 1 秒；响应带 `Retry-After: 1`。 |
| 503 | `RATE_LIMIT_GLOBAL_QUEUE_FULL` | 当前所有 Application 的总等待量已达到服务保护上限。 |
| 503 | `RATE_LIMIT_STATE_CAPACITY_REACHED` | 限流状态容量已满，当前没有可安全回收的空闲状态。 |
| 503 | `RATE_LIMITER_UNAVAILABLE` | 限流调度器正在关闭或无法继续调度。 |
| 503 | `RATE_LIMIT_WAIT_CANCELLED` | HTTP 异步等待已取消。 |

客户端收到 429 或 503 后应遵守 `Retry-After`（若存在），并使用有上限的退避重试；不要立即并发重放。已经获得速率许可后，Quota 耗尽或业务执行失败不会退还本次速率时隙。

### 管理端轮换 API Key 凭据

拥有该 Key 的用户可用 Bearer JWT 调用 `POST /api/apiKey/{apiKeyId}/rotate`，请求体为 `{"expectedPublicId":"<当前列表中的 publicId>"}`。不提交旧完整 Key。成功响应的 `data` 包含原 `id`、`applicationId`、`name`、新 `publicId`、`keyPreview`、完整 `apiKey`、原 `status` 和 `updatedAt`；完整 Key 只返回这一次，数据库只保存 Secret 摘要。Key ID 和状态不变，已有短链接与 Usage 历史仍归这枚 Key。

轮换提交后，旧完整 Key 的后续机器请求返回 401；新凭据在 Key、Application 和账号均启用时可用。如果 Key 原先禁用，轮换不会自动启用，新凭据仍返回 403。缺失或无效的管理 JWT 返回 401；Key 不存在、已删除或不属于当前用户返回 404 `API_KEY_NOT_FOUND`；`expectedPublicId` 已变化时返回 409 `API_KEY_ROTATION_CONFLICT`，不会再次轮换。缺失或空的 `expectedPublicId` 返回 400 `VALIDATION_ERROR`。

若轮换已提交但成功响应丢失，不能找回新完整 Key。刷新管理列表确认公开 ID 已改变后，用新的 `expectedPublicId` 主动发起下一次轮换；不要自动重试旧请求。并发轮换只有一个基于同一旧公开 ID 的请求能成功。

## 生成 UUID

```http
GET /v1/utils/uuid
Authorization: ApiKey <完整密钥>
```

例如：

```bash
curl 'https://<你的服务地址>/v1/utils/uuid' \
  -H 'Authorization: ApiKey <创建时保存的完整密钥>'
```

成功时返回 HTTP 200。每次请求生成一个新的 UUID，示例：

```json
{
  "code": "SUCCESS",
  "message": "UUID 已生成",
  "data": { "uuid": "8ac09c20-5818-4978-a7e2-3e37602eaadb" }
}
```

响应不包含 Key、Secret 摘要、内部 Key ID 或 Application ID。这个接口目前只演示机器身份链，没有额外业务参数。

## 计算文本摘要

`POST /v1/utils/hash` 对请求中的 `value` 按 UTF-8 编码后计算单向摘要。它是文本工具接口，不用于保存密码，也不会改变 API Key Secret 的认证方式；不要把密码或完整 API Key 当作输入提交。

```http
POST /v1/utils/hash
Authorization: ApiKey <完整密钥>
Content-Type: application/json

{"algorithm":"SHA256","value":"hello"}
```

例如：

```bash
curl 'https://<你的服务地址>/v1/utils/hash' \
  -H 'Authorization: ApiKey <创建时保存的完整密钥>' \
  -H 'Content-Type: application/json' \
  -d '{"algorithm":"SHA256","value":"hello"}'
```

`algorithm` 必填，只接受 `SHA256` 或 `SHA512`；`value` 必填，但允许空字符串。`value` 编码后的 UTF-8 字节数最多为 **4096**，恰好 4096 字节可用。结果 `hashContent` 是小写十六进制字符串：SHA-256 为 64 位，SHA-512 为 128 位。成功时返回 HTTP 200：

```json
{
  "code": "SUCCESS",
  "message": "hash摘要成功",
  "data": {
    "hashContent": "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
  }
}
```

Hash 请求体错误均返回 HTTP 400，使用同样的 `code`、`message`、`data` 外层格式：

| `code` | 情况 |
| --- | --- |
| `VALIDATION_ERROR` | 缺少 `algorithm` 或 `value`；`data` 包含对应字段的错误信息。 |
| `INVALID_REQUEST_BODY` | JSON 格式错误、算法值不在 `SHA256` / `SHA512` 内，或字段无法转换。 |
| `HASH_INPUT_TOO_LARGE` | `value` 的 UTF-8 编码超过 4096 字节。 |

认证失败仍按下方的 401/403 规则处理，且先于请求体校验。

## 网页正文提取（第一版）

`POST /v1/web/extract` 使用机器 API Key。请求体为 `{"url":"https://blog.csdn.net/<作者>/article/details/<文章ID>"}`；当前仅接受 `https://blog.csdn.net` 和 `https://zhuanlan.zhihu.com` 的精确主机名，不接受自定义端口、URL 用户信息或片段。白名单与短链接分开，且不自动跟随目标站重定向。

成功响应 `data` 包含三个字符串：`title`（标题）、`textContent`（纯文本）、`contentHtml`（保留段落与图片位置的净化 HTML）。`contentHtml` 中的图片只保留绝对 HTTPS URL；服务端不下载图片，也不保证原站允许客户端展示。调用方仍应将外站内容视为不可信输入。单次抓取只接收 HTML，响应体上限为 2 MiB；当前要求提取正文至少 80 个字符，正文识别仍是启发式行为，不能保证所有页面都能识别。

| HTTP 状态 | `code` | 情况 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` / `INVALID_WEB_EXTRACT_REQUEST` | URL 缺失、超长、格式不合法或不在白名单内。 |
| 422 | `WEB_CONTENT_UNAVAILABLE` | 页面可读取，但没有可提取的正文。 |
| 502 | `WEB_PAGE_FETCH_FAILED` | 上游拒绝、跳转、非 HTML、超大或网络失败。 |

认证失败仍按下方的 401/403 规则处理；Filter 拒绝时不会向目标网站发起请求。建议用固定 HTML 样本做自动化测试，真实知乎/CSDN 页面只作为手工验收样本。抓取遵守目标站公开访问条件，不绕过登录或限制。

## 静态网页截图（第一版）

`POST /v1/web/screenshot` 使用相同机器认证，接收 `{"url":"https://blog.csdn.net/<作者>/article/details/<文章ID>"}`。入口只接受知乎专栏和 CSDN 的精确 HTTPS 主机名，关闭脚本、固定 1280×720 视口，不跟随重定向、不保存历史。

成功响应为 HTTP 200 的 `image/png` 二进制，**不包装成 JSON**；`Cache-Control: no-store`，`X-Nexus-Blocked-Resources` 报告被拒绝、获取失败或停止加载的次要资源数量。主 HTML 成功后资源请求次数触达上限，停止获取后续资源并返回部分 PNG，`X-Nexus-Resource-Limit-Reached: true`；false 不保证资源全部完整。错误继续使用 ApiResponse JSON：400 URL/请求错误、502 主页面获取失败、504 超时、422 大小超限、503 繁忙或浏览器不可用；认证错误仍为原有 401/403。详情、Chromium 安装与 PowerShell 示例见 [静态网页截图](SCREENSHOT_API.md)。

## 异步网页截图任务

`POST /v1/web/screenshot-jobs` 创建持久化任务并返回 HTTP 201；`GET /v1/web/screenshot-jobs/{jobId}` 查询 `QUEUED/RUNNING/SUCCEEDED/FAILED/EXPIRED` 状态；`GET /v1/web/screenshot-jobs/{jobId}/result` 在成功且未过期时返回原始 PNG。任务按 API Key 隔离，查询和下载不重复扣配额；结果保留 1 小时、元数据保留 24 小时，全局最多 10 个未完成任务。完整字段、状态恢复和错误码见 [静态网页截图](SCREENSHOT_API.md#持久化异步接口)。

## 认证与错误

请求到达 `/v1/*` 时，Filter 从 `Authorization` 提取完整密钥。认证器先用公开 ID 找记录，再验证 Secret 摘要，然后沿 Application 找到所有者 User，检查三层状态。成功后，仅把 `apiKeyId` 和 `applicationId` 放入本次请求，供 Controller 使用。

| HTTP 状态 | `code` | 含义 |
| --- | --- | --- |
| 401 | `INVALID_API_KEY_CREDENTIAL` | 缺失、格式错误、未知、Secret 错误，或 Key / Application / User 已删除或不存在。响应带 `WWW-Authenticate: ApiKey realm="nexus-openapi"`。 |
| 403 | `API_KEY_FORBIDDEN` | Secret 已验证，但 Key、Application 或所有者账号被禁用。 |

示例错误体：

```json
{
  "code": "INVALID_API_KEY_CREDENTIAL",
  "message": "API Key 无效",
  "data": null
}
```

禁用父 Application 不会修改子 Key 自身的禁用状态；重新启用父 Application 后，原本仍启用且未删除的 Key 可以继续使用。
账号禁用也不会修改子表；恢复账号后，原本仍启用且未删除的 Application 和 Key 可以继续使用。详见 [账号状态规则](ACCOUNT_STATUS_POLICY.md)。

## 短链接

机器端使用 `Authorization: ApiKey <完整密钥>`，仅能管理这把 Key 创建的短链。创建请求不接收 `apiKeyId`；服务端从已认证的 Key 身份确定归属。

| 方法与路径 | 用途 |
| --- | --- |
| `POST /v1/short-links` | 创建短链 |
| `GET /v1/short-links` | 列出当前 Key 未删除的短链 |
| `PUT /v1/short-links` | 按请求体中的 `id` 修改名称或状态 |
| `DELETE /v1/short-links/{id}` | 软删除当前 Key 的短链 |

创建请求示例：

```json
{"name":"演示链接","originalUrl":"https://www.douyin.com/video/1","expiresAt":"2030-01-01T16:00:00+08:00"}
```

`expiresAt` 必须是带时区的未来绝对时刻；当前时间到达该时刻即失效。`originalUrl` 创建后不能修改，最多 2048 个字符。第一版只接受 HTTPS、无 URL 用户名、无非标准端口，且主机名精确匹配 `www.douyin.com`、`v.douyin.com`、`www.xiaohongshu.com`、`weibo.com` 或 `m.weibo.cn`；不自动允许子域名。该检查不保证目标内容安全。

创建、列表和更新的 `data` 返回短链 `id`、`apiKeyId`、`name`、`originalUrl`、`shortCode`、`status`、`expiresAt`、`createdAt`、`updatedAt`。短码由服务端生成，删除后不复用。更新请求体为 `{"id":123,"name":"新名称","status":1}`；`name` 和 `status` 至少提供一个，状态 `0` 为启用、`1` 为禁用。

管理端使用 `Authorization: Bearer <JWT>`：`GET /api/short-links?apiKeyId=123`、`PUT /api/short-links` 和 `DELETE /api/short-links/{id}`。管理端只可操作自己 Application 下的 Key 所属短链；不能通过提交其他用户的 ID 获得访问权。

访问者无需凭证即可请求 `GET /s/{shortCode}`。有效短链返回 HTTP 302、`Location` 为原始 URL，并设置 `Cache-Control: no-store`。短链或上层 Key、Application、所有者已禁用或删除时返回 404；仅在仍可用但已到期时返回 410。公开跳转不经过 `/v1/*` 的 API Key Filter。
