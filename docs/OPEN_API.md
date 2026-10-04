# Nexus Open API：第一个机器调用

## 获取与保管 API Key

用户先使用管理端 JWT 创建 Application，再调用管理接口 `POST /api/apiKey/create`，请求体包含 `applicationId` 和 `name`。创建响应中的 `apiKey` 是完整凭证，仅在这一次返回；请在安全的位置保存。普通列表只显示公开 ID 和遮蔽预览，不能找回完整凭证。不要把密钥写进代码仓库、日志或公开文档。

管理端使用 `Authorization: Bearer <JWT>`；机器 Open API 使用 `Authorization: ApiKey <完整密钥>`。两种凭证不可互换。部署后只通过 HTTPS 发送完整密钥。

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
