## 先看 HTTP 状态，再看业务码

Nexus 的开放 API 响应使用统一外层结构：`code` 是稳定的业务分类，`message` 是给人阅读的说明，`data` 是结果或错误字段信息。不要只根据 `message` 文字判断错误；排查时优先看 HTTP 状态与 `code`。

```json
{
  "code": "INVALID_API_KEY_CREDENTIAL",
  "message": "API Key 无效",
  "data": null
}
```

这篇文档主要覆盖 `/v1/*` 开放接口，末尾也列出公开短地址 `/s/*` 的状态。控制台管理接口 `/api/*` 使用 Bearer JWT，有另外的登录错误码。

## 认证错误：401 与 403

| HTTP | `code` | 表示什么 | 你可以检查 |
| --- | --- | --- | --- |
| 401 | `INVALID_API_KEY_CREDENTIAL` | 服务器无法接受这个机器凭证 | 是否传了完整 Key；请求头是否为 `Authorization: ApiKey <完整密钥>`；Key 或其父级是否已删除 |
| 403 | `API_KEY_FORBIDDEN` | 凭证已验证，但当前不允许调用 | Key、所属 Application 或账号是否被禁用 |

401 对“Key 不存在”“Secret 错误”等情况使用统一响应，不会揭示哪一步失败。401 还包含响应头：

```http
WWW-Authenticate: ApiKey realm="nexus-openapi"
```

如果刚创建的 Key 就遇到 401，请确认使用的是**完整 Key**，而不是列表里的 `publicId` 或遮蔽预览。不要为了排错把完整 Key 发到聊天、工单、截图或日志里。

## Hash 请求体错误：400

只有认证通过后，请求体才会进入接口校验。因此“错误的 Key + 错误的 JSON”会先看到 401，而不是 400。

| `code` | 典型情况 | `data` | 修复建议 |
| --- | --- | --- | --- |
| `VALIDATION_ERROR` | 缺少 `algorithm` 或 `value`，或字段为 `null` | 出错字段及提示 | `algorithm` 传合法值；`value` 必须存在，可以是 `""` |
| `INVALID_REQUEST_BODY` | JSON 无法解析、字段类型不对、`algorithm` 不是 `SHA256` / `SHA512` | `null` | 检查 JSON、字段类型、算法大小写与枚举值 |
| `HASH_INPUT_TOO_LARGE` | `value` 的 UTF-8 编码超过 4096 字节 | `null` | 按字节数缩短文本，恰好 4096 字节允许 |

例如遗漏 `algorithm`，HTTP 400 的响应类似：

```json
{
  "code": "VALIDATION_ERROR",
  "message": "请求参数校验失败",
  "data": {
    "algorithm": "算法为空"
  }
}
```

`value` 缺失时对应提示为 `"value": "输入内容为空"`。若算法写成 `MD5`，服务器只返回通用的 `INVALID_REQUEST_BODY`，不会回显原始输入。

## 短链接错误

创建时若目标 URL 不符合 HTTPS 与精确主机名白名单、到期时刻已过去，或更新请求没有可修改字段，返回 400 `INVALID_SHORT_LINK_REQUEST`。字段缺失可能返回 400 `VALIDATION_ERROR`；短码多次冲突无法生成时返回 503 `SHORT_CODE_UNAVAILABLE`。

机器端查询、修改或删除其他 Key 的短链，按不存在处理，返回 404 `SHORT_LINK_NOT_FOUND`。公开 `/s/{shortCode}` 遇到禁用或删除的短链及其上层资源也返回 404；仍可用但已到期时返回 410 `SHORT_LINK_EXPIRED`。具体归属、白名单和状态规则见 **短链接** 文档。

## 网页正文提取错误

`POST /v1/web/extract` 的 400 `INVALID_WEB_EXTRACT_REQUEST` 表示文章 URL 不符合要求；422 `WEB_CONTENT_UNAVAILABLE` 表示页面没有可识别正文；502 `WEB_PAGE_FETCH_FAILED` 表示目标页面无法获取，包括网络错误、上游拒绝、重定向、非 HTML 或响应过大。具体限制与示例见 **网页正文提取** 文档。

测试库和日常库中的 Key 不能混用。若后端正常运行却返回 401，除了核对完整 Key，还要确认该 Key 是在当前后端连接的数据库中创建的。

## 没有 HTTP 响应怎么办

“无法连接”“连接被拒绝”或请求超时不是上表中的业务错误，因为请求可能根本没到 Nexus。按顺序检查：

1. 后端是否启动；本地示例是否真的监听 `8080` 端口。
2. 请求地址和路径是否正确：`/v1/utils/uuid`、`/v1/utils/hash`、`/v1/short-links`、`/v1/web/extract` 或公开 `/s/{shortCode}`。
3. 如果从手机、容器或另一台电脑请求，`localhost` 指的是**那台设备自己**；请使用它能访问的服务端地址。
4. 若从浏览器前端直接请求，不要把完整 API Key 嵌入前端。让受信任的服务端发起机器调用。

如果已经收到 HTTP 401/403/400，就按对应章节处理，不必先怀疑网络。排查过程中只记录状态码、业务码和不敏感的请求信息。
