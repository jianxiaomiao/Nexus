# OBS-1 最小可观测性基线

## 当前范围

Nexus 已有日志和 Usage 记录，但它们不能连续回答服务是否变慢、错误是否上升、资源是否饱和，以及限流或截图任务是否积压。OBS-1 先建立最小指标基线，再进行独立的受控负载实验，最后只依据证据决定是否优化。

本设计于 2026-10-23 审定。当前阶段只确定指标语义和验证方法：

- 不安装 Prometheus 或 Grafana；
- 不开放公网监控端口，也不修改 Nginx、UFW 或安全组；
- 不开始压力测试；
- 不决定确切管理端口、直方图区间或 p95/p99 聚合配置；
- 不修改 ECS，V9 仍未部署到 ECS。

## 指标出口与管理边界

2026-10-24 已完成 Actuator 与 Prometheus 格式出口的最小对照实验，并审定：

- 保留 `spring-boot-starter-actuator`，使用 `/actuator/metrics` 进行人工诊断；
- 保留 `micrometer-registry-prometheus`，使用 `/actuator/prometheus` 提供机器可抓取格式；registry 只维护当前 JVM 中的 Meter 并输出抓取文本，不安装 Prometheus 服务，也不保存历史时间序列；
- 只暴露 `health`、`metrics`、`prometheus` 三个 Endpoint ID。Actuator 根发现页中的 `health-path` 和 `metrics-requiredMetricName` 是对应端点的路径模板，不是额外端点；
- 管理 Web Server 必须使用区别于业务端口的独立端口，并只绑定 `127.0.0.1`；不得经 Nginx 转发，也不得增加 UFW 或安全组入站规则；
- 本地实验使用 `127.0.0.1:8081`。监听表确认地址精确为 `127.0.0.1`；业务端口 8080 的 health、metrics 均返回 404；管理端口的 health、metrics、prometheus 返回 200，而未开放的 env 返回 404；
- `/actuator/metrics/process.uptime` 与 Prometheus 文本中的 `process_uptime_seconds` 均随同一 JVM 运行时间增长，证明两个出口读取同一观测来源而非 JSON 到文本的二次转换。

当前不决定部署环境的确切管理端口、抓取周期和历史存储；这些值在真正接入采集器时再按部署边界配置。

## 指标类型

- **Timer：** 记录耗时，同时自带事件次数。若 Timer 已能提供所需计数，不再添加重复 Counter。
- **Counter：** 记录只增不减的累计事件，适合没有有意义耗时的失败或丢失事件。
- **Gauge：** 表示采样瞬间的当前状态；趋势依赖监控系统持续采样，不能把单次 Gauge 读数当作累计历史。

## 第一批指标

| 问题 | 指标及明确语义 | 指标类型 | 验证方法 |
|---|---|---|---|
| HTTP 是否变慢、错误是否上升、流量是多少？ | 框架指标 `http.server.requests`，保留 `method`、URI 模板、`status`、`outcome`；不使用原始 URI | Timer | 分别制造 200、400、认证 Filter 的 401、限流 Filter 的 429；验证每个请求只增加一次 count、耗时非负、URI 中没有真实 ID 或凭证。必须实际确认 Filter 提前返回也被覆盖 |
| JVM 是否接近内存、线程或 CPU 边界？ | `jvm.memory.*`、`jvm.threads.*`、`process.cpu.*`、`system.cpu.*`、`process.uptime`、`disk.*` | Gauge / TimeGauge | 启动后验证指标存在、标签有限、数值范围合理；执行一次普通 API 和一次截图后观察变化，不对运行时瞬时值做精确断言 |
| GC 是否开始造成明显停顿？ | `jvm.gc.*`，重点关注事件产生后的 `jvm.gc.pause` | Timer / Counter / Gauge | 验证 `JvmGcMetrics` Binder 已装配；若本次 JVM 自然产生 GC 事件，再验证 meter 的 count、total、max 与单位。功能测试不强制触发 GC，也不把“尚无事件序列”误判为配置缺失 |
| 数据库连接池是否饱和或取连接变慢？ | `jdbc.connections.*`、`hikaricp.*`，关注 active、idle、max、pending/acquire | Gauge + Timer | 在 Hikari 上限为 2 的隔离集成测试中占满两个连接，让第三个借用短暂等待，验证 active/idle/pending 的状态迁移和 acquire 次数；不制造持续并发负载 |
| 限流请求是在立即通过、排队后通过，还是被拒绝？等待多久？ | `nexus.ratelimit.admission`；从进入限流器到最终决策，每个请求只记录一次。`outcome` 为 `allowed_immediate`、`allowed_after_wait`、`application_queue_full`、`global_queue_full`、`state_capacity_reached`、`timed_out`、`cancelled`、`unavailable` | Timer | 复用确定性限流器测试逐一触发各结果；验证 count 恰好增加 1。排队结果验证等待时间，立即结果应接近 0 |
| 当前有多少请求正在限流队列等待？ | `nexus.ratelimit.waiters`，全局当前等待数，不按 Application 分组 | Gauge | 控制一个请求进入队列，验证 `0 → 1 → 0`；分别覆盖放行、超时和取消后的归零 |
| 当前限流器保存了多少 Application 状态？是否逼近 1000 上限？ | `nexus.ratelimit.states`，只记录状态总数 | Gauge | 创建多个状态后验证增长；推进时间并执行空闲清理后验证回落。不使用 Application ID 标签 |
| 异步截图从提交到首次执行等了多久？ | `nexus.screenshot.queue.delay`，只记录 `created_at → 首次 started_at`；租约重试不重复计算首次排队时间 | Timer | 使用固定 `Clock` 提交、推进时间并首次认领，验证一次样本及预期时长；重排后再次认领不增加 count |
| 唯一截图执行槽一次被占用多久？同步和异步是否不同？ | `nexus.screenshot.execution`；每次真正取得 Gate 后记录一次，`mode=sync\|async`，`outcome` 只使用有限枚举 | Timer | 使用可阻塞的假 Renderer，验证成功和失败都只记录一次；异步重试按执行尝试分别计数。进程直接崩溃时可能没有完成样本，由 uptime、busy 和 unfinished 共同暴露 |
| 当前持久化截图积压是否逼近全局上限 10？ | `nexus.screenshot.jobs.unfinished`，MySQL 中 `QUEUED + RUNNING` 总数 | Gauge | 在 `nexus_test` 写入已知状态，验证 `0 → N → 0`。第一批只查总数，不增加 status 维度和多次查询 |
| 唯一 Chromium 执行槽现在是否被占用？ | `nexus.screenshot.slot.busy`，值只能为 0 或 1 | Gauge | 阻塞假 Renderer，验证 `0 → 1 → 0`；成功和异常路径都必须归零 |
| Usage 是否在客户端已收到响应后无法确认记录成功？ | `nexus.usage.record.failures`，`reason=queue_rejected\|transient_write_exhausted\|duplicate_unconfirmed\|write_failed` | Counter | 分别模拟执行器拒绝、瞬时异常重试耗尽、重复键且无法确认原事件、其他写入失败；各自恰好增加 1。成功写入、瞬时异常重试成功以及重复键但确认已写入时不增加 |

