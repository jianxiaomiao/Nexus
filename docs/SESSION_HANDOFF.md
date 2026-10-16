# Nexus Session Handoff

## 当前阶段

2026-10-16，Application 级生产限流已完成当前实现与聚焦验证。限流覆盖全部 `/v1/*`，位于 API Key 认证之后、Quota 和业务之前；管理端 `/api/*` 不经过该限流器。Nginx 本机入口已经用于前期实验，但正式部署配置和“禁止外部直连 Java”仍未实施。

总设计与 IP/Nginx 隔离实验记录见 `docs/RATE_LIMIT_AND_CLIENT_IP_EXPERIMENTS.md`，实验代码与证据位于 `tools/experiments/`。

## 已确定的业务语义

- 同一 Application 的所有 Key 和 `/v1/*` 接口共享每秒 10 次额度，平滑间隔 100ms，空闲不积攒突发额度。
- 每 Application 最多 10 个等待请求、最长等待 1 秒；Application 队列满和等待超时返回各自的 JSON 429，并带 `Retry-After: 1`。恰好到达 1 秒期限仍可放行，超过才超时；队列为 FIFO。
- 调用顺序为 `失败日志 → API Key 认证 → Application 限流 → Quota → 业务`。认证失败不进入限流；限流拒绝不扣 Quota；获得速率许可后发生的 Quota 或业务失败不退还速率时隙。
- IP 只观察和记录，不作为本轮 Application 限流身份。可信客户端地址必须从实际连接对端开始，按明确的代理信任名单解析，不能无条件信任客户端传入的 XFF。
- 生产资源边界为：每 Application 等待 10、全局等待 100、Application 状态 1000。空闲状态 TTL 10 秒，每 5 秒扫描一次；有等待者、仍有调度任务或尚在冷却的状态不能回收。

## 生产实现

- `openapi.ratelimit.ApplicationRateLimiter` 管理每 Application 状态、FIFO 等待者、全局等待信号量、定时放行、超时、取消、关闭和空闲状态回收。专用单线程 `ScheduledExecutorService` 由 Spring Bean 生命周期启动和关闭。
- `openapi.web.ApplicationRateFilter` 使用 Servlet `AsyncContext` 排队，不占用 Servlet 请求线程。结果通过请求属性带到 ASYNC 再分派，第二轮不会重复认证或再次申请限流许可。
- Filter 注册顺序为失败日志 10、机器认证 20、Application 限流 30；三者均允许异步分派。
- `RequestFailureLoggingFilter` 为同步与异步请求共用一次性日志标记。正常完成、异常、超时和多轮 ASYNC 分派最终只记录一次结果。
- 429 与基础设施 503 使用稳定错误码；无效限流身份在 Filter 边界翻译为响应，不能逃逸成容器默认错误页。

## 验证状态

2026-10-16 聚焦回归通过 **103/103**：

- 限流器 53：放行、排队、精确期限、超时、取消、关闭、调度拒绝、并发竞态、状态回收与 Spring 生命周期；
- 限流 Filter 28，身份异常 1；
- 异步失败日志 12，全局异常日志 3；
- 生产 HTTP 调用链 6：认证失败、立即放行、排队后放行、队列满、等待超时和三类基础设施 503。该组串起真实日志 Filter、认证 Filter、限流 Filter 与 UUID Controller，认证器、限流判定和 Quota 作为可控边界；排队用原生 Servlet ASYNC 再分派验证，确认不重复认证、限流或 Quota。

2026-10-16 随后完成完整后端回归：先确认本地生效配置仍指向日常 `nexus`，再从忽略配置中读取保留的 `nexus_test` 专用账号，只对本次 Maven 进程同时覆盖 URL、用户名和密码，并设置连接池上限 2 与一次性 JWT 密钥。启动日志确认 Flyway 使用 `nexus_test`、8 个迁移校验通过；`mvnw clean test` 最终 **382/382** 通过，0 失败、0 错误、0 跳过，包含真实 MySQL、随机端口 HTTP 和 Chromium 截图用例。本地配置文件未修改。

同日完成真实 Spring + MySQL + Nginx 生产链路验收。可重复脚本为 `nexus-server/scripts/accept-rate-limit-nginx.ps1`，本次使用隔离的 `18082 → 18083`。同一 Application 的两把 Key 首批3请求全部200、Quota为3；30并发得到10个200、19个队列满429、1个等待超时429，Quota只增加10。真实截图经Nginx返回200 `image/png`、79075字节并只扣1次Quota；Nginx的20条429与Java上游状态全部一致，日志未出现凭证。Application 1151及两把Key已软删除，随机账号留在可丢弃的 `nexus_test`；所有隔离端口均已关闭。

## 下一步

1. 限流生命周期学习检查、完整后端回归和本机 Nginx 生产路径验收均已完成。
2. 下一阶段进入部署边界：限制 Java 后端入口，并分别验证外部直连失败、经 Nginx 成功和伪造转发头无效。Nginx 读取超时必须覆盖最长排队时间与接口执行时间，截图接口需要单独评估。

工作区包含开发者和 Agent 的大量未提交改动；不得重置或覆盖。代码与测试是实现事实的第一来源。
