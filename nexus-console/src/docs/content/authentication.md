## 人与程序使用不同凭证

| 请求范围 | 凭证 | 代表谁 | 典型操作 |
| --- | --- | --- | --- |
| 管理接口 `/api/*` | `Authorization: Bearer <JWT>` | 登录的 Nexus 用户 | 创建、查看、禁用 Application 或 API Key |
| 开放接口 `/v1/*` | `Authorization: ApiKey <完整密钥>` | 某个 Application 下的机器 Key | 调用 UUID 或 Hash |

管理端 JWT 不能用于 `/v1/*`，API Key 也不能用于 `/api/*`。这不是两种写法的区别，而是**人类管理身份**与**程序调用身份**的边界。

## 认识完整 Key、公开 ID 与预览

在应用详情的 **API Keys** 标签创建 Key。成功时会得到完整凭证；它只显示一次。普通列表展示 `publicId` 和遮蔽后的 `keyPreview`，便于识别记录，但**二者都不能用于认证**。

请求头必须使用创建时保存的完整 Key，`ApiKey` 后面是一个空格：

```http
GET /v1/utils/uuid
Authorization: ApiKey <完整密钥>
```

不要把引号当成 Key 的一部分，不要只复制预览，也不要把 `Bearer` 用在此处。终端示例中包住整个请求头的引号只是命令行语法。响应不会回显完整 Key、Secret 摘要或内部 Key / Application ID。

## 安全存放与轮换

完整 Key 只能交给受信任的服务端程序。不要放入浏览器、小程序或 App 安装包：客户端代码和网络请求可能被使用者提取。也不要提交到 Git、写入日志或公开截图。线上环境使用 HTTPS，并通过安全配置向服务端程序提供 Key。

如果怀疑泄露，先为同一 Application 创建新 Key，让调用方切换，再禁用或删除旧 Key。丢失完整 Key 也应按此方式替换；列表无法找回原值。

## 何时返回 401 或 403

| HTTP 状态 | `code` | 含义 |
| --- | --- | --- |
| 401 | `INVALID_API_KEY_CREDENTIAL` | 请求头缺失/格式错误、完整 Key 未知或 Secret 错误；Key、Application 或所有者账号已删除或不存在 |
| 403 | `API_KEY_FORBIDDEN` | Secret 已验证，但 Key、Application 或所有者账号被禁用 |

无效凭证统一返回 401，不会告诉调用方“Key 是否存在”或“是哪一段出错”。401 响应还带有 `WWW-Authenticate: ApiKey realm="nexus-openapi"`。

禁用 Application 或账号不会改写子 Key 的状态；重新启用后，原本仍启用且未删除的 Key 可以继续使用。**删除**与**禁用**不是同一状态，删除后的 Key 不会因为重新启用父级而恢复。需要排查时，从 Key、Application、账号三个层级检查。