## 已审定的取舍

### 第一个自定义 Meter 验证

2026-10-24 已实现 `nexus.ratelimit.admission`。第一次请求分派在调用限流器前启动 `Timer.Sample`；立即结果同步停止，排队结果跨线程保留到 `CompletionStage` 产生最终决定时停止；Servlet ASYNC 二次分派只消费已有决定，不重新开始或重复记录。八个 `outcome` 由枚举固定，不接受 Application ID、请求路径或其他动态标签。

聚焦测试通过 36/36：使用 Micrometer `MockClock` 验证排队 250 ms、二次分派后 count 仍为 1，并覆盖立即放行、排队放行、四类立即拒绝、超时、取消、不可用及取消/关闭竞态的一次终态记录。

本地真实应用通过一个有效 API Key 完成一次立即放行后：

- Prometheus 文本为 `count=1`、`sum=0.0014395s`、当时的 `max=0.0014395s`；
- Actuator 按 `outcome=allowed_immediate` 过滤后为 `COUNT=1`、`TOTAL_TIME=0.0014395s`；稍后读取的 `MAX=0`，符合时间窗口最大值可过期的语义；
- 两个出口的累计次数和总时间一致，证明自定义 Meter 已同时进入诊断与抓取出口；本次不是压力测试，也未验证排队场景的真实网络请求。

随后在显式 `local` Profile、专用 `nexus_test` 账号、Hikari 1..2、一次性 JWT 和禁止浏览器下载的边界下运行 `mvnw clean test`。Flyway 确认 schema 为 `nexus_test` 且 9 个迁移已是最新；完整后端回归 **415/415** 通过，0 失败、0 错误、0 跳过。应用上下文、限流 HTTP 调用链、真实随机端口 HTTP/MySQL、Usage、短链和 Chromium 截图均通过；测试 JVM 与 `chrome-headless-shell` 无残留。相较此前 414 项增加的 1 项是立即放行只记录一次的指标测试。

### 限流器 Gauge 验证

2026-10-24 已实现 `nexus.ratelimit.waiters`。它是一个无标签的全局 Gauge，由 `MeterBinder` 在启动时注册一次；取值直接使用限流器既有的全局 `Semaphore`，计算为“最大等待槽数减去当前可用许可数”。它不遍历每个 Application 的非线程安全队列、不复制一份可漂移的计数，也不按 Application 动态注册 Meter。

2026-10-24 随后实现 `nexus.ratelimit.states`，直接读取限流器 `ConcurrentHashMap` 的当前条目数。第二个同组件、同生命周期的 Gauge 出现后，不再保持“一指标一类”：原 `ApplicationQueueMetrics` 合并并重命名为 `ApplicationRateLimiterMetrics`，由一个 `MeterBinder` 同时注册 waiters 和 states；主动记录 admission Timer 的类保持独立。

确定性测试把真正注册到 `SimpleMeterRegistry` 的 Gauge 作为观察对象：waiters 验证 `0 → 1 → 0`；states 验证初始为 0，两个 Application 分别创建状态后为 1、2，推进到空闲 TTL 并运行清理后回到 0。`ApplicationRateLimiterTests` 共 **55/55** 次测试执行通过；本轮没有重复完整后端回归。

随后使用现有 `local` Profile 和仅存在于临时进程的一次性 JWT secret 启动真实应用，没有调用业务 API 或制造并发负载。管理端口仍精确监听 `127.0.0.1:8081`，两个出口观察到同一当前状态：

- `/actuator/metrics/nexus.ratelimit.waiters` 与 `/actuator/metrics/nexus.ratelimit.states` 均返回 `VALUE=0.0`、无可用标签，并保留各自描述；
- `/actuator/prometheus` 同时返回 `nexus_ratelimit_waiters 0.0` 与 `nexus_ratelimit_states 0.0`，类型均为 Gauge；
- 真实进程中的 0 表示观察时没有等待请求；短暂的 `0 → 1 → 0` 由可控调度测试验证，避免为了抓住生产配置中的 100 ms 等待窗口而制造负载；
- 观察完成后应用优雅停止，8080/8081 无残留监听。

### 一个限流 Timer 代替重复指标

限流不同时维护“等待 Timer”和“拒绝 Counter”。`nexus.ratelimit.admission` 的 count 已能按有限 `outcome` 统计各类拒绝，同时保留最终决策耗时。查询等待分布时只选择排队相关 outcome，避免立即结果稀释等待时间。

### 第一批只统计截图未完成总数

`nexus.screenshot.jobs.unfinished` 与 `nexus.screenshot.slot.busy` 联合使用：

- unfinished 增长且 busy 为 1，表示单槽正在工作但积压形成；
- unfinished 大于 0 且 busy 长时间为 0，表示 Worker、调度或认领链路可能异常；
- 第一批不增加 `status` 维度。只有实践证明总数不足以定位时，才考虑有限状态拆分。

### Screenshot execution 表示执行槽占用时间

单机截图吞吐受唯一 Gate 的占用时间约束，因此 `nexus.screenshot.execution` 测量取得 Gate 到释放 Gate 的时间，而不只包围 Chromium 调用：

- 同步路径包括配额操作和渲染；
- 异步路径包括任务认领、渲染、文件发布和终态更新；
- Gauge 回答“现在是否忙”，Timer 回答“一次占用多久”，两者不重复。

### 截图子系统指标实现与验证

2026-10-24 已实现四项截图指标，并保持各自的权威数据源：

