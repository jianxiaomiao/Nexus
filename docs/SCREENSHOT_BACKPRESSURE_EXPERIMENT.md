# 异步截图背压与资源边界实验

## 当前状态

2026-10-25 已完成代码、测试和现有文档审计，并共同审定本实验设计。确定性离线数据源、数据库身份/清理守卫、采样客户端和进程启动器已经实现并通过编译或语法检查。

2026-10-26 单任务确定性预检通过。最终有效轮次只提交 1 个任务：Job `SUCCEEDED`，执行 6383 ms，PNG 693726 bytes；Usage 精确为 1、Quota `usedCount=1`。7 个 OS 样本观察到 unfinished/busy 峰值 1/1、实验 Chromium 峰值 4 个进程、Java/Chromium Working Set 峰值约 356/184 MiB，整机最小 available 约 15.60 GiB，没有触发安全停止。任务完成后 unfinished、busy、限流 waiters/states 和 Hikari pending 都为 0；Actuator 与 Prometheus 对 HTTP 201、队列等待和异步执行成功的增量一致，服务日志 0 ERROR/0 WARN。守卫物理删除并反查 1 个 Job、1 条 Usage、1 个 Quota period、1 个 Key/Application/User，18180/18181 与受控 Java/Chromium 无残留。证据位于忽略目录 `nexus-server/target/screenshot-backpressure-runtime/20261026-090922-047/`。

预检先暴露并修复了三个仅属于实验工具的问题：HTML 中 CSS `%` 被 `String.formatted` 误解析；PATH 首位 Oracle `javapath` 跳板导致启动器 PID 不等于真实 JVM；`powershell.exe` 为 Windows PowerShell 5.1，不支持 `??`。现改为固定字符串替换、从 `JAVA_HOME` 锁定真实 Java 21，以及 5.1 兼容的空值处理；清理同时保留原始失败并严格复核精确 JVM PID。

同日已单独审定三任务校准口径并更新工具，但尚未执行：同一新 JVM 内严格串行 3 个任务，前一个 `SUCCEEDED` 后才提交下一个；主估计量改为 `nexus.screenshot.execution{mode=async,outcome=succeeded}` 的 `ΔCOUNT / ΔTOTAL_TIME`，即唯一 Gate 的平均服务率，不再用数据库执行时长中位数代替容量。三个 `started_at → completed_at` 时长只用于稳定性检查，要求总体变异系数不超过 25% 且 `max/min ≤ 1.5`。校准同时要求 HTTP 201、queue delay、execution success、Usage 和 Quota 都精确为 3，failed/429/503/Hikari timeout/pending/Usage failure 为 0，unfinished/busy 峰值为 1/1，两种指标出口一致，并沿用预检的 OS 安全停止条件。校准只输出 `μ` 与 `0.5μ/1μ/2μ/4μ` 候选，不自动进入负载阶段。

2026-10-26 首次三任务校准按上述闸门执行并判为无效，未产生正式 `μ`。三个任务均完成，HTTP/queue delay/async success、Usage 和 Quota 均精确为 3，Gate 累计占用 12.4523358 秒、平均 4.1507786 秒，观察速率约 0.2409 job/s，最大单次 Gate 占用 6.1946317 秒；双出口、OS 安全、Hikari、Gauge 和清理判据均已先通过。唯一失败项是持久化执行时长 CV=34.96%，超过 25% 上限，说明首个冷任务与后续任务不能可靠地压缩成一个三样本服务率。工具在失败后物理删除并反查 3 个 Job、3 条 Usage、1 个 Quota period、1 个 Key/Application/User，18180/18181 和 Java/Chromium 无残留。原始证据位于忽略目录 `nexus-server/target/screenshot-backpressure-runtime/20261026-104753-576/`。稳定性失败时完整校准对象此前未写入 result.json，这一证据保存缺陷已修复为先保存 `valid=false`、失败原因、三次时长、OS 样本和计算结果再返回失败；没有修改阈值，也没有自动重跑。

