# 账号状态对现有凭证的影响

## 问题

用户登录后持有 JWT，Application 下的程序持有 API Key。如果账号随后被禁用或软删除，已有凭证不能只因为签名或 Secret 正确就继续调用。

## 选择

| User 状态 | 已签发且本身有效的 JWT | Secret 正确且 Key、Application 本身有效的 API Key |
| --- | --- | --- |
| 正常 | 可进入管理接口，随后仍需检查资源归属 | 可进入 Open API |
| 禁用 | 403 `AUTH_ACCOUNT_FORBIDDEN` | 403 `API_KEY_FORBIDDEN` |
| 软删除或记录不存在 | 401 `INVALID_ACCESS_TOKEN` | 401 `INVALID_API_KEY_CREDENTIAL` |

无效 JWT、错误 Secret、未知或已删除的 Key 始终先按 401 处理，不向未认证调用方暴露禁用状态。对于 Secret 正确的机器请求，Key、Application、User 任一层已删除都按 401；三层都存在且未删除后，任一层禁用按 403。恢复 User 后，原本仍有效且独立启用的 Application 和 Key 可以继续使用。

账号状态只保存在 `users`。`api_keys.application_id → applications.owner_user_id → users.id` 提供机器认证的查找路径；禁用或恢复账号只更新用户行，不批量复制状态到子表。

## 执行边界

- 人工管理请求：`BearerUserIdResolver` 验证 JWT 后按 subject 查询 User。缺失或已删除抛 `InvalidAccessTokenException`；禁用抛现有 `AccountForbiddenException`。
- 机器 Open API：`ApiKeyAuthenticator` 验证 Secret，再沿 Application 找 User。缺失或已删除抛现有 `InvalidApiKeyCredentialException`；禁用抛 `ApiKeyForbiddenException`。Filter 将它们写成 401/403 JSON。
- `ApplicationService` 和 `ApiKeyService` 继续负责资源归属、状态更新及业务规则。当前受保护的 HTTP 管理入口都先经过 Bearer 解析器；若以后新增可直接调用这些 Service 的入口，那个入口也必须执行账号状态检查。

## 取舍与结果

每次管理请求增加一次 User 主键读取；每次机器请求在 Key、Application 读取后增加一次 User 主键读取。禁用事务提交后的新请求会读取到新状态；已经通过认证的进行中请求仍可能完成。这个选择避免了子表冗余状态、批量更新与恢复同步。后续若性能测量证明读取成为瓶颈，再评估 JOIN 或缓存；当前没有这个证据。

单元测试、数据库状态转换测试和真实 HTTP 测试覆盖禁用、恢复、软删除、错误 Secret 的优先级，以及管理端创建 Key 被拦截。最近一次完整测试为 136/136。