- `nexus.screenshot.queue.delay` 由持久化任务的 UTC `created_at` 与首次成功认领写入的 `started_at` 相减得到。这个边界可能跨进程重启，不能用只存在于当前 JVM 的 `Timer.Sample`；实现使用 `Timer.record(Duration)`，且只在认领事务提交后、首次 `started_at` 原本为空时记录。租约重排不会重复计数，负时长不会污染 Timer；
- `nexus.screenshot.execution` 在真正取得共享 Gate 后启动 `Timer.Sample`，在释放 Gate 前停止。同步路径覆盖配额与渲染，异步路径覆盖认领事务、渲染、文件发布和终态更新；标签只使用 `mode=sync|async` 与 `outcome=succeeded|failed`。URL 校验失败和未取得 Gate 不产生样本，取得 Gate 后的异常仍以 failed 结束，`finally` 的嵌套保证指标停止失败也不能阻止 Gate 释放；
- `nexus.screenshot.jobs.unfinished` 每次采样直接执行既有 `countUnfinished()`，权威值是 MySQL 中 `QUEUED + RUNNING` 总数，不维护第二份内存计数。数据库访问失败返回 `NaN`，避免把“观测不可用”伪装成零积压；
- `nexus.screenshot.slot.busy` 直接读取共享公平 `Semaphore` 的当前许可，空闲为 0、唯一许可被占用为 1；同步 HTTP 线程和异步 Scheduler 工作线程看到的是同一个 Gate。

确定性验证分三层完成：17 项单元测试使用 `MockClock` 和真实 registry 验证 queue delay 250 ms、execution 125/250 ms、成功/失败一次计数、首次认领与重试边界、busy `0 → 1 → 0`，以及 unfinished 每次重新查询和数据库失败为 `NaN`；5 项真实 MySQL 恢复测试在专用 `nexus_test` 上验证 unfinished `0 → 2 → 0`；1 项随机管理端口集成测试通过 HTTP 同时读取四个 `/actuator/metrics/{name}` 和 `/actuator/prometheus`，确认四项指标进入两种出口。

随后在显式 `local` Profile、专用 `nexus_test` 账号、Hikari 1..2、一次性 JWT、禁止浏览器下载并允许 Chromium 子进程执行的边界下运行完整后端回归：Flyway 确认 schema 为 `nexus_test` 且 V1–V9 已是最新，**425/425** 通过，0 失败、0 错误、0 跳过。测试没有安装 Prometheus/Grafana、没有开放管理端口、没有制造压力负载，也没有修改 ECS。

### Usage 最终记录失败 Counter

2026-10-24 已实现 `nexus.usage.record.failures`。同一个 Counter 名只使用固定 `reason` 标签，不把异常类名、异常消息、API Code 或任何身份 ID 放入标签：

- `queue_rejected`：HTTP 请求已经完成，但 `UsageInterceptor` 向有界执行器提交记录任务时被拒绝；
- `transient_write_exhausted`：`TransientDataAccessException` 经过 `@Retryable` 的首次执行和两次重试仍失败；中间失败不计数；
- `duplicate_unconfirmed`：写入返回 `DuplicateKeyException`，随后按 eventId 查不到已有记录，或确认查询本身失败；
- `write_failed`：其他非瞬时、非重复键的最终写入异常。

成功写入、瞬时异常重试后成功，以及重复键后确认同一 eventId 已存在都不计入 failure。异常类和堆栈继续保留在日志中，Counter 标签只表达稳定、可行动的终态原因。

确定性测试 **7/7** 通过：四种失败分别只增加对应 Counter；真实异步代理与重试链验证三次写入尝试最终只增加一次 `transient_write_exhausted`；重试成功和已确认重复均保持所有 failure Counter 为 0。随机业务/管理端口集成测试 **1/1** 在专用 `nexus_test` 上通过 HTTP 验证 Actuator JSON 与 Prometheus 文本都包含四种 reason。随后完整后端回归 **428/428** 通过，0 失败、0 错误、0 跳过；测试边界仍为显式 `local`、专用测试账号、Hikari 1..2、一次性 JWT、禁止浏览器下载并允许既有 Chromium 测试执行。

### 框架指标受控验证

2026-10-24 完成三项无持续负载的框架指标实验，并各自通过随机业务端口、随机回环管理端口和真实 `nexus_test` 连接验证：

- `http.server.requests`：一次 200、一次 Bean Validation 400、认证 Filter 提前返回 401、限流 Filter 提前返回 429，各自只增加一个 Timer count。进入 Controller 的请求使用 `/v1/utils/uuid`、`/v1/utils/hash` 模板；在 HandlerMapping 之前被 Filter 截断的 401/429 使用低基数 `uri=UNKNOWN`。两个出口均不包含测试 jobId、查询参数或 API Key 凭据；
- Hikari：在 `maximumPoolSize=2`、`minimumIdle=1` 的隔离上下文中，手动借用连接观察到 active `0 → 1 → 2 → 2 → 0`，池满时 idle 为 0；第三个借用线程令 pending `0 → 1 → 0`，释放一个连接后等待者取得该连接；`hikaricp.connections.acquire` 至少增加 3。Actuator 与 Prometheus 同时确认 active、pending 等序列已导出；管理出口在池未饱和时抓取，避免全量抓取干扰短暂等待窗口；
- JVM/进程/磁盘：验证 `jvm.memory.used` 为非负字节值且带有限 `area/id` 标签，live thread 至少为 1，CPU usage 为 `NaN` 或 `[0,1]`，`process.uptime` 为正秒值，disk free/total 为非负字节且 free 不大于 total。Actuator 使用 `baseUnit="seconds"`，Prometheus 使用 `_seconds`、`_bytes`、`_threads` 后缀；
- GC：Spring 上下文必须存在 `JvmGcMetrics` Binder。`jvm.gc.pause` 等事件序列由 JVM 通知惰性创建：同一测试在不同冷启动中可能尚未出现，也可能已自然出现；出现时才验证 count/total/max 非负、Actuator 秒单位及 Prometheus 序列。不调用 `System.gc()`，也不通过大量分配变相强制 GC。

三项聚焦联合测试 **3/3** 通过。随后以显式 `local`、专用 `nexus_test_user`、Hikari 2/1、一次性 JWT、禁止浏览器下载并允许既有 Chromium 子进程的边界运行 `mvn -o clean test`，完整后端回归 **431/431** 通过，0 失败、0 错误、0 跳过。