同日重新审定并执行了“两阶段校准”：全新 JVM 中先执行 1 个冷启动诊断任务，再在不重启、不等待限流 state 回收的条件下严格串行执行 3 个稳态任务。冷任务持久化/Gate 时间为 6062 ms/6.0724159 s；三个稳态持久化时长为 3060、3094、3155 ms，CV=1.27%、max/min=1.031，均通过稳定性闸门。稳态 Gate 累计 9.3323401 s、平均 3.1107800 s，因此截图槽位占用率 `μ_gate=0.3214628 job/s`；冷 Gate 是稳态平均的 1.952 倍。18 个 OS 样本覆盖 cold 6 个、warm 12 个，unfinished/busy 峰值 1/1、Chromium 峰值 4 个进程、Java/Chromium Working Set 峰值约 357/217 MiB，system/process CPU 峰值约 42.2%/5.4%，没有安全失败。四个 Job、HTTP、queue delay、execution success、Usage 和 Quota 精确对账为 4，双出口一致，日志 0 ERROR/0 WARN。守卫删除并反查 4 个 Job、4 条 Usage、1 个 Quota period、Key/Application/User；18180/18181、Java、Chromium 无残留。证据位于忽略目录 `nexus-server/target/screenshot-backpressure-runtime/20261026-112242-148/`。

静态复核随后确认，`μ_gate` 不能直接作为异步队列的排空率：`ScreenshotJobScheduler` 使用 `scheduleWithFixedDelay(..., 0, 1, SECONDS)`，每次 Worker 调用完成后还固定等待 1 秒。基于当前实现的排空周期模型为 `3.1107800 + 1 = 4.1107800 s`，建模队列排空率为 `μ_worker≈0.2432628 job/s`。该值是由已测 Gate 平均值和代码中的固定延迟组合出的模型值，不冒充新的实测吞吐。容量 10 统计 `QUEUED + RUNNING`，因此满载时是 1 个运行、最多 9 个排队，而不是 1+10。

30 秒负载窗口仍为半开区间 `[0, 30s)`：第一请求位于 `t=0`，第 `k` 个计划时刻为 `t_k=k/λ`，只要 `t_k<30s` 就发送，因此有限窗口请求数为 `ceil(λ×30)`。到达率档位改以 `μ_worker` 为基准：`0.5μ_worker/1μ_worker/2μ_worker/4μ_worker` 约为 0.1216314、0.2432628、0.4865257、0.9730513 RPS，对应 4、8、15、30 个计划请求。`N/30s` 与目标 `λ` 的小差异是有限窗口在 `t=0` 含一个到达事件造成的边缘效应，实际到达强度仍由相邻请求间隔 `1/λ` 定义。脚本会保存 Gate 口径、Worker 模型、容量模型和计划请求数；尚未执行负载。

2026-10-26 已单独执行 `0.5μ_worker` control。30 秒内按 8.22156 秒间隔计划并发送 4 个请求，4 个均返回 201，0 个 503/429，最大在途 1、最大发送迟到 25.9 ms；Quota 与 Usage 均精确为 4，所有接受任务最终 `SUCCEEDED`，Actuator 与 Prometheus 静止边界一致。unfinished 实测峰值为 2 而不是预测的约 1，但仍远低于容量 10，control 的背压禁止判据通过。4 次成功 Gate 总计 17.8587 秒，MAX 为 8.6649 秒；这次冷执行明显高于校准冷 Gate 6.0724 秒，说明原预测低估了冷启动波动。Java/Chromium Working Set 峰值约 369/178 MiB，Chromium 峰值 4 个进程，system/process CPU 峰值约 25.5%/4.9%，Hikari pending/timeout 和 Usage failure 都为 0，日志 0 WARN/ERROR。队列、执行槽、限流状态最终回到 0；4 个 Job、4 条 Usage、Quota/Key/Application/User 已物理删除并反查，18180/18181、Java 和 Chromium 无残留。证据位于忽略目录 `nexus-server/target/screenshot-backpressure-runtime/20261026-191117-835/`。

本档通过回答了“低到达率下系统可承受且可恢复”，但精确峰值预测被实测修正。预测只作为待验证假设，不再直接决定实验是否有效。背压断言改为三态：`forbidden` 只用于 `0.5μ_worker` control；`observe` 用于 `1μ_worker`、`2μ_worker`，无论是否触顶都保存为有效观察；`required` 用于 `4μ_worker`，必须真正触及容量并出现 503 才回答了背压问题。

