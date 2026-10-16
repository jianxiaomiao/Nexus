# HTTP-1/HTTP-2：认证、限流、Quota 与业务顺序实验

日期：2026-10-14。

## 问题

Application 限流必须使用认证得到的 `applicationId`。因此要先认证，再限流；只有获得速率许可的请求才能进入 Quota，避免队列满或等待超时的请求扣减周期额度。

## 实验模型

隔离的 Java 模型按以下顺序执行：

```text
authenticate -> rateLimit(applicationId) -> quota(applicationId) -> business
```

使用假认证器、脚本化限流结果、额度为 1 的内存 Quota 和业务调用计数器，不连接 Spring、MySQL 或 Nexus 真实服务。

## 已验证结果

- 无效凭证返回 401，只调用认证；限流与 Quota 调用均为 0。
- 认证成功但限流拒绝返回独立的 `APPLICATION_RATE_LIMIT_QUEUE_FULL` 429；Quota 与业务调用均为 0。
- 限流放行后 Quota 消耗一次，业务执行一次并返回 200。
- 下一次虽获得速率许可，但 Quota 已耗尽：返回 `APPLICATION_QUOTA_EXHAUSTED` 429，业务不执行；速率许可不会退还。

运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\experiments\http-1-2\run.ps1
```

## 结论边界

本实验确定调用顺序和拒绝语义，没有证明真实 Filter、Controller、Quota 事务与数据库集成。生产接入时还需用真实 HTTP 测试验证 401/403、两类速率 429、Quota 429 和业务异常。