### 单 Application 限流受控负载实验

2026-10-24 使用真实 `失败日志 Filter → API Key 认证 Filter → Application 限流 Filter → UUID Controller → Quota → Usage` 链路完成首轮受控负载实验。实验只绑定 `127.0.0.1:8080/8081`，数据库身份闸门先通过 JDBC 断言 `DATABASE()=nexus_test`、`CURRENT_USER()` 为专用 `nexus_test_user`；Hikari 固定 10/2，JVM 固定 `-Xms128m -Xmx512m`。一个全新 User/Application/API Key 以均匀固定到达率调用 `GET /v1/utils/uuid`，每档 15 秒，客户端最大在途上限固定 64、请求超时 3 秒、keep-alive、不补发落后的请求。各档之间等待 Usage 收敛以及限流 `waiters=0`、`states=0` 后再继续。

| 到达率 | 计划请求 | 200 | 429 | admission 主要结果 | waiters 峰值 | 客户端 p95 |
|---:|---:|---:|---:|---|---:|---:|
| 5 RPS | 75 | 75 | 0 | immediate 75 | 0 | 31.9 ms |
| 10 RPS | 150 | 147 | 3 | immediate 3、wait 144、queue full 1、timeout 2 | 10 | 996.5 ms |
| 12 RPS | 180 | 147 | 33 | immediate 1、wait 146、queue full 24、timeout 9 | 10 | 1008.6 ms |
| 20 RPS | 300 | 147 | 153 | immediate 1、wait 146、queue full 140、timeout 13 | 10 | 1009.7 ms |

结果解释：

- 5 RPS 全部立即放行；12 与 20 RPS 的成功数都保持 147/15 秒，约 9.8 RPS，新增到达量转化为有界等待和 429，没有把成功吞吐推高；
- 理论 10 RPS 边界已经逐步积累到满队列，并出现 3 个 429。当前实现每次实际放行后以 `now + 100ms` 设置下一许可，调度与执行延迟会累积，因此“100ms 最小间隔”保证不超过 10 RPS，但不保证真实环境长期恰好达到 10 RPS。2026-10-24 已明确接受这一保守上限语义，不做 9 RPS 复验，也不修改算法；
- `allowed_after_wait` 平均 admission 时间随负载从 602.94 ms 增至 824.69/912.28 ms；`timed_out` 平均为 1003.68–1009.01 ms，与配置的 1 秒期限一致；`application_queue_full` 平均约 0.01–0.30 ms。Timer 因此准确区分了排队后终态和立即拒绝；
- 10/12/20 RPS 的 waiters 峰值均为 10，未越界；所有阶段 states 峰值为 1，冷却后回到 0。没有 global queue full、state capacity、cancelled、unavailable 或 503；
- Hikari 最大 active 为 1、pending 始终 0；进程 CPU 峰值约 3.1%–5.2%，Heap 观察范围约 38–105 MiB，live threads 峰值稳定为 49；每档自然发生一次 3–8 ms GC pause。数据库、JVM 和线程没有先成为瓶颈；
- 客户端最大在途为 11，远低于 64，最大发送迟到约 17 ms，低于最短的 50 ms 发送间隔。客户端未限速或制造追赶突发；
- 四类 `nexus.usage.record.failures` 增量均为 0。含预检请求共 517 个成功请求，最终 Quota used 与 Usage 行数均精确为 517；所有实验数据随后按精确 ID 物理删除：517 Usage、1 个配额周期、1 个 Key、1 个 Application、1 个 User，无短链或截图任务残留；
- Actuator 用于 admission/HTTP 累计值和 waiters/states/Hikari/JVM 的分频采样；Prometheus 在每档开始、中点、结束各抓一次。静止边界的两个出口 count/sum 一致，Prometheus 的 HTTP 200/429 增量也与客户端结果逐档一致。全量抓取次数受限，避免 `nexus.screenshot.jobs.unfinished` Gauge 的 MySQL 查询成为持续干扰。

可重复工具为 `nexus-server/scripts/controlled-rate-limit-experiment.mjs` 与 `RateLimitExperimentDatabaseGuard.java`；原始本地证据保存在忽略的 `target/rate-limit-experiment/obs-rate-2026-10-24T10-25-27-420Z-348ef915/`。本轮未安装 Prometheus/Grafana、未开放公网监控端口、未访问外部网站、未修改或压测 ECS。

### 多 Application 隔离实验

2026-10-25 已将下一轮设计固化为 `controlled-multi-application-isolation-experiment.mjs`：每个 B 档位创建新的 User、Application A/B 和两把 Key；先运行 B 单独 10 秒作为 control，待 Usage 收敛且 `waiters/states` 回到 0，再让 A 以 20 RPS 预热 2 秒，并与 B 的 1/5/10 RPS 并行 10 秒。A、B 各自拥有 keep-alive Agent 和 64 个最大在途槽位，3 秒超时、均匀固定到达、不补发追赶请求。这样只改变“是否存在 A 的重负载”，并以 B 自身 control 为比较基线。

预测与判据如下：B=1/5 RPS 时请求应全部成功；B=10 RPS 时允许出现它自身保守限流造成的 429，但 treatment 的成功数不得低于 control 的 90%，p95 不得高于 control 的 110%。control 的 `states` 峰值必须为 1，treatment 必须为 2；全局 `waiters` 在 B=1/5 时不超过 10，在 B=10 时不超过 20。任何 global queue full、state capacity、cancelled、unavailable、非 200/429、Hikari pending、Usage failure、客户端发送迟到/在途上限或 Tomcat 持续满线程均判失败。Quota、Usage、HTTP 200/429 与 admission 总数必须和客户端逐阶段对账。

Actuator JSON 由脚本通过 HTTP 调用 `/actuator/metrics/{name}?tag=...`：限流 Gauge 每 200 ms、Hikari/Tomcat 每 500 ms、JVM 每 1 秒采样；Prometheus 文本由 `/actuator/prometheus` 在每阶段开始、中点、结束抓取并保存，静止边界的 admission、HTTP 和 Usage failure 增量必须与 Actuator 相等。这里 Node 内置 `fetch`/`http.Agent` 只是 HTTP 客户端，不是另一套指标来源。预检还发现当前默认 `server.tomcat.mbeanregistry.enabled=false`，既有 Prometheus 样本只有 Tomcat session 指标，没有线程池/连接指标；本次实验 JVM 必须用仅进程级的 `SERVER_TOMCAT_MBEANREGISTRY_ENABLED=true` 启动，脚本会在发压前检查必需 meter，缺失即停止，不修改持久配置。