同日随后按新断言完成 `1μ_worker → 2μ_worker → 4μ_worker`，三档均通过安全、有效性、双出口、业务对账、恢复和清理判据：

| 档位 | 计划请求 | 201 / 503 / 429 | unfinished 峰值 | queue delay MAX | Gate MAX | Java / Chromium WS 峰值 | system / process CPU 峰值 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `0.5μ_worker` | 4 | 4 / 0 / 0 | 2 | 2.080 s | 8.665 s | 369 / 178 MiB | 25.5% / 4.9% |
| `1μ_worker` | 8 | 8 / 0 / 0 | 2 | 4.154 s | 6.566 s | 517 / 217 MiB | 56.6% / 5.3% |
| `2μ_worker` | 15 | 15 / 0 / 0 | 9 | 31.601 s | 5.944 s | 384 / 218 MiB | 19.2% / 5.8% |
| `4μ_worker` | 30 | 16 / 14 / 0 | 10 | 38.981 s | 6.633 s | 366 / 217 MiB | 52.3% / 9.2% |

结果与修正后的解释一致：`1μ_worker` 只有小幅积压；`2μ_worker` 形成高积压但未触顶；`4μ_worker` 精确触及容量 10，约接纳 16/拒绝 14 的预测也命中。所有 43 个被接纳任务最终 `SUCCEEDED`；每档 Quota 等于 201，Usage 等于全部尝试，503 均带 `Retry-After: 5`。四档最大客户端在途都为 1，最大发送迟到不超过 31.6 ms；Hikari pending/timeout、Usage failure 和 Application 429 始终为 0。每档停止输入后 unfinished/busy、限流 waiters/states 均回到 0，Actuator 与 Prometheus 静止边界一致。

资源没有随档位单调增长：Chromium 始终最多 4 个进程、Working Set 约 178–218 MiB；Java Working Set 峰值约 366–517 MiB；最高瞬时 system/process CPU 为 56.6%/9.2%，未接近停止线。说明这台机器在已测最高约 0.973 RPS、30 秒窗口下能够依靠容量 10 的背压保持资源有界并最终恢复，但不能把这个结论外推为更高速率、更长持续时间或多 JVM 的容量结论。代价是已接纳任务的最大队列等待从 2.080 秒升至 38.981 秒。

`4μ_worker` 的 14 个预期 503 各产生一条 `GlobalExceptionHandler` ERROR；控制台和文件重复记录使扫描总数为 28，除这些容量拒绝外没有其他 ERROR/WARN。这不是本轮隐藏故障，但表明当前“所有 5xx 都记 ERROR”的日志策略会把正常背压 shedding 计入错误日志；是否调整日志级别应作为独立可观测性决定，不在本实验中顺手修改。

后续于 2026-10-27 单独完成日志分级：`SCREENSHOT_JOB_QUEUE_FULL` 现在按明确原因记录无堆栈 WARN，真实截图引擎、数据库、任务创建和结果存储故障仍为 ERROR。上述 14 个 ERROR/28 个扫描命中保留为实验当时的历史事实，不回写为新结果，也未为日志调整重复本压力实验。

三档证据分别位于忽略目录：`nexus-server/target/screenshot-backpressure-runtime/20261026-192247-078/`、`20261026-192424-237/`、`20261026-192627-702/`。每档均物理删除并反查实验 Job、Usage、Quota、Key、Application、User，精确 Java PID、实验 Chromium 和 18180/18181 均无残留。

本轮优先做异步截图背压，不同时改变或测试 Application 限流状态容量。Application state 的 1000 条容量、10 秒空闲 TTL 和 5 秒清理周期留作后续独立实验。

## 1. 问题与预测

实验回答的不是“接口最多能跑多少 RPS”这一孤立数字，而是：在固定机器、固定应用配置和固定截图工作量下，当异步截图提交到达率从低于 Worker 队列排空率逐步升到高于排空率时，本机 Java、Chromium、数据库连接和 HTTP 线程的占用是否有界，系统能否通过全局 10 个未完成任务（1 个运行加最多 9 个排队）的边界快速拒绝过量流量，并在停止提交后恢复为空闲状态。

