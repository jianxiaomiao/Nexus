# Application 限流 state 容量与回收实验

日期：2026-10-26
状态：确定性预检与三轮本地真实实验已完成

本实验遵循：

```text
预测 → 单变量变化 → 执行 → Actuator/Prometheus 两种出口观察 → 解释
```

它不安装 Prometheus/Grafana，不开放公网管理端口，不访问 ECS 或外部网站，也不修改 ECS。普通 Maven 回归只验证确定性合同，不充当负载发生器。

## 1. 实验问题、预测与机器判据

问题不是“150 次/s 创建 Application”。1001 个 Application 和 Key 在测量前由管理 API 顺序创建；被测工作负载是 1001 个既有 Application 各自通过 Key 调用一次本机 `GET /v1/utils/uuid`，其中前 999 个请求以聚合约 150 RPS 发出。

实验假设如下，假设不能预先当成断言：

- 999 个不同 Application 应形成 999 个 resident states，且没有容量拒绝；
- 第 1000 个应成功并把 state 填到 1000；
- 第 1001 个在回收前应返回 503 `RATE_LIMIT_STATE_CAPACITY_REACHED`；
- 停止输入后，空闲 state 应在 TTL 与后续清理扫描共同决定的窗口内回到 0；此前被拒绝的第 1001 个 Application 随后应成功；
- 150 RPS 不等于 150 个并发连接。并发近似为 `到达率 × 请求停留时间`，因此预计 Hikari 10 和 Tomcat 200 不会仅因“150”这个数字被打满，但必须实测。

“本机在本实验窗口能承受”的判据是：三轮客户端计划、发送、响应和数据库账目精确；无客户端超时或安全停止；state 从不超过 1000；Hikari pending/timeout 为 0；Tomcat busy 不连续 5 秒达到其上限的 90%；系统 CPU 不连续 5 个 1 秒样本达到 95%；可用物理内存始终不少于 `max(1 GiB, 物理内存 15%)`；停止后 state 能回到 0。该结论只覆盖本机、该请求和持续时间，不能外推为长期容量或生产 SLA。

## 2. 为什么现在需要实验

确定性测试能证明容量判断、并发原子性、TTL 条件和 HTTP 映射，但不能回答真实 HTTP、API Key 认证、Quota、Usage、MySQL、Hikari、Tomcat、JVM 与 Windows 调度共同作用时是否先出现其他瓶颈。此前单/多 Application 和全局 waiter 实验也没有触达 1000 个 resident states，因此无法验证生产容量和真实回收窗口。

## 3. 单一自变量、control 与 treatment

单一自变量是“当前 resident 的不同 Application state 数量”：

- control：999 个不同 Application，各请求一次，预期 `states=999`；
- treatment 边界 1：第 1000 个不同 Application 请求，预期成功且 `states=1000`；
- treatment 边界 2：回收前第 1001 个不同 Application 请求，预期容量 503；
- recovery：停止输入直到 `states=0`，再重试第 1001 个，预期 200，随后再次观察到 0。

请求 API、JVM、数据库、连接池、客户端、采样频率和轮次内步骤保持不变。三个轮次复用同一批 Application/Key，但每轮开始前都要求 state 已清零。

## 4. 数量、模型、速率、并发、持续时间与递增

- 资源：1 个实验 User、1001 个 Application、每个 Application 1 把 Key；设置阶段不计入负载测量。
- 请求：本机 `GET /v1/utils/uuid`，每个 control Application 每轮只发 1 次；无外部网络依赖。
- control 速率：10 个 sender Worker，每个 15 RPS，聚合 150 RPS，均匀间隔约 66.67 ms；999 个请求约 6.66 秒完成。
- 并发：每个 Worker 最多 8 个在途，全客户端硬上限 80；实测各轮在途上界合计均为 10。
- 递增：999 → 1000 → 1001，不用大跨度阶梯掩盖精确边界。
- 轮次：3 个有效轮次；实测从 control 开始到第二次回收为 34.397、33.351、33.321 秒。整个结果文件从创建资源、预检到三轮完成约 127.5 秒。
- 客户端单请求超时：3 秒；任何计划/发送/响应不一致或调度迟到超过一个发送周期都使轮次无效。