2026-10-25 在上述设计下完成实验。预检首次在发压前因全新 JVM 尚未惰性创建 `http.server.requests` 而停止；脚本随即改为先发送一条预期 401 的无凭据 UUID 探针，只注册 HTTP meter，不进入限流、Quota 或 Usage。随后全部必需 meter、Tomcat 两种出口和冷状态检查通过，正式结果如下。表中 A 是 2 秒预热与 10 秒测量的合计，`200/429` 均为客户端观测：

| B 速率 | B control 200/429 | B control p95 | A+B 时 A 200/429 | A+B 时 B 200/429 | B treatment p95 / control | treatment waiters / states |
|---:|---:|---:|---:|---:|---:|---:|
| 1 RPS | 10/0 | 28.19 ms | 119/121 | 10/0 | 56.2% | 10 / 2 |
| 5 RPS | 50/0 | 16.17 ms | 119/121 | 50/0 | 98.6% | 10 / 2 |
| 10 RPS | 100/0 | 812.89 ms | 119/121 | 100/0 | 102.2% | 18 / 2 |

三组均通过预定判据：B 成功数均为 control 的 100%，B=1/5 无 429，B=10 的 p95 只增加 2.2%，低于 10% 容差。B=10 即使 10 秒内全部成功，也已有 98/100 个请求排队、waiters 峰值 8；这不推翻单 Application 15 秒实验在理论边界最终出现 429 的结论。A 的重负载只在 A 自己形成稳定 429，没有夺走 B 的 Application 队列或许可；全局等待者最多 18，远低于 100。

每个 control 的 `states=1`、每个 treatment 的 `states=2`，冷却后均回到 0。所有阶段没有 global queue full、state capacity、cancelled、unavailable、非 200/429 或客户端中止。HTTP 200/429、admission 总数、Quota 与异步 Usage 逐阶段精确匹配；Actuator 与 18 份 Prometheus 开始/中点/结束快照的 admission、HTTP 和 Usage failure 增量一致。

资源没有成为竞争变量：Hikari active 峰值 3/10、pending 始终 0；Tomcat busy 峰值 3/200、连接峰值 24；进程 CPU 峰值 5.45%，Heap 峰值 106.8 MiB，live threads 峰值 50；四类 Usage failure 均为 0。客户端最大在途 11/64，最大发送迟到 15.51 ms，没有追赶突发。三组分别物理删除 139、219、319 条 Usage，共删除 6 个配额周期、6 个 Key、6 个 Application 和 3 个 User，反查均为零残留；无短链或截图任务。实验 JVM日志无 ERROR/WARN，停止后 8080/8081 无监听。

原始结果位于忽略的 `target/multi-application-isolation-experiment/obs-multi-app-2026-10-25T02-54-27-035Z-343748a5/`。实验未安装 Prometheus/Grafana、未开放公网监控端口、未访问外部网站、未修改或压测 ECS。

### 全局 waiter 容量实验（稳定性验收未完成）

2026-10-25 将“11 个 Application”明确为总数包含 A，即处理组为 `A + 10 个 B`；负对照为 `A + 9 个 B`，共 10 个 Application。A 固定 20 RPS 并预热 2 秒，随后 A 与每个 B=10 RPS 同时运行 20 秒；每个 Application 各自使用 keep-alive Agent、64 个最大在途槽位和 3 秒超时。B 在一个 100 ms 周期内均匀错开，避免同一时刻起始突发。处理组的客户端安全上限因此是 `11 × 64 = 704`，它只是防止客户端失控的上限，不是目标并发数。

先以确定性测试隔离验证容量语义：创建 11 个状态，前 10 个 Application 各放入 10 个等待者后全局等待数恰好为 100；第 11 个 Application 的下一个请求仍有自己的队列空间，但得到 `GLOBAL_QUEUE_FULL`，等待数保持 100；随后向已满的第 1 个 Application 再请求得到 `APPLICATION_QUEUE_FULL`，也验证了拒绝判定顺序。该聚焦测试 **1/1** 通过。

真实链路结果如下；只有客户端调度健康、HTTP/admission/Usage/Quota 可对账且资源指标未先饱和的轮次才计为有效：

| 轮次 | 总 Application | 计划请求 | 200 / 429 / 503 | `global_queue_full` | waiters / states 峰值 | 判定 |
|---|---:|---:|---:|---:|---:|---|
| 负对照 | 10（A+9B） | 2240 | 1949 / 291 / 0 | 0 | 97 / 10 | 有效，未触顶 |
| 处理 r1 | 11（A+10B） | 2440 | 2138 / 272 / 30 | 30 | 100 / 11 | 有效，触顶 |
| 处理 r2 | 11（A+10B） | 2440 | 2140 / 265 / 35 | 35 | 100 / 11 | 有效，触顶 |
| 处理 r3 | 11（A+10B） | 2440 | 未形成完整对账 | 未判定 | 未判定 | 无效轮次，停止 |

两次有效处理组都在 waiters=100 时出现 `global_queue_full`，负对照峰值 97 且该 outcome 为 0。以负对照中 9 个 B 为基线，B 平均成功数为 193、成功请求 p95 中位数为 994.19 ms；r1 分别为 192.6（99.79%）和 988.04 ms（99.38%），r2 为 192.8（99.90%）和 989.84 ms（99.56%），均在约定的 10% 容差内。有效轮次中 Hikari pending 与连接超时增量均为 0，Hikari active 峰值为 4/7/5，Tomcat busy 峰值为 5/5/6（上限 200），连接峰值为 123/127/127；客户端最大在途 11/11/12，最大发送迟到 15.04/15.49/23.26 ms。数据库池、Tomcat 池或客户端没有先成为有效轮次的瓶颈。

第三次处理组不能用来证明或反驳全局容量的稳定性：Prometheus 的 admission 累计增量在测量第 5/10/15/19.5 秒仅为 597/645/694/740，说明约第 5 秒后大部分发送流已因超过一个发送间隔而按保护规则中止，只剩约 10 RPS 继续；脚本随后在 Usage 收敛检查发现 B1 超过客户端已完成成功数并停止。服务日志同时记录 4 个请求在配额短事务中发生 MySQL deadlock，异常可定位到 `ApplicationQuotaService.consumeOnceAndCommit` 内 API Key 的 `SELECT ... FOR UPDATE`，但没有保存 InnoDB deadlock 图，不能据此断言完整锁循环的根因。

