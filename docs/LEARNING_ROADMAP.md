# Nexus 下一阶段学习路线

本清单按仓库当前实现编排。最初的《Nexus Developer API Platform 开发计划》提供功能地图，但其中的版本、接口路径、认证请求头和技术顺序是早期设想；实现与测试是当前事实来源。

## 当前检查点

- [x] 注册、登录和 JWT 签发；管理接口使用 Bearer JWT。
- [x] Application 创建、列表、更新、软删除及归属校验。
- [x] API Key 创建、列表、更新、软删除与原地凭据轮换；完整密钥仅创建或轮换成功时返回。
- [x] `/v1/*` 的 API Key Filter、机器身份传递和 `GET /v1/utils/uuid`。
- [x] 认证器、管理流程及真实 HTTP 测试；2026-10-10 后端完整测试在 `nexus_test` 上为 189/189（使用新的临时测试 JWT 密钥），前端 Playwright 为 60/60，并完成轮换真实浏览器验收。
- [ ] 开发者能独立解释 Filter、认证器、请求属性、Controller 以及 401/403 的边界。

机器认证沿 Key → Application → User 检查当前状态。管理接口在 JWT 验签后也查询 User，因此账号禁用和删除会影响已签发凭证。具体规则见 [账号状态策略](ACCOUNT_STATUS_POLICY.md)。

## A. 先完成认证与账号生命周期决策

**要解决的问题：** 账号被禁用或软删除后，已有 JWT 和其 Application 下的 Key 是否立刻失效？规则已确定并实现。

- [x] 写出状态表：账号正常、禁用、删除时，已有 JWT 和 API Key 各返回什么；说明 401/403 的理由。
- [x] 选择立即失效：每次请求读取 User；无效或已删除身份优先于禁用状态。
- [x] 修改两条认证边界，加入状态转换与真实 HTTP 测试。
- [ ] 开发者能不用代码解释上述状态表、查询代价和失败顺序。

**学习检查：** 能解释“认证、授权、账号状态”各在哪一步判断，以及为什么某种状态返回 401 或 403。

## B. 第二个 Open API：Hash

**要解决的问题：** UUID 没有请求体，尚未练到机器接口的输入边界与参数验证。旧计划下一项是 `POST /v1/utils/hash`。

- [x] 契约已定：`SHA256` / `SHA512`、UTF-8 最多 4096 字节、小写十六进制结果和统一错误响应。
- [x] 真实 HTTP 测试覆盖两种算法、空输入、缺失字段、非法算法与输入字节边界；测试由 Agent 编写，开发者仍需能解释这些场景对应的规则。
- [x] DTO、校验、Hash 逻辑与 HTTP 接口已实现；不记录原始输入或完整密钥。
- [x] `docs/OPEN_API.md` 已补请求、响应、长度和错误示例；完整测试 145/145 通过。

**学习检查：** 能从 HTTP 请求追踪到 Bean Validation、业务计算和响应，并解释输入限制的目的。

## C. Vue Developer Console 最小闭环

**要解决的问题：** 后端已有管理接口和两个机器 Open API，但开发者还没有网页入口来完成 Application 与 API Key 的日常管理。

- [x] 在同一仓库创建 `nexus-console/`，使用 Vue、TypeScript、Router、Linter 和 Prettier 脚手架。
- [x] 前端依赖已安装、锁文件已生成；开发服务器可启动，`npm run build` 通过。
- [x] 实现登录、Application 列表与创建、API Key 列表与创建；创建时仅展示一次完整 Key。另有开发文档和 UUID/Hash 调试台。
- [x] 真实后端已验证登录、Application/Key 创建和 UUID/Hash 调用；新 Key 的 UUID 调用、禁用后 403 与重新启用由 Usage 验收脚本再次确认。

2026-10-06：开发者确认 UUID 与 Hash 的真实调用成功。2026-10-08：新 Key 的创建、调用、禁用拒绝、重新启用和 Usage 页面联调通过。前端模拟浏览器回归测试随后通过 44/44。

**学习检查：** 能解释浏览器请求如何到达管理 API，JWT 与 API Key 分别由谁持有，以及创建响应为何不能长期保存在浏览器。

## D. 让测试可重复运行

**要解决的问题：** 当前完整测试依赖本地 MySQL 和测试 JWT 密钥；换一台机器后运行条件需要明确。