预测：

- `nexus.screenshot.jobs.unfinished` 永不超过 10，`nexus.screenshot.slot.busy` 只能为 0 或 1；
- 低于 `μ_worker` 的 control 不应触顶，任务全部完成；
- 明显高于 `μ_worker` 的 treatment 应逐步形成积压；是否在有限 30 秒窗口内触及 10 由实际调度相位和执行波动决定，触顶后的提交应快速返回预期 `503` 和 `Retry-After: 5`，而不是继续堆积、超时或拖垮进程；
- 获得 `201` 的任务最终全部 `SUCCEEDED`，队列排空后 unfinished/busy、Hikari pending、限流 waiters/state 和 Usage 异步写入都收敛；
- Java 与属于本实验固定浏览器目录的 `chrome-headless-shell` 进程数、RSS、Private Bytes 和 CPU 不随累计请求数无界增长；
- Actuator 与 Prometheus 文本出口对 HTTP 201/503、截图执行次数和队列等待计数给出一致增量。

`503 SCREENSHOT_JOB_QUEUE_FULL` 是背压生效的预期结果，不单独视为系统失败。具体拒绝数不是硬性通过条件：它会受执行时间波动、Scheduler 相位和有限窗口边界影响。预测与断言分离：`1μ_worker`、`2μ_worker` 即使推翻预测也属于有效观察；只有 `4μ_worker` 使用 `required`，要求观察到容量 10 和至少一个 503，否则 treatment 没有真正触达待验证边界。

## 2. 为什么现在需要

正确性测试已经覆盖容量拒绝、任务状态机、租约恢复、文件发布和清理，但它们不能回答真实 Chromium 子进程占用、队列在连续到达下是否保持有界、拒绝是否足够快、停止输入后多久排空。OBS-1 的 HTTP、Hikari、Tomcat、JVM、Usage 和截图四项指标已经可用，且此前限流实验已证明双出口和清理守卫可工作；现在具备先观察再决定优化的前提。

## 3. 单一自变量、control 与 treatment

唯一自变量是同一机器 API Key 对 `POST /v1/web/screenshot-jobs` 的均匀开环到达率 `λ`。

固定项包括：同一构建产物、全新 JVM、同一 `nexus_test`、Hikari 4/1、同一 Application/Key、同一离线 HTML、同一 2000 ms fetch 延迟、同一 Chromium、同一 30 秒阶段、最大 16 个客户端在途请求和 3 秒提交超时。每个阶段使用全新 JVM 和全新实验数据，避免上一档的堆积、JIT/指标累计值或数据库记录成为干扰。

- 校准：全新 JVM 先完成 1 个冷启动诊断任务，再在同一 JVM 严格串行完成 3 个稳态任务；冷任务单独报告。稳态 async succeeded execution Timer 的 `ΔCOUNT / ΔTOTAL_TIME` 得到 `μ_gate`，三个稳态持久化执行时长只检查离散度；队列档位使用 `μ_worker = 1 / (Gate 平均时间 + Worker 固定延迟)`。校准不是普通 Maven 测试，也需要单独批准。
- control：`0.5μ_worker`。
- treatment：`1μ_worker`、`2μ_worker`、`4μ_worker`，所有档位上限为 5 RPS，保证 Application 10 RPS 限流不是先到的瓶颈。

任何需要比较的阶段都只改变 `λ`。不在同一阶段改变线程池、Hikari、队列容量、HTML、Chromium 参数或持续时间。

## 4. 请求模型与递增方式

- 请求：固定离线目标的异步截图提交；提交窗口内不轮询任务状态，避免状态查询成为额外流量；
- 模型：uniform open-loop，窗口为 `[0,T)`，第一请求在 `t=0`，后续计划时刻为 `k/λ<T`，因此数量为 `ceil(λT)`；按计划墙钟发起，不因前一请求变慢而闭环降速，也不补发错过的节拍；
- 并发保护：客户端最大 16 个在途请求；达到上限、调度严重迟到或请求超时均判实验无效/失败，不用失真的样本得结论；
- 持续时间：正式阶段 30 秒，工具仅允许 10–60 秒；
- 顺序：确定性预检 → 1 个冷启动诊断 + 3 个稳态串行校准 → `0.5μ_worker` → `1μ_worker` → `2μ_worker` → `4μ_worker`；每档新 JVM、新数据，先从低到高执行，任一停止条件触发即不进入更高档；
- 阶段结束后才对已接受 job 各查询一次最终状态，并等待 Usage、队列和执行槽收敛。