数据库停止前的交叉核对显示 r3 共写入 685 条 Usage、配额只成功增加 681 次，差值恰好为 4；所有 685 条 Usage 都记为 HTTP 200。当前 `UsageInterceptor.afterCompletion` 直接读取当时的 `response.getStatus()`；未处理异常逃逸时，容器最终改写 500 可能尚未发生，因此“afterCompletion 一定能读到最终 HTTP 状态”这一假设不成立。这是本次负载实验发现的独立正确性问题，不应通过重跑直到绿色来掩盖。

有效的负对照、r1、r2 各保存 6 份 Prometheus 快照，共 18 份；静止边界的 Actuator 与 Prometheus admission、HTTP 和 Usage failure 数据一致。脚本已增强为每轮在等待 Usage 前先落盘客户端摘要并立即执行客户端健康判定，便于后续无效轮次保留证据。四组资源均已按精确 ID 物理清理并反查不存在：共 6912 条 Usage、43 个配额周期、43 个 Key、43 个 Application、4 个 User，无短链或截图任务；实验 JVM 已停止，8080/8081 无监听。

因此首次实验当时的结论是：确定性边界通过，且 `A + 10B` 在 **2/2 个有效真实轮次**触达全局 100 waiter 上限，`A + 9B` 负对照未触达；预定的 **3/3 稳定性判据尚未完成**。当时决定不继续盲目复跑，而是先把客户端发送流隔离方案与生产侧 Quota deadlock、异常 Usage 状态分别作为单变量问题审定。原始结果位于忽略的 `target/global-waiter-capacity-experiment/obs-global-waiters-2026-10-25T03-25-56-698Z-80b4caa8/`。

### 客户端发送流隔离改造

2026-10-25 将全局 waiter 实验客户端从单一 Node 事件循环改为协调进程加独立 Worker：A 使用一个 Worker 保持自己的预热/测量时间轴和 keep-alive Agent，B1–B10 各使用一个 Worker，Actuator/Prometheus 采样使用第十二个 Worker。协调进程只负责资源准备、向全部 Worker 提供同一个测量开始墙钟、等待全部 Worker 返回摘要、对账和清理；B Worker 仍按统一时间轴做 0–90ms 相位错开，避免在测量起点制造 10 请求突发。任一 Worker 调度落后、响应堆积或失败只使该 Worker 的摘要无效，不会停止其他发送 Worker；协调进程使用 `Promise.allSettled` 等待全部 Worker 结束后才判整轮失败。

脚本新增不访问 Nexus、MySQL 或外部网络的 `--worker-preflight`。确定性预检创建 A、B1–B10 与 metrics 共 12 个不同线程，用统一开始时间分别模拟 50ms 和 100ms 节拍；12/12 返回完整摘要，最大调度迟到 16ms，没有超过各自一个发送间隔。这个结果只证明客户端线程、Agent 和时间轴已经隔离，不替代真实负载结论；其后的真实复验结果见下一节。

### 全局 waiter 容量复验

2026-10-25 在全新本地 JVM、专用 `nexus_test`、Hikari 10/2、Tomcat MBean 仅进程级开启的环境中复验。启动前数据库身份 Guard、回环 8080/8081、health、Hikari 上限、Tomcat meter 和 12 Worker 时钟预检均通过。固定负对照 `A + 9B` 与三个 `A + 10B` 处理组仍分别发送 2240、2440 个请求，结果如下：

| 轮次 | 200 / 429 / 503 | `global_queue_full` | waiters / states 峰值 | B 平均成功数 / control | B p95 / control | 判定 |
|---|---:|---:|---:|---:|---:|---|
| 负对照 | 1942 / 298 / 0 | 0 | 99 / 10 | 基线 192.33 | 基线 999.34ms | 有效，未触顶 |
| 处理 r1 | 2139 / 269 / 32 | 32 | 99 / 11 | 100.19% | 98.91% | 有效，触顶 |
| 处理 r2 | 2132 / 273 / 35 | 35 | 100 / 11 | 99.88% | 99.72% | 有效，触顶 |
| 处理 r3 | 2139 / 260 / 41 | 41 | 100 / 11 | 100.19% | 99.21% | 有效，触顶 |

r1 的 100ms Gauge 采样峰值为 99，但同一轮出现 32 次服务端 `global_queue_full` 最终决策，说明 100 容量在两个采样点之间被占满；Gauge 没有采到瞬时 100 不推翻 admission 事件。四轮所有计划请求都得到 200/429/503，客户端零中止、零异常响应；最大调度迟到 41.33ms，低于最短 50ms 间隔，最大在途 16/64。每轮实际 Worker thread 数分别为 11/11、12/12、12/12、12/12，发送流没有再次关联失速。

Hikari active 峰值均为 6、pending 和连接超时增量均为 0；Tomcat busy 峰值为 7/5/20/7（上限 200），连接峰值最高 138；进程 CPU 峰值最高 13.16%，Heap 峰值最高 228.33MiB，live threads 峰值 88。四类 Usage failure 增量均为 0；Actuator 与 Prometheus 的 admission、HTTP 和 Usage failure 静止边界一致，每轮保存 6 份、合计 24 份 Prometheus 快照。

数据库逐 Application 核对确认 Usage 200 与 Quota used 精确相等，Usage 500 为 0；四组共物理删除并反查 8352 条 Usage、43 个配额周期、43 个 Key、43 个 Application 和 4 个 User，无短链或截图任务。服务日志中的 108 条 ERROR 恰好等于 32+35+41 个预期 503，除此之外无 ERROR、WARN 或 deadlock。实验结束后 waiters/states 为 0，JVM、启动器、8080/8081 与 headless 进程均无残留。原始结果位于忽略的 `target/global-waiter-capacity-experiment/obs-global-waiters-2026-10-25T08-02-11-779Z-a8e15ca3/`。

因此，在修复 Quota/Usage 锁序、Usage 500 语义并隔离客户端发送流后，`A + 10B` 已通过新的 **3/3 有效真实轮次**稳定触发全局等待容量，`A + 9B` 负对照不触发；全局 waiter 容量实验达到当前验收判据。