- [x] 记录最短的本地启动与测试步骤，明确专用测试数据库和临时密钥从哪里来；见 [本地可重复测试](LOCAL_TESTING.md)。Usage 页已用新 Key 完成真实后端验收。
- [x] 给后端测试增加启动前数据源保护：当前 `config/application-local.yml` 的旧 `nexus` URL 是注释，生效值为 `nexus_test`。测试上下文在建连前拒绝错误库名；真实服务验收仍须核对最终连接。轮换真实验收脚本与步骤已记录。
- [ ] 在需要团队或远程持续验证时，增加最小 CI：启动 MySQL、注入测试密钥、运行 Maven 测试；不上传真实凭证。

**学习检查：** 能说明单元测试、Spring 集成测试和真实 HTTP 测试分别证明了什么。

## E. 第一个有持久化业务数据的 Open API：短链接

短链接后端与 Vue 控制台的最小闭环已实现：Key 创建短链、公开地址跳转、归属检查、禁用/删除/到期规则与短码冲突处理。下一步用真实后端验收完整流程。

- [x] 明确 Key 归属、JWT 管理、公开跳转、状态、过期、短码冲突和目标 URL 主机白名单；HTTP 契约见 `docs/OPEN_API.md`。
- [x] 先定数据模型与 V5 Flyway migration，再实现 Entity/Mapper/Service/Controller。
- [x] 测试覆盖跨 Key/用户越权、短码冲突、过期、状态转换和公开跳转；后端完整测试 154/154 通过。
- [x] Vue 调试台接入机器端四种短链操作，控制台按 Key 管理短链；开发文档新增短链接 API 参考。模拟浏览器测试 39/39 通过。
- [ ] 用真实前后端逐项记录短链创建、复制、公开跳转、禁用/恢复、删除和跨 Key 隔离的结果。开发者已反馈完成测试，具体场景覆盖尚未记录。

当前权限决策：一枚有效 API Key 可使用全部机器接口，不引入 Scope；短链仍按创建它的 Key 校验资源归属。若未来出现按接口分权的真实需求，再重新设计凭证权限。

