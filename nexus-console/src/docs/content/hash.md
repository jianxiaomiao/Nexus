## 概览

`POST /v1/utils/hash` 把文本按 UTF-8 编码，再计算 SHA-256 或 SHA-512 摘要。相同输入与算法会得到相同输出；结果是**单向摘要，不是加密文本**。

> 这个接口是文本工具，**不用于保存用户密码**。密码应由服务端使用专门的 `PasswordEncoder` 处理；也不要把密码或完整 API Key 提交给这个接口。

| 项目 | 值 |
| --- | --- |
| 方法与路径 | `POST /v1/utils/hash` |
| 认证 | `Authorization: ApiKey <完整密钥>` |
| 内容类型 | `application/json` |
| 成功状态 | HTTP 200 |

## 请求参数

| JSON 字段 | 类型 | 是否必填 | 规则 |
| --- | --- | --- | --- |
| `algorithm` | string | 是 | 只接受 `SHA256` 或 `SHA512`，按示例大写书写 |
| `value` | string | 是 | 允许 `""` 空字符串；UTF-8 编码后最多 4096 字节 |

长度限制按**编码后的字节数**计算，而不是 JavaScript 字符串长度或肉眼看到的字符数。例如 `中` 通常占 3 个 UTF-8 字节。恰好 4096 字节有效，4097 字节会被拒绝。

## 请求示例

原始 HTTP 请求：

```http
POST /v1/utils/hash
Authorization: ApiKey <完整密钥>
Content-Type: application/json

{"algorithm":"SHA256","value":"hello"}
```

macOS / Linux 终端：

```bash
curl -i 'http://localhost:8080/v1/utils/hash' \
  -H 'Authorization: ApiKey <完整密钥>' \
  -H 'Content-Type: application/json' \
  -d '{"algorithm":"SHA256","value":"hello"}'
```

Windows PowerShell：

```powershell
$apiKey = Read-Host '输入完整 API Key'
$body = @{ algorithm = 'SHA256'; value = 'hello' } | ConvertTo-Json -Compress
Invoke-RestMethod -Uri 'http://localhost:8080/v1/utils/hash' `
  -Method Post -Headers @{ Authorization = "ApiKey $apiKey" } `
  -ContentType 'application/json; charset=utf-8' -Body $body
```

本地后端默认使用 `8080` 端口；远程部署时改用实际 HTTPS 地址。示例中的 `<完整密钥>` 不是可用凭证。

## 成功响应

对 `hello` 使用 `SHA256` 时，HTTP 200 返回：

```json
{
  "code": "SUCCESS",
  "message": "hash摘要成功",
  "data": {
    "hashContent": "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
  }
}
```

`data.hashContent` 为小写十六进制字符串：`SHA256` 的长度是 64，`SHA512` 的长度是 128。`value: ""` 也合法，但省略 `value` 或传 `null` 不合法。

## 错误说明

认证检查先于请求体校验：即使 JSON 有错误，若 Key 无效，也会先得到 HTTP 401 或 403。认证通过后，请求体错误按下表返回 HTTP 400：

| `code` | 示例原因 | 处理方式 |
| --- | --- | --- |
| `VALIDATION_ERROR` | 缺少 `algorithm` / `value`，或值为 `null` | 补齐字段；`data` 包含出错字段及说明 |
| `INVALID_REQUEST_BODY` | JSON 格式错误、字段类型错误、算法写成 `MD5` 等未知值 | 修正 JSON；算法只用 `SHA256` 或 `SHA512` |
| `HASH_INPUT_TOO_LARGE` | `value` 的 UTF-8 长度超过 4096 字节 | 缩短输入，按字节重新计算长度 |

错误响应的具体结构和认证问题排查见左侧 **错误码与排查**。