### Quota/Usage 外键锁序实验

2026-10-25 使用一个全新 Application 与一把 API Key，通过两条事务连接和一条观察连接精确控制顺序；不启动 Nexus、不使用概率性 HTTP 压测，也不修改生产代码。权限预检先确认工作负载仍为 `nexus_test/nexus_test_user`、InnoDB deadlock detect 已开启，且临时观察权限可以读取 `SHOW ENGINE INNODB STATUS`、`performance_schema.data_locks/data_lock_waits/threads`。观察权限只用于本机实验，完成后必须撤销。

无死锁对照先让 Quota 连接按生产顺序同时锁住 Application 与 API Key，再启动真实 `usage_events` INSERT。锁快照显示 Usage 请求 API Key 的 `S,REC_NOT_GAP`，被 Quota 已持有的 `X,REC_NOT_GAP` 单向阻塞；释放 Quota 后 INSERT 继续，双方不存在相互等待，因此没有死锁。

处理组先让 Quota 只锁 Application，再启动相同的真实 Usage INSERT。死锁形成前的确定性快照为：

```text
Quota 连接 55：持有 applications(2397) X,REC_NOT_GAP
Usage 连接 56：持有 api_keys(1608) S,REC_NOT_GAP
Usage 连接 56：等待 applications(2397) S,REC_NOT_GAP
Quota 随后请求：api_keys(1608) X,REC_NOT_GAP
```

MySQL 随即返回 error 1213 / SQLState 40001。立即保存的 `LATEST DETECTED DEADLOCK` 同时包含两个连接和两条真实 SQL：Usage INSERT 持有 API Key 共享记录锁并等待 Application，共享锁来自外键父记录检查；Quota `SELECT ... api_keys ... FOR UPDATE` 持有 Application 独占记录锁并等待 API Key。MySQL 选择回滚事务 2，即 Quota 事务。由此不再只是推测：`usage_events` 的 `api_key_id → application_id` 外键检查锁序与 Quota 的 `Application → API Key` 显式锁序相反，构成了负载实验中的死锁闭环。

实验双方事务最终均 rollback；随后物理删除并反查 1 个 Key、1 个 Application、1 个 User，Usage/Quota/短链/截图任务残留均为 0。原始结果与完整 InnoDB 报告位于忽略的 `target/quota-usage-deadlock-experiment/quota-usage-deadlock-20261025-044326-902-24576da5/`。临时观察权限随后由开发者撤销，使用工作负载账号执行负向预检得到 `PROCESS` 权限拒绝，确认生产测试账号已恢复最小权限。

### Quota/Usage 锁序修复与审计

2026-10-25 在不修改表结构和 Quota 业务语义的前提下修复生产代码。`UsageEventWriter` 的每次重试现在都开启一个独立短事务，先对 Application 执行 `FOR SHARE`，再对归属于该 Application 的 API Key 执行 `FOR SHARE`，最后 INSERT `usage_events`。因此显式顺序与 Quota 的 `Application FOR UPDATE → API Key FOR UPDATE` 一致；共享锁足以保护外键父记录，同时不会把并发 Usage 写入彼此串行化。外键仍负责最终完整性校验，重试仍以完整新事务为单位。

验证分三层：确定性单元测试确认 `begin → Application S → API Key S → Usage INSERT → commit`；真实 MySQL 测试先用 Quota 顺序持有 Application X，再启动 Usage 写入并确认其停在 Application S，随后 Quota 能取得 API Key X 并提交，Usage 才继续插入；Quota、Usage HTTP 与锁序聚焦回归共 40/40 通过。允许既有 Chromium 子进程的完整后端回归随后 **434/434** 通过，0 失败、0 错误、0 跳过，实际 schema 为 `nexus_test`、Hikari 为 2/1。

同时审计全部 `FOR UPDATE`、`FOR SHARE`、事务写入与 V1–V9 外键：Application/Key 管理、Quota、短链创建和截图任务提交都遵循 `Application → API Key → 子记录`；Application 删除也是 Application 后批量 Key、再短链；截图 Worker 只锁 `screenshot_jobs`，直接短链更新只锁短链且不会再回锁父级；Usage 查询是只读一致性读。当前未发现第二条结构性反向锁序。这个结论不等于数据库永远不会出现任何死锁：未来新增多表事务、改变加锁顺序或并发唯一索引写入时仍须重新审计，并保留瞬时数据库异常的边界处理。

### Usage 最终 HTTP 状态与写入失败隔离

2026-10-25 对 `UsageInterceptor.afterCompletion` 做最小修复：已处理异常继续读取响应中已经确定的 HTTP 状态；若响应仍未提交且 `afterCompletion` 收到未处理异常，则 Usage 记录 500，而不是 Servlet 响应对象的默认 200。这个回退只修正 Usage 事件的观测状态，不负责替容器生成客户端响应，也不把 Usage 入库结果混入业务响应。

验证覆盖五类边界：正常 UUID/Hash 请求记录 200，异步截图提交记录 201，已处理业务异常分别记录 400/429/503，未处理异常的真实 HTTP 响应与 Usage 均为 500；强制 `usage_events` INSERT 失败时，原业务响应仍为 200，`nexus.usage.record.failures{reason="write_failed"}` 恰好增加 1，且该 Key 的 Usage 事件数仍为 0。定向单元/真实 HTTP/MySQL 回归 **17/17** 通过；专用 `nexus_test`、Hikari 2/1、允许既有本地 Chromium 子进程的完整后端回归 **438/438** 通过。

## 标签边界

允许的标签必须来自稳定的有限枚举，例如 HTTP 方法、URI 模板、状态码、outcome、截图 mode 和失败 reason。

禁止把以下内容作为标签或指标值暴露：

- API Key、JWT、密码或密码哈希；
- jobId、Application ID、User ID、API Key ID；
- 原始 URL、原始请求路径或请求正文；
- 异常消息、SQL 或其他可能无限增长的动态文本。

## 预期容量拒绝的日志分级

