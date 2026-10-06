## 归属与访问方式

短链归**创建它的 API Key**。同一 Application 下另一把 Key 不能查看或修改它。机器端使用 `Authorization: ApiKey <完整密钥>`；创建请求**不填写** `apiKeyId`，服务端从已认证的 Key 确定归属。

登录用户可以在控制台按 Application → API Key 查看和管理自己名下的短链。管理请求使用 Bearer JWT。访问公开短地址的访客不需要登录，也不需要 API Key。

| 方法与路径 | 身份 | 用途 |
| --- | --- | --- |
| `POST /v1/short-links` | API Key | 创建短链 |
| `GET /v1/short-links` | API Key | 列出当前 Key 未删除的短链 |
| `PUT /v1/short-links` | API Key | 按 `id` 修改名称或状态 |
| `DELETE /v1/short-links/{id}` | API Key | 删除当前 Key 的短链 |
| `GET /api/short-links?apiKeyId={id}` | JWT | 查看自己名下指定 Key 的短链 |
| `PUT /api/short-links` | JWT | 改名或启停自己名下的短链 |
| `DELETE /api/short-links/{id}` | JWT | 删除自己名下的短链 |
| `GET /s/{shortCode}` | 无 | 公开跳转 |

## 创建请求

| 字段 | 类型 | 规则 |
| --- | --- | --- |
| `name` | string | 必填，去掉首尾空白后为 1～64 字符 |
| `originalUrl` | string | 必填，最多 2048 字符；创建后不可修改 |
| `expiresAt` | datetime | 必填，带时区的未来绝对时刻；到达该时刻即过期 |

只接受 **HTTPS**，不接受 URL 用户名、密码或非标准端口。目标主机名必须精确等于以下之一：`www.douyin.com`、`v.douyin.com`、`www.xiaohongshu.com`、`weibo.com`、`m.weibo.cn`。不会自动放行其他子域名。系统在提交时只验证协议和主机名，**不保证目标页面内容合法或安全**。

10 秒等短有效期从创建请求提交时计算才较合理；网络耗时和复制、打开浏览器的时间仍可能使短链在首次访问前到期。需要分享给其他人时请选择更长时间。

```http
POST /v1/short-links
Authorization: ApiKey <完整密钥>
Content-Type: application/json

{"name":"演示链接","originalUrl":"https://www.douyin.com/video/1","expiresAt":"2030-01-01T16:00:00+08:00"}
```

创建成功返回 HTTP 200，`data` 包含 `id`、`apiKeyId`、`name`、`originalUrl`、`shortCode`、`status`、`expiresAt`、`createdAt`、`updatedAt`。短码由服务端生成，调用方不能指定；删除后也不会复用。下面的值仅为示例：

```json
{
  "code": "SUCCESS",
  "message": "短链接创建成功",
  "data": {
    "id": 123,
    "apiKeyId": 11,
    "name": "演示链接",
    "originalUrl": "https://www.douyin.com/video/1",
    "shortCode": "Ab7Kq2",
    "status": 0,
    "expiresAt": "2030-01-01T08:00:00Z",
    "createdAt": "2026-10-06T08:00:00Z",
    "updatedAt": "2026-10-06T08:00:00Z"
  }
}
```

客户端将**自己的公开服务地址**与 `/s/`、`shortCode` 组合为短地址。示例域名 `nexus.example` 不是真实服务地址。

## 查询、修改与删除

`GET /v1/short-links` 只返回当前 Key 未删除的短链。修改时至少提供 `name` 或 `status` 之一；`status: 0` 为启用，`status: 1` 为禁用。原始 URL 与到期时间不能修改。

```http
PUT /v1/short-links
Authorization: ApiKey <完整密钥>
Content-Type: application/json

{"id":123,"name":"新名称","status":1}
```

删除使用 `DELETE /v1/short-links/123`。登录用户也能通过相应的 `/api/short-links` 接口管理自己名下的记录。管理端按 `apiKeyId` 查询仍会验证 Application 所有者；客户端提供 ID 不代表已经获得访问权。

## 公开跳转与有效状态

```http
GET /s/Ab7Kq2
```

有效短链返回 **HTTP 302**，`Location` 指向创建时保存的目标，并带 `Cache-Control: no-store`。访客无需凭证。公开入口不经过 `/v1/*` 的 API Key Filter。

跳转会检查短链、所属 Key、Application 和所有者账号的当前状态。禁用或删除时返回 **404 `SHORT_LINK_NOT_FOUND`**；仍可用但已到期时返回 **410 `SHORT_LINK_EXPIRED`**。到期时刻本身已经过期。禁用父级不会改写短链自身状态，恢复父级后，原本仍启用且未到期的短链可以继续跳转；删除父级会同步软删除对应短链。

## 常见错误

| HTTP | `code` | 含义 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` / `INVALID_REQUEST_BODY` | 缺少字段、字段类型或 JSON 格式错误 |
| 400 | `INVALID_SHORT_LINK_REQUEST` | 非白名单目标、到期时刻无效、更新没有提供可修改字段等 |
| 401 | `INVALID_API_KEY_CREDENTIAL` | 机器请求的完整 Key 无效或已删除 |
| 403 | `API_KEY_FORBIDDEN` | Key、Application 或账号被禁用，机器请求不可继续 |
| 404 | `SHORT_LINK_NOT_FOUND` | 记录不存在、已删除、归属不符，或公开跳转当前不可用 |
| 410 | `SHORT_LINK_EXPIRED` | 公开跳转的短链已过期 |
| 503 | `SHORT_CODE_UNAVAILABLE` | 生成短码多次冲突，请稍后重试 |
