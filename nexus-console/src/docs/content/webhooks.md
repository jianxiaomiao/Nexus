## 用途

Webhook 用于让 Nexus 在异步截图任务成功或最终失败后，主动通知开发者自己的 HTTPS 服务器。它不是浏览器实时通知机制，也不会替代任务查询接口。

每个 Application 最多配置一个回调地址。配置只影响配置完成后进入终态的截图任务，不会为更早完成的任务补发事件。

## 事件请求

当前只发送 `screenshot.completed`。请求包含：

```http
POST /nexus/webhooks HTTP/1.1
Content-Type: application/json
X-Nexus-Timestamp: 1793842200
X-Nexus-Signature: <64 位小写 Hex HMAC>
```

成功事件示例：

```json
{
  "eventId": "7d73f222-8b76-49dd-9376-0415cf1a1350",
  "eventType": "screenshot.completed",
  "applicationId": 123,
  "jobId": "0a863f9a-69dd-4128-b704-d727f774cbcc",
  "completedAt": "2026-11-05T01:30:00Z",
  "result": {
    "sizeBytes": 78984,
    "sha256": "<截图文件的 SHA-256>"
  },
  "resultExpiresAt": "2026-11-05T02:30:00Z",
  "failureCode": null
}
```

最终失败时 `result` 和 `resultExpiresAt` 为 `null`，`failureCode` 提供稳定失败原因。不要根据 HTTP 到达顺序推断任务顺序。

## 验证签名

签名原文由时间戳、一个 ASCII 点号和收到的原始请求体字节组成：

```text
Message = X-Nexus-Timestamp + "." + RawRequestBody
Signature = HMAC-SHA256(WebhookSecret, Message)
```

签名输出为 64 位小写 Hex。验证时必须使用收到的原始 Body 字节，不能先解析 JSON 再重新序列化。控制台一次性展示的是无 Padding 的 Base64 URL-safe Secret；接收端先将它解码为原始字节，再作为 HMAC 密钥，不能使用 AES Master Key 或其他 Nexus 密钥。

接收端还应检查时间戳是否在自己允许的时间窗口内，并使用常量时间比较签名。

## 响应和重试

当前只有 HTTP 200 表示投递成功。`408`、`425`、`429` 和 `5xx` 会按有限退避重试；`429` 可以通过 `Retry-After` 建议延迟。重试会重新生成时间戳与签名，但保持同一 `eventId` 和业务事件内容。

Webhook 是至少一次投递。接收端已经完成处理，但响应在网络中丢失时，Nexus 可能再次发送同一事件。接收端必须使用持久化 Inbox 或数据库唯一约束按 `eventId` 去重。

## 暂停与轮换

- 暂停 Webhook 只停止发送，不取消待投递事件。
- 恢复后待投递事件继续处理，但不保证立即发送。
- 修改 URL 后，尚未开始的投递使用新 URL。
- Secret 创建和轮换后只展示一次，不能从详情页找回。
- 轮换后的投递使用最新 CURRENT Secret；接收端应在切换期间按自己的发布流程安全更新。

完整 Secret、请求体和签名原文都不应写入日志或前端代码。