2026-10-27 完成请求失败日志审计并实施按原因分类。HTTP 状态不是日志级别的唯一依据：同为 503，`RATE_LIMIT_STATE_CAPACITY_REACHED` 和 `SCREENSHOT_JOB_QUEUE_FULL` 是主动容量保护，记录 WARN 且不带堆栈；`RATE_LIMITER_UNAVAILABLE`、`SCREENSHOT_ENGINE_UNAVAILABLE`、数据库提交不可用和未知异常仍记录 ERROR。Application 业务额度耗尽虽然返回 429，但属于单租户业务状态，继续记录 INFO；403 以及未来明确表示业务/政策拒绝的 423/451 也不作为系统 ERROR。

限流 Filter 通过请求属性把稳定错误码与 `capacity_protection`、`request_interrupted` 或 `system_failure` 分类交给外层一次性日志 Filter；MVC 异常由 Advice 使用同一分类语义直接记录。未知异常仍由最外层记录堆栈并原样重新抛出，未分类 5xx 默认 ERROR，没有增加通用捕获或降级分支。请求日志只使用 MVC 路由模板；在 HandlerMapping 前返回时退化为 `/api/*` 或 `/v1/*`，不再记录含资源 ID 的原始 URI。

本次没有增加 Counter：`http.server.requests` 继续负责状态与耗时聚合，`nexus.ratelimit.admission` 已按稳定 outcome 区分限流结果，waiters/states 与截图 unfinished/busy Gauge 继续回答当前饱和状态。日志只补充单次事件原因和真实故障堆栈，不替代这些指标。

聚焦日志回归 77/77 通过后，2026-10-27 使用显式 `local`、专用 `nexus_test_user`/`nexus_test`、Hikari 2/1、一次性 JWT、离线 Maven 缓存和既有 Chromium 执行完整 `mvn clean test`。Flyway 校验 V1–V9，最终 **452/452** 通过，0 失败、0 错误、0 跳过；测试 JVM和 Chromium 无残留。最小处置顺序见 [可观测性运行手册](OBSERVABILITY_RUNBOOK.md)。至此 OBS-1 的指标、受控实验、证据驱动修复、日志分级和运行说明形成当前闭环。

## 暂不纳入第一批

- health 不伪装为 Gauge；它属于后续管理端点设计。
- 不添加与 `http.server.requests` 或其他 Timer count 重复的通用请求、截图完成 Counter。
- 不预先增加截图状态维度、每 Application 指标或每 API Key 指标。
- 不预先决定 Prometheus/Grafana、外部告警、抓取周期、直方图区间和告警阈值。
- 不把普通 Maven 测试变成持续压力发生器。

## 下一步边界

OBS-1 第一批自定义埋点、框架指标基线、单/多 Application 限流实验、全局 waiter 容量实验和[异步截图背压与资源边界实验](SCREENSHOT_BACKPRESSURE_EXPERIMENT.md)均已完成。截图校准得到 `μ_gate=0.321463 job/s`、建模 `μ_worker≈0.243263 job/s`；四档 `0.5/1/2/4μ_worker` 的 201 为 4/8/15/16、503 为 0/0/0/14、unfinished 峰值为 2/2/9/10。所有接纳任务成功，Quota/Usage/HTTP 和双出口一致，无 429、Hikari pending/timeout、Usage failure 或客户端失真；Java/Chromium Working Set 约 366–517/178–218 MiB，最高 system/process CPU 约 56.6%/9.2%，各档均完全排空和清理。容量 10 的背压在已测约 0.973 RPS、30 秒窗口下保持机器资源有界并恢复，但最大队列等待达到 38.981 秒，不能外推更长或更高负载。14 个预期 503 均被通用 Handler 记为 ERROR，日志级别是否调整是独立决定。

2026-10-26 已完成 Application state 容量/回收实验的确定性预检。新增测试直接使用生产 Spring 配置验证前 1000 个不同 Application state 创建成功、第 1001 个得到 `STATE_CAPACITY_REACHED`；另以 32 个线程并发提交 1001 个不同 Application，验证最终恰好 1000 个成功、1 个容量拒绝、state 不越界且 waiters 为 0；清理生命周期测试明确验证首次和后续 fixed delay 均为 5 秒。连同既有 TTL、等待者保护、Filter 503 映射和拒绝不扣 Quota 合同，聚焦回归 **94/94** 通过（Limiter 59、Filter 29、HTTP chain 6）。本轮没有启动 Nexus、连接 MySQL、创建实验 Application 或发送压力请求，因此不能把该结果表述为数据库池、Tomcat 或本机已承受 150 RPS。

随后经单独批准完成三轮真实实验。1001 个 Application/Key 在测量前顺序创建；被测量的是既有 Key 调用本机 UUID，不是以 150/s 创建 Application。每轮 999 个 control 请求由 10 个 Worker 以聚合 150 RPS 发出，随后第 1000 个成功、第 1001 个稳定返回容量 503，回收后第 1001 个重试成功。三轮均为 1001 个 200、1 个 503、0 个 429；states/waiters 峰值为 1000/0，Hikari active/pending 为 5/0，Tomcat busy/max 分别为 3/200、5/200、4/200，control p95 为 23.52/21.87/18.33 ms。停止后首批 states 在容量拒绝后 11.434–12.586 秒清零，单个 recovery state 在完成后 14.891–15.014 秒清零，符合 TTL 10 秒加 fixed-delay 扫描的可观察边界，但不是严格 15 秒合同。

三轮共 3003 个成功请求，数据库精确收敛为 3003 条 UUID Usage 和 3003 次 Quota 消耗，三个容量拒绝均不扣费；Actuator 与每轮六个 Prometheus 文本阶段快照一致。最低可用物理内存 13.27 GiB，Heap 峰值 107.63 MiB/512 MiB，最高 system/process CPU 41.96%/16.14%，未触发安全停止。测量数据写完后发现 metrics Worker 的消息端口未关闭，导致包装器未自然退出；这是测量后的工具生命周期缺陷，独立 DB 检查已先确认数据完整。精确终止该 Node 后，包装器删除 3003 Usage、1001 Quota periods/Keys/Applications 和 1 User并反查为零，专用 JVM与端口无残留。脚本已修复并以监听型 Worker 预检确认退出。完整设计、有效性边界和结果见 [Application 限流 state 容量与回收实验](APPLICATION_RATE_STATE_CAPACITY_EXPERIMENT.md)。

参考：

- [Spring Boot Metrics](https://docs.spring.io/spring-boot/reference/actuator/metrics.html)
- [Spring Boot Monitoring and Management over HTTP](https://docs.spring.io/spring-boot/reference/actuator/monitoring.html)
