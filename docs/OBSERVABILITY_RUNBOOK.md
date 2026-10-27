# Nexus 最小可观测性运行手册

## 适用范围

本手册用于当前单机 Nexus 的本地或回环管理端点排障。管理端点只允许绑定回环地址，不经 Nginx 暴露；当前没有 Prometheus/Grafana 历史存储或外部通知渠道，因此不能把一次瞬时采样描述成趋势或事故。

## 先区分三个信号

- HTTP 响应负责告诉当前调用方结果，状态码与错误码合同不因日志级别改变。
- Micrometer 负责低基数聚合、耗时、趋势和当前饱和状态，是判断问题是否持续或扩大时的主要依据。
- 日志负责解释单次事件和保留真实故障堆栈，不用来替代指标计数，也不把单条 WARN 直接等同于事故。

## 收到 WARN 时

| 日志分类 | 先检查 | 当前含义 | 建议动作 |
|---|---|---|---|
| `capacity_protection`，Application 限流 | `nexus.ratelimit.admission` 的 outcome、`nexus.ratelimit.waiters`、`nexus.ratelimit.states`、`http.server.requests` | 队列满、等待超时、全局 waiter 或 state 容量达到已知保护边界 | 确认是否持续、是否只影响单个 Application；再看 CPU、线程和 Hikari 是否先饱和。保护正常工作且随后恢复时不按系统故障处理 |
| `capacity_protection`，截图 | `nexus.screenshot.jobs.unfinished`、`nexus.screenshot.slot.busy`、queue delay、execution、HTTP 503 | 同步 Gate 或异步任务容量已满 | 确认未完成任务是否继续上升、是否能够排空、执行时长是否异常；不要仅因一次 503 扩容 |
| `request_interrupted` | 相同时间段的 HTTP 状态、客户端取消/超时和限流 admission | 已知等待请求被取消 | 判断是否为客户端主动断开或请求生命周期结束；若持续出现，再调查网络或容器超时 |

## 收到 ERROR 时

1. 先按 `code`、`route`、时间范围定位事件，并确认同一请求是否只有一条堆栈。
2. `RATE_LIMITER_UNAVAILABLE` 检查限流调度器、异步完成异常和 JVM 生命周期；不要把它并入正常容量 WARN。
3. `SCREENSHOT_ENGINE_UNAVAILABLE` 或截图任务创建/结果存储失败，检查 Chromium 进程、固定浏览器路径、结果目录权限、磁盘和任务状态收敛。
4. 数据库或 Usage 最终写入故障，检查 Hikari active/idle/pending/acquire、数据库连接与 `nexus.usage.record.failures` 的稳定 reason；重试中的中间异常不等于最终失败。
5. 未分类 5xx、502/504 和未知异常保持 ERROR。以堆栈和请求链路查根因，不通过新增通用捕获、改成 WARN 或返回成功来消除日志。

## 恢复确认

- 限流 `waiters` 回到 0，空闲后 `states` 按 TTL 与扫描周期回收；
- 截图 unfinished 能排空、busy 回到 0，任务终态和结果文件一致；
- Hikari pending 回到 0，没有连接超时；
- Usage failure Counter 不再增长，Quota、Usage 与成功 HTTP 结果按既有业务语义对账；
- JVM、Chromium、随机测试端口和临时实验数据没有残留。

## 日志安全

请求失败日志允许稳定的 `method`、路由模板或路径组、`status`、`code`、`category`、异常类型、安全诊断枚举/数值和耗时。禁止记录完整 API Key、JWT、密码或哈希、Authorization/Cookie、请求体、查询参数、SQL、连接串、原始目标 URL，以及 User/Application/API Key/job/event ID 等请求级高基数字段。后台任务确有排障需要时可记录非敏感 jobId/eventId，但不得把它们用作 Micrometer 标签。具体分级见 [日志说明](LOGGING.md)，指标和实验边界见 [OBS-1 基线](OBSERVABILITY_BASELINE.md)。