**学习检查：** 能解释机器身份如何绑定资源，以及为什么客户端传入的 ID 不能单独证明资源归属。[OWASP 的对象级授权说明](https://api-security.owasp.org/editions/2023/en/0xa1-broken-object-level-authorization/)可作复习材料。

## F. Application 30 天配额

- [x] 规则：Application 创建时刻起每 720 小时一期，默认上限 1000、旗下全部 Key 共用；校验失败不扣，实际准入后失败不退。
- [x] V8、Entity/Mapper、独立同步计数和归属查询 Service；Usage 继续独立。
- [x] UUID、Hash、短链创建、网页正文提取和截图接入；短链其他操作、管理调用与公开跳转不扣。短链创建改为先消费再由 TransactionTemplate 启动写入事务。
- [x] 2026-10-13 后端全套 275/275 在核实的 nexus_test 和专用账号上通过，含并发最后一次、周期整点及真实 HTTP/MySQL 消费边界；真实 Chromium 受控资源回归也通过。
- [x] 2026-10-14 JWT 管理查询 HTTP 入口复用原 Service，定向48/48通过；列表与详情两张视觉提案完成，见 `docs/design/NEXUS_QUOTA_UI_PROPOSAL_V1.md`。
- [x] 分页列表嵌套配额概要与按本页应用批量读取已实现，不同创建锚点、保序、无记录不创建和错误传播验证通过；相关后端定向86/86。
- [x] 两页按视觉稿接入：列表用嵌套quota，详情可单独刷新；构建/E2E类型检查通过，模拟响应Playwright全套82/82，覆盖北京时间、零上限、查询错误与窄屏。
- [x] 2026-10-14 开发者反馈真实配额页面测试通过；未提供逐项记录或数据库环境，不扩展为Agent新验收证据。
- [x] 配额教程与429排查已接入 `/docs/quota`；同步快速开始、认证及五篇接口参考。构建/E2E类型检查通过，文档与滚动定向9/9，含正文链接保留会话与手机阅读。
- [ ] 开发者能解释周期锚点、字段快照、条件更新、Application 行锁、Quota 提交与业务事务的顺序，以及失败为何仍计次。

具体决定与验证边界见 [配额设计](QUOTA_DESIGN.md)。当前本地配置已回到日常 nexus；测试通过进程环境覆盖为专用测试库与账号，不改日常设置。

## G. 持久化异步截图任务（当前检查点完成）

**要解决的问题：** 同步截图最长占用连接和唯一 Chromium 执行槽约 20 秒；客户端必须持续等待，且进程重启时没有可恢复的任务事实。异步化先只服务截图，不抽象为万能任务平台。

- [x] 2026-10-22 耗时证据：本地 `nexus` 与 `nexus_test` 的 9 条既有 `web.screenshot` Usage 为 4709–11639 ms；受控实验耗尽生产 20 秒预算后，Renderer 完成超时翻译和浏览器清理共 20231 ms。实验测试文件已删除，不进入日常套件。
- [x] 第一版规则：全局未完成任务最多 10 个；结果从成功时起保留 1 小时；完成后的任务元数据保留 24 小时。状态为 `QUEUED → RUNNING → SUCCEEDED → EXPIRED` 或 `RUNNING → FAILED`；任务恢复不重复扣配额。
- [x] V9 创建 `screenshot_jobs`，保存 API Key 归属、目标、状态、执行次数、运行令牌/租约、结果元数据、失败码和 UTC 时间，并为 FIFO 领取、过期租约、结果过期及元数据清理建立索引。`ScreenshotJob` Entity 与仅继承 `BaseMapper` 的 `ScreenshotJobMapper` 已加入。
- [x] 专用 `nexus_test` 从 V8 真实迁移到 V9；Flyway 校验 9 个迁移，聚焦 Spring 上下文测试通过。未运行完整回归，V9 尚未部署到 ECS。
- [x] Service 与 Manager 完成：配额与 `QUEUED` 插入同事务，FIFO 原子认领使用 30 秒租约和 runToken 围栏，最多两次尝试；Renderer 在事务外执行，ResultStore 原子发布 PNG，定时任务恢复租约并按 1 小时/24 小时清理。
- [x] Mapper 的 11 个业务 SQL 和 Service/并发/崩溃恢复测试完成；真实 MySQL 场景验证租约重排、尝试耗尽、旧 token 围栏、文件与元数据清理顺序、配额与任务插入共同回滚。
- [x] 提交、状态、结果三个 HTTP 接口完成，同步截图保留作为对照路径。2026-10-23 专用 `nexus_test` 全量后端回归 414/414 通过；同步/异步前端、2 秒串行轮询和当前 Job 展示已实现，完整模拟浏览器回归 88/88。提交 `9a9d10c` 已推送，开发者报告一次真实前端异步任务成功；V9 尚未部署 ECS。

**学习检查：** 能区分数据库中的任务状态、Worker 的运行时单并发和租约恢复分别解决什么问题，并能说明进程在接纳、认领、渲染、文件发布及完成更新之间崩溃时如何收敛。

## H. 最小可观测性与受控压力实验（当前检查点完成）

**要解决的问题：** 现有日志和 Usage 能说明离散事件与业务调用，但不能连续回答服务是否变慢、错误是否上升、资源是否饱和、限流或截图任务是否积压。没有这些基线，压力测试只能看到结果，难以定位瓶颈。

- [x] 2026-10-23 已审定[“问题 → 指标 → 指标类型 → 验证方法”表](OBSERVABILITY_BASELINE.md)，明确 Timer、Counter、Gauge 的边界、第一批指标、标签约束和验证方法。
- [x] 2026-10-24 已实现最小 Actuator/Micrometer 指标出口；管理端点使用独立回环监听，只暴露 health/metrics/prometheus，不经 Nginx 或公网防火墙规则。
- [x] 限流 admission/waiters/states、截图 queue-delay/execution/unfinished/busy，以及 Usage 最终记录失败已完成；固定标签不包含 API Key、JWT、jobId、用户/Application ID、原始 URL 或动态异常文本。确定性边界、Gauge 生命周期和 Actuator/Prometheus 双出口均已验证。
- [x] 框架 HTTP、Hikari、JVM、进程、GC 和磁盘指标已通过三个受控实验验收：Filter 401/429 各计一次且使用 `uri=UNKNOWN`，Hikari 2 连接池验证 active/idle/pending/acquire 状态迁移，运行时指标单位与范围合理；GC Binder 启动即存在，但事件序列允许在未自然 GC 时尚未创建。完整后端回归 431/431 通过。
- [x] 2026-10-24 已完成单 Application 限流受控负载实验：UUID 以均匀 5/10/12/20 RPS 各运行 15 秒，真实观察 admission/waiters/states、HTTP、Hikari、JVM、GC 与 Usage；吞吐在约 9.8 RPS 形成平台，队列和状态边界正确，数据库/JVM/Usage/客户端均未先饱和，Actuator 与 Prometheus 结果一致，隔离数据已精确清理。
- [x] 已接受“100 ms 最小间隔、实际吞吐略低于 10 RPS”的保守上限语义，不做 9 RPS 复验或算法修改。
- [x] 2026-10-25 多 Application 隔离实验通过：固定 A=20 RPS，以 B 单独 1/5/10 RPS control 对比 A+B treatment；B 成功率均保持 100%，B=10 treatment p95 为 control 的 102.2%，waiters 峰值 10/10/18、states 精确为 2，Hikari pending 与 Usage failure 均为 0。两种出口一致，677 条 Usage及全部隔离数据已精确清理。
- [x] 2026-10-25 全局 waiter 容量实验完成：首次实验以确定性测试证明第 101 个等待者得到 `global_queue_full`，并在 2/2 个有效轮次触顶；失速轮次暴露的客户端共享事件循环、Quota/Usage 反向锁序和异常 Usage 200 均被拆分修复。全新环境复验中 `A + 9B` 负对照为 0，`A + 10B` 三轮分别为 32/35/41，客户端、HTTP/admission、Quota/Usage、Hikari/Tomcat/JVM 和双出口均通过判据，完成新 3/3 稳定性验收。
- [x] 2026-10-25 Quota/Usage 独立锁序实验确认并修复死锁根因：实验观察到 Quota 的 Application X → Key X 与 Usage 外键隐式 Key S → Application S 构成闭环，MySQL 返回 1213/40001 且原始报告已保存；修复后 Usage 在独立短事务中显式按 Application S → Key S → INSERT 执行。确定性顺序测试、真实 MySQL 阻塞关系测试和完整后端 434/434 通过；临时观察权限已撤销。全事务/外键审计未发现第二条结构性反向锁序。
- [x] 2026-10-25 Usage 最终 HTTP 状态语义已修复并验证：正常请求 200、异步截图提交 201、已处理异常 400/429/503、响应未提交时的未处理异常 500；Usage INSERT 失败不改变客户端 200，只使 `nexus.usage.record.failures{reason="write_failed"}` 增加 1 且不留下事件。定向回归 17/17、完整后端回归 438/438 通过。
- [x] 2026-10-25 全局 waiter 客户端已拆为 A、B1–B10 和 metrics 共 12 个独立 Node Worker；统一测量开始墙钟，独立事件循环/Agent/停止判定，协调进程等待全部摘要后再判失败。无网络预检验证 12 个不同线程，50/100ms 节拍最大迟到 16ms；真实容量复验中零客户端中止，最大发送迟到 41.33ms，低于最短 50ms 间隔。
- [x] 2026-10-25 已审定并于 2026-10-26 完成[异步截图背压与资源边界实验](SCREENSHOT_BACKPRESSURE_EXPERIMENT.md)。校准得到 `μ_gate=0.321463 job/s`、建模 `μ_worker≈0.243263 job/s`；背压断言使用 `forbidden/observe/required`，预测偏差只用于解释。`0.5/1/2/4μ_worker` 四档分别得到 4/8/15/16 个 201 和 0/0/0/14 个 503，unfinished 峰值 2/2/9/10；全部 43 个接受任务成功，Hikari pending/timeout、Usage failure、429 为 0，双出口和业务数据精确对账。Java/Chromium Working Set 峰值范围约 366–517/178–218 MiB，最高 system/process CPU 约 56.6%/9.2%，各档均完全恢复并清理。容量背压有效且本机资源在已测约 0.973 RPS、30 秒窗口下有界；最大队列等待升至 38.981 秒。
- [x] 2026-10-27 完成预期容量拒绝日志分级：主动容量保护与请求中断为无堆栈 WARN，业务/安全/政策拒绝为 INFO，真实基础设施故障、未分类 5xx 和未知异常保持 ERROR 与一次堆栈；日志使用稳定 code/category 和路由模板，不记录凭证或请求级高基数字段。聚焦回归 77/77、完整后端 452/452 通过，处置顺序见[最小运行手册](OBSERVABILITY_RUNBOOK.md)。
- [x] 已依据吞吐、p95、错误、CPU/内存、连接池和队列证据修复 Quota/Usage 锁序、Usage 最终状态和日志噪音；当前容量保护与保守限流语义可接受，不再做无问题驱动的优化，也不引入 Redis、MQ 或完整 Prometheus/Grafana 栈。

**学习检查：** 能从一个运行问题推导指标及类型，解释采样与累计、低/高基数标签、管理端点安全边界，并区分功能正确性测试、限流验收和容量压力测试。

## 后续按具体问题引入

2026-10-14 阶段讨论：当前无管理员业务，不增加RBAC；所有有效Key可调用全部机器API的产品规则维持，暂不加Scope；现有Key Usage与Application Quota满足当前统计查看需要，不另建Dashboard。Redis缓存按瓶颈证据评估，MQ还需按消息持久化/恢复需求评估，不以“全部服务完成”为引入条件。Nginx入口限流可作为部署时的保护；它与按认证后Key/Application的业务限流分开设计。

下一阶段建议（尚未开始实施）：配额文档已完成，进入原计划M19/M20的最小可重复部署：Vue静态产物、Nginx路径转发、Spring Boot JAR/MySQL及环境配置；Docker/Compose只包含实际需要的组件。截图运行依赖与网络隔离需纳入部署验证；之后再考虑M23 CI。暂不为推进里程碑新增异步任务、Webhook或新API。

- 静态网页截图：2026-10-11 已按“脚本关闭、同步 PNG、不保存历史”实现后端和前端。Java Playwright 渲染安全 HTTP/DNS 获取后 fulfill 的资源；次数上限后返回部分 PNG，其他大小/超时仍失败。初始后端干净构建229/229，后续定向42/42；真实CSDN PowerShell部分PNG已验收。调试台第五个入口、第八篇教程、预览/放大/下载、错误JSON和临时URL释放已完成；构建/类型检查和模拟响应Playwright75/75通过。下一步核实测试库后验收真实调试台，再收尾。学习检查：能解释谁建立连接、截图视口与页面加载范围、PNG与JSON分支，以及 Semaphore 与临时URL的释放时机。

- 网页正文提取：后端已实现机器接口、URL/DNS 策略、受限抓取、正文识别与 HTML 净化；前端教程和调试台已接入。开发者关闭 VPN 并重启后端后，确认真实文本提取成功。下一步补一项带图片文章的真实调试台检查，然后收尾提交。学习重点是区分服务端 SSRF 与浏览器 XSS，追踪“URL 校验 → 连接时 DNS 验证 → 获取 HTML → 识别正文 → 净化 → 安全展示”。固定样本测试不能证明真实目标站一定允许抓取。

- Usage：异步持久化、事件 UUID 去重和 200/400 入库已验证。Key 用量查询已实现归属校验、北京时间日界、每日/API-code 聚合与禁用后历史查看；管理端 GET 使用查询参数，真实 JWT/HTTP/MySQL 测试通过。API Key 详情页现有密钥信息、短链接、调用统计三个标签；完整后端测试 165/165、前端构建和 44 个模拟浏览器测试通过。2026-10-08 已用新 Key 完成真实浏览器与后端联调。
- Redis：短链查询有实际延迟或吞吐瓶颈并测量后再加缓存；限流需求明确后再设计限流器。
- RabbitMQ、异步截图、Webhook、监控、部署：各自需要明确业务问题、失败规则与验证办法。公开部署前需提供 HTTPS；API Key 不应通过明文传输。[OWASP REST 安全建议](https://cheatsheetseries.owasp.org/cheatsheets/REST_Security_Cheat_Sheet.html)

每完成一个学习单元，检查两件事：行为及相关测试是否正确；开发者是否能说明调用链、失败路径与当前选择的代价。只更新当前完成项与最近下一项，不把整份功能地图当作必须按顺序堆技术的工期表。
