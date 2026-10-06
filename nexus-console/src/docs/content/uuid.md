## 概览

`GET /v1/utils/uuid` 每次调用生成一个新的随机 UUID。无需查询参数或请求体；该接口目前用于演示完整的机器身份认证与开放 API 调用链。

| 项目 | 值 |
| --- | --- |
| 方法与路径 | `GET /v1/utils/uuid` |
| 认证 | `Authorization: ApiKey <完整密钥>` |
| 请求体 | 无 |
| 成功状态 | HTTP 200 |

## 请求示例

原始 HTTP 请求：

```http
GET /v1/utils/uuid
Authorization: ApiKey <完整密钥>
```

macOS / Linux 终端：

```bash
curl -i 'http://localhost:8080/v1/utils/uuid' \
  -H 'Authorization: ApiKey <完整密钥>'
```

Windows PowerShell：

```powershell
$apiKey = Read-Host '输入完整 API Key'
Invoke-RestMethod -Uri 'http://localhost:8080/v1/utils/uuid' `
  -Headers @{ Authorization = "ApiKey $apiKey" }
```

示例中的地址是本地后端默认端口；远程部署时换成实际 HTTPS 地址。不要把真实 Key 复制到代码仓库。

## 成功响应

HTTP 200 的 JSON 响应：

```json
{
  "code": "SUCCESS",
  "message": "UUID 已生成",
  "data": {
    "uuid": "8ac09c20-5818-4978-a7e2-3e37602eaadb"
  }
}
```

| 字段 | 说明 |
| --- | --- |
| `code` | `SUCCESS` 表示本次请求成功 |
| `message` | 给人阅读的简短说明 |
| `data.uuid` | 本次生成的 UUID 字符串，例如 `8ac09c20-5818-4978-a7e2-3e37602eaadb` |

每次请求都重新生成值，不要把示例 UUID 当成固定结果。响应不包含完整 Key、Secret 摘要或内部身份 ID。

## 错误说明

在执行业务前会先验证 API Key。缺失、格式错误或无效的 Key 返回 HTTP 401、`INVALID_API_KEY_CREDENTIAL`；Key 正确但 Key、应用或账号被禁用时返回 HTTP 403、`API_KEY_FORBIDDEN`。详情见左侧 **错误码与排查**。
