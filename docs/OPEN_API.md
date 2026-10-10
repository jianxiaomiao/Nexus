# Nexus Open API：第一个机器调用

## 获取与保管 API Key

用户先使用管理端 JWT 创建 Application，再调用管理接口 `POST /api/apiKey/create`，请求体包含 `applicationId` 和 `name`。创建响应中的 `apiKey` 是完整凭证，仅在这一次返回；请在安全的位置保存。普通列表只显示公开 ID 和遮蔽预览，不能找回完整凭证。不要把密钥写进代码仓库、日志或公开文档。

管理端使用 `Authorization: Bearer <JWT>`；机器 Open API 使用 `Authorization: ApiKey <完整密钥>`。两种凭证不可互换。部署后只通过 HTTPS 发送完整密钥。

当前一枚有效 API Key 可以调用全部 `/v1/*` 机器接口，包括工具和短链接口，不按 Scope 分权。这样客户端只需管理一种机器凭证；代价是无法把某枚 Key 限制为“只用工具”或“只管短链”。不同 Key 仍分别拥有自己的短链，持有一枚 Key 不代表可以操作另一枚 Key 的资源；每枚 Key 也可以单独禁用或删除。

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