## 5. 观察指标

| 层次 | 指标/证据 | 用途 |
|---|---|---|
| HTTP | 200/429/503、错误码、p50/p95/p99、客户端超时 | 区分正常成功、容量拒绝和其他失败 |
| 限流 | `nexus.ratelimit.admission` 各 outcome、`states`、`waiters` | 验证容量、无排队和回收 |
| Hikari | active/idle/pending/max/acquire/timeout | 判断数据库连接池是否先饱和 |
| Tomcat | busy/current/config.max、connections.current | 判断 Servlet 线程或连接是否先饱和 |
| JVM | heap used/max、live threads、process CPU | 观察 JVM 资源边界 |
| Usage/Quota | failure counter、HTTP 状态的 Usage 行、每 Application 周期计数 | 验证异步记录最终收敛及拒绝不扣费 |
| OS | system CPU、可用/总物理内存、Java Working Set/Private Bytes | 执行安全停止与机器结论 |

`states/waiters` 每 100 ms 采样，Hikari/Tomcat 每 500 ms，JVM/OS 每 1 秒。瞬时峰值短于采样周期时可能被漏采，所以这些结果是已观测峰值，不是连续时间数学上界。

## 6. 生产代码事实与可观察边界

- `ApplicationRateLimiterConfig` 把最大 state 数设为 1000；
- `ApplicationRateLimiter` 把空闲 TTL 固定为 10 秒；
- 清理任务首次延迟 5 秒，并使用 5 秒 fixed delay；
- state 只有在没有等待者、没有待执行调度且超过空闲 TTL 时才有资格回收；清理还要取得该 state 的锁，竞争时会留到后续扫描；
- 因此“最后活动后恰好第 10 秒删除”不是代码合同。通常可观察上界接近 10 秒 TTL 加下一次扫描，但 fixed delay、锁竞争、扫描耗时和 100 ms 采样都会推迟观察。

实测 control 批次最后一个 state 到 `states=0` 用了 11.434–12.586 秒；recovery 的单个 state 用了 14.891–15.014 秒。前者与 999 个请求跨约 6.7 秒产生、清理相位不同有关。这证明了当前窗口的回收，而不是承诺所有机器都精确在 15 秒内清零。

## 7. 干扰变量与隔离

- 所有 Application/Key 预先顺序创建，创建吞吐不混入被测 150 RPS；
- 使用全新本地 JVM、固定 `-Xms128m -Xmx512m`、Hikari 10/2、业务/管理端口 18180/18181 且仅绑定 loopback；
- 只使用专用 `nexus_test`/`nexus_test_user`，Guard 在运行前核对 schema 和账号；
- 每轮必须从 `states=0` 开始，轮间等待 Usage/Quota 收敛，不让上一轮 state 污染下一轮；
- 认证、Quota 和 Usage 属于真实请求链，不能删除；通过 Hikari、Usage failure 和精确数据库对账判断它们是否成为竞争瓶颈；
- 管理端指标采样本身是固定低强度干扰，control/treatment 使用相同频率；
- 三轮在同一 JVM 中执行以避免重启差异，同时把 JIT/缓存热身视为轮次差异的一部分；
- 结果清单只持久化资源 ID 和名称，完整 API Key 仅驻留于客户端进程内存。

## 8. 停止条件、实验失败与产品不达预期

安全停止条件包括 state 超过 1000、物理内存低于安全余量、系统 CPU 连续 5 秒不低于 95%、Tomcat busy 连续 5 秒达到 max 的 90%，以及客户端超时/调度失真。

实验失败表示工具或环境使数据不能解释，例如：预检失败、端口/进程不隔离、客户端少发/漏收、两出口不一致、数据库未收敛、采样中断。产品表现不达预期则是实验有效但行为错误，例如：第 1001 个不是预期 503、state 超过 1000、空闲后不回收、容量拒绝扣了 Quota、Hikari/Tomcat 先饱和。