四档初始预测以已测冷 Gate 6.0724 秒、稳态 Gate 平均 3.11078 秒、Worker fixed delay 1 秒、首次认领轮询相位和 unfinished 容量 10 建模。`0.5μ_worker` 实测冷 Gate MAX 8.6649 秒证明冷启动存在额外波动，因此峰值和拒绝数只用于解释，不是精确通过条件。

| 档位 | 间隔 / 计划数 | 初始预测 | 背压断言 | 原因 |
| --- | --- | --- | --- | --- |
| `0.5μ_worker` | 8.2216 s / 4 | 峰值约 1，0 个 503；实测峰值 2、0 个 503 | `forbidden` | 健康 control 必须证明低到达率不会触发背压 |
| `1μ_worker` | 4.1108 s / 8 | 峰值约 2，0 个 503 | `observe` | 稳态到达间隔约等于 Worker 排空周期；预测偏差本身就是结果 |
| `2μ_worker` | 2.0554 s / 15 | 峰值约 9，0 个 503 | `observe` | 较慢冷启动可能使它触及 10；允许观察触顶或不触顶，不用预测判实验无效 |
| `4μ_worker` | 1.0277 s / 30 | 峰值 10，约 16 个接纳、约 14 个 503 | `required` | 到达量足以验证容量和快速拒绝；若不触发背压则 treatment 强度不足 |

`1/2μ_worker` 的触顶和 503 只修正模型，不单独构成实验失败；仍须满足容量不超过 10、503 与容量状态一致、201 全部成功、业务与指标对账、资源安全和最终恢复。`4μ_worker` 若没有同时观察到 unfinished 峰值 10 和至少一个 503，则 treatment 未触达背压边界。其“约 14 个 503”不作精确断言，实际值允许随任务完成时刻变化。

## 5. 观察指标

| 维度 | 指标或证据 | 目的 |
| --- | --- | --- |
| HTTP/客户端 | 201、预期 503、429、其他状态、提交 p50/p95/p99、客户端超时/调度迟到/最大在途 | 区分正常接纳、快速背压、Application 限流和客户端先饱和 |
| 截图 | `nexus.screenshot.jobs.unfinished`、`nexus.screenshot.slot.busy`、`nexus.screenshot.queue.delay`、`nexus.screenshot.execution{mode=async}` | 核对容量 10、单槽、排队和真实执行时间 |
| 限流 | `nexus.ratelimit.admission`、waiters、states | 证明 5 RPS 以下未被 Application 限流污染 |
| Hikari | active、idle、pending、max、acquire、timeout | 判断数据库池是否先饱和 |
| Tomcat | threads busy/current/config.max、connections current | 判断提交 HTTP 层是否先饱和 |
| JVM | heap used/max、live/peak threads、process/system CPU、GC pause/overhead、磁盘可用空间 | 观察 JVM 与宿主资源边界 |
| Usage/Quota | `nexus.usage.record.failures`、实验 Usage 状态数、Application quota used | 201 应扣一次并记 201；容量 503 不扣 Quota但应记 503 |
| OS 进程树 | Java PID 与实验浏览器根目录下 Chromium 的进程数、RSS、Private Bytes、CPU；整机总/可用内存 | Actuator 只覆盖 Java，必须补足 Chromium 和整机占用 |

## 6. 干扰变量与隔离