本次三轮测量均有效且产品表现符合预期。测量结果写完后，metrics Worker 因保留 `parentPort` 消息监听而未退出，导致外层包装器没有自然结束。这是“测量完成后的实验编排缺陷”，不是产品负载失败：`result.json` 已有 `completedAt` 和三轮完整数据，18 份 Prometheus 阶段快照已落盘，数据库独立检查为 1001/1001/3003/3003。随后只终止该实验 Node PID，包装器按 `finally` 精确清理并反查为零。脚本已在发送最终结果后关闭消息端口，并新增监听型 Worker 退出预检；修复后 12 个 Worker 均正常退出。

## 9. Actuator 与 Prometheus 文本出口

Actuator 使用 `/actuator/metrics/{name}` 并通过稳定标签筛选读取当前值或 Counter/Timer；Prometheus 使用 `/actuator/prometheus` 保存每个阶段的全文快照。每轮保存 before、control-999、treatment-1001、first-zero、recovery-zero、after 六个文本快照，并断言关键 HTTP、admission、Hikari timeout 和 Usage failure 的两出口增量一致。这里只读取文本出口，没有运行 Prometheus 服务。

## 10. 数据、进程与端口清理

数据库 Guard 只接受 `nexus_test`、`nexus_test_user` 和 `obs-state-`/`obs-state-app-`/`obs-state-key-` 前缀。它先按实验邮箱定位唯一 User，再按外键顺序删除 screenshot jobs、short links、Usage、Quota periods、API Keys、Applications 和 User，最后反查邮箱不存在。包装器只停止自己启动且身份匹配的 JVM，并等待 18180/18181 释放；不会按进程名清理其他 Java/Node。

本次清理结果：

```text
DB_CLEANUP_OK screenshotJobs=0 shortLinks=0 usageEvents=3003
quotaPeriods=1001 apiKeys=1001 applications=1001 users=1
DB_VERIFY_CLEAN_OK emailAbsent=true
```

实验 JVM PID 40548、Node PID 3820 和端口 18180/18181 均无残留；VS Code 自己的 Java PID 24152 未被触碰。原始证据保留在被 Git 忽略的：

```text
nexus-server/target/application-state-capacity-runtime/20261026-210440-257/
  evidence/obs-state-capacity-2026-10-26T13-05-01-842Z-559bc45d/
```

## 结果

| 轮次 | control 200 | 1000th | 1001st | 回收后重试 | p95 / p99 | states/waiters 峰值 | Hikari active/pending | Tomcat busy/max |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 999 | 200 | 503 | 200 | 23.52 / 32.56 ms | 1000 / 0 | 5 / 0 | 3 / 200 |
| 2 | 999 | 200 | 503 | 200 | 21.87 / 32.14 ms | 1000 / 0 | 5 / 0 | 5 / 200 |
| 3 | 999 | 200 | 503 | 200 | 18.33 / 21.84 ms | 1000 / 0 | 5 / 0 | 4 / 200 |

每轮 admission 都精确为 `allowed_immediate=1001`、`state_capacity_reached=1`，其他六种 outcome 为 0；HTTP 每轮为 1001 个 200、1 个 503、0 个 429。三轮总共 3003 个成功请求，数据库最终为 3003 条 `uuid.generate` Usage 和 3003 次 Quota 消耗，容量拒绝均未扣费。

JVM/OS 的三轮已观测峰值范围：process CPU 2.74%–16.14%，system CPU 26.14%–41.96%，Heap 105.38–107.63 MiB / 512 MiB，live threads 49，Java Working Set 412.38–421.74 MiB，Private Bytes 476.79–489.21 MiB；最低可用物理内存 13.27 GiB。没有触发安全停止。

结论：在这台本机、该短请求、每轮约 6.7 秒的 150 RPS control 和最多 10 个实测在途下，Hikari 未被打满（5/10、pending=0），Tomcat 也远未打满（最高 5/200）。state 容量 1000、容量 503 和停止后的回收均按当前产品合同工作。这个结果不能证明“以 150 RPS 创建 Application”或任意业务端点也安全；请求若让连接平均占用达到约 `10 / 150 = 66.7 ms`，Hikari 10 就可能接近满载，慢 SQL、事务等待和不同 API 必须另测。