- 不访问 CSDN、知乎或其他外部网站。实验启动类只存在于 `target/test-classes`，用固定内存 HTML 替换资源获取器；生产 `ScreenshotRenderer`、Playwright/Chromium、共享 Gate、Scheduler、数据库、Quota、Usage 和 HTTP 链均保持真实且不修改生产安全策略。
- 固定 HTML、fetch 延迟、viewport、脚本禁用策略和浏览器版本，避免站点/DNS/公网波动。
- 业务端口 18180、管理端口 18181 都只绑定 `127.0.0.1`；发压前要求端口为空，管理端点不经过 Nginx。
- 数据库守卫要求 `DATABASE()=nexus_test` 且 `CURRENT_USER()` 为 `nexus_test_user@...`；否则在写入前停止。
- 每档新 JVM、新随机 User/Application/Key、新结果目录；只采样属于精确 Java PID和固定 Playwright 浏览器目录的进程。
- 提交窗口不轮询 Job；Actuator 高频小查询与有限 Prometheus 边界快照分开，避免全量抓取本身成为主要负载。
- 电源模式、机器前台任务和其他大型构建应在各档保持不变；若系统 CPU 已高或可用物理内存不足，阶段作废而不是归因给 Nexus。

## 7. 停止条件与失败判定

观察到下列任一项立即停止当前档，并且不升档：

- 可用物理内存低于 `max(1 GiB, 总内存的 15%)`；
- `unfinished > 10`，或 `slot.busy` 出现 0/1 以外的值；
- system CPU 连续 10 个样本不低于 95%；
- Java 退出、健康检查失败、管理出口不可读、客户端在途达到保护上限、提交超时或调度失真；
- 出现非预期 HTTP 状态、429、Hikari pending/timeout、Usage failure、任务 FAILED/EXPIRED、接受任务无法排空；
- Actuator 与 Prometheus 的静止边界累计值不一致，或客户端、HTTP、Usage、Quota 无法对账；
- cleanup 反查仍发现实验行、实验进程或监听端口。

`forbidden` 要求 unfinished 峰值小于 10 且没有 503；`observe` 不对是否触顶作先验断言；`required` 要求峰值等于 10 且至少一个 503。达到这些功能判据只说明实验 treatment 有效和背压边界正确，机器是否“承受得住”还要结合 Java+Chromium 峰值/稳态资源和阶段后恢复判断。

## 8. Actuator 与 Prometheus 两种出口

Actuator JSON 用于按频率采样当前 Gauge 和指定标签的 Timer/Counter；业务 `/actuator/**` 必须 404，只有回环管理端口可访问。Prometheus 文本不需要安装 Prometheus：脚本只在阶段边界和中点抓取 `/actuator/prometheus` 原文保存，再与 Actuator 的静止前后快照比较累计增量。全量抓取次数受限，因为 unfinished Gauge 每次读取都查询 MySQL。

GC 是惰性事件指标；阶段中没有自然 GC 时允许不存在，不以人为 `System.gc()` 制造样本。操作系统采样是第三类本机证据，不替代两种 Micrometer 出口，专门补充 Chromium 子进程和物理内存。

## 9. 数据、进程和端口清理

每次运行的随机邮箱固定使用 `obs-screenshot-...@example.test`，Application/Key 名称也使用实验前缀。finally 清理按精确身份和外键顺序删除 screenshot jobs、结果文件、短链、Usage、Quota、Key、Application、User，并用守卫反查为零；清理器拒绝不符合实验前缀的数据。

进程只停止启动器记录的精确 Java PID，以及启动后、命令行匹配固定浏览器根目录的新增 `chrome-headless-shell`；不按进程名批量结束其他浏览器。结果目录必须规范化后位于本轮 runtime root 内才允许递归删除。最后确认 18180/18181 不再监听并恢复全部临时环境变量。证据目录保留 manifest、哈希、边界快照、Prometheus 原文、OS 样本、客户端汇总和服务日志；秘密、完整 API Key、JWT 与数据库密码不写入证据。

## 执行闸门

入口脚本为 `nexus-server/scripts/run-controlled-screenshot-backpressure-experiment.ps1`：

- 默认 `Preflight` 只执行一个端到端任务，但仍会启动本地 Nexus 与 Chromium、写入并清理 `nexus_test`；
- `Calibrate` 会执行 1 个冷启动诊断和 3 个稳态串行任务，必须显式提供 `-ApprovedCalibration`；
- `Load` 必须显式提供 `-ApprovedLoad`，Node 层还会检查第二道批准标记，速率超过 5 RPS 或时长不在 10–60 秒会拒绝；
- 任何真实执行都必须在本设计和确定性预检分别审定后进行。普通 Maven 回归不作为负载发生器。
