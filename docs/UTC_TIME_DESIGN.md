# Nexus 时间约定调整

## 问题和决定

Application 的默认创建时间来自数据库，而配额将它按北京时间解释；其他模块已有显式 UTC 时间，部署环境的默认时区因此可能改变业务含义。

已选择：绝对时刻的内部存储和计算统一为 UTC，API 明确返回时区，前端按北京时间展示。Usage 自然日统计继续按北京时间划分；Application 配额继续以创建时刻为锚点，每周期严格为 720 小时。

相比统一使用北京时间，UTC 更贴合现有截图、短链和 Webhook 的绝对到期时间；代价是必须一起审查持久化、DTO 和前端契约，不能只改数据库时区。

## 当前代码检查点：Application 写入

- `ApplicationService` 注入已有 `Clock`，每次写操作显式转换为 UTC，并截断为毫秒，与 `DATETIME(3)` 对齐。
- 创建时只读取一次时钟，显式设置相同的 `created_at`、`updated_at`；更新不改创建时间。
- Application 软删除及其 Key、短链级联删除共用同一个 UTC 时刻，并显式写入更新时间。
- 实体仍使用 `LocalDateTime` 与现有列对应；在这条写入链路中值的约定是 UTC，不代表类型本身带有时区。

2026-11-08：`ApplicationServiceImplTests` 在 JVM 默认 UTC 和 Asia/Shanghai 下分别 18/18 通过。固定时钟使用带纳秒的同一时刻，并覆盖不同 Clock 时区、创建只读一次时钟、更新不改锚点和级联删除时间一致性。该组测试不启动 Spring 或连接数据库；它证明传给 Mapper 的时间值，不代表完成真实 MySQL 或 HTTP 验收。

## 配额 UTC 锚点检查点

`ApplicationQuotaService` 将创建时间按 UTC 转成 Instant，以固定 720 小时 Duration 计算当前周期，再以 UTC 写入/查询 `period_start`。单次查询、分页批量查询和扣费准入共用同一函数；结束时刻属于新周期，创建锚点不变。无调用的周期仍不补写记录。

2026-11-08：先将测试锚点改为 UTC，旧算法在 19 个单元场景中失败 5 个，重现提前 8 小时换期等问题。修正后，配额 19 个与 Application 18 个单元场景在 JVM 默认 UTC、America/Los_Angeles 下分别 37/37 通过。独立临时 MySQL 8.4 使用 UTC 会话，配额集成测试 16/16 通过，其中周期结束前 1 毫秒、恰好结束和后 1 毫秒的三次扣费分别落入旧/新/新周期；原创建锚点未改变。该临时库已移除，未使用现有 Compose 数据。没有重复完整后端回归。

## Application 与配额响应检查点

Application 列表、更新响应的创建/更新时间，以及列表嵌套配额和独立配额接口的周期起止，DTO 使用 Instant，输出带 Z 的 UTC ISO 字符串。Application 创建响应仍只返回原有 ID 和名称，不添加字段。Service 在 DTO 边界将数据库 UTC LocalDateTime 显式转为 Instant；数据库列和实体类型不变。

前端继续按 Asia/Shanghai 展示。Application/配额模拟响应已使用带 Z 的值，验证跨日显示、不同设备时区，以及等价的 +08:00 响应。无偏移北京时间兼容仅供尚未调整的其他接口，不再是 Application/配额新契约。

2026-11-08：单元和 MVC 测试 61/61；独立临时 UTC MySQL 的 Application/配额相关集成测试 48/48，新增真实 HTTP 场景对比数据库、列表、独立配额和更新响应的同一时刻。前端构建、类型检查通过，完整模拟浏览器回归 95/95。未重复完整后端测试或更新现有 Compose 镜像。

## 其余模块与运行配置检查点

- API Key 创建、轮换、更新和删除使用已有 Clock，显式 UTC、截断毫秒。Key 与短链级联删除共用一个时刻；创建、列表、更新、轮换响应使用 Instant，输出 Z。
- 注册显式设置相同 UTC 创建/更新时间。邮箱验证与用户删除业务尚未新增；JWT 原有 Instant 签发逻辑保留。
- Usage 请求时刻与查询“今天”注入 Clock。事件明细返回 UTC Z；统计范围继续 +08:00，timeZone=Asia/Shanghai，每日标签及无偏移自定义查询输入仍按北京业务日期解释。耗时继续使用单调时钟。
- 短链、截图及 Webhook 投递已有显式 UTC 的业务逻辑保持。截图 updated_at，以及用户、Webhook 配置、Secret、Outbox 的数据库默认/自动时间，由 UTC 连接会话保障。Webhook 租约继续使用数据库 UTC_TIMESTAMP(6)，没有改为各 JVM 自行判定，也没有改变现有精度和重试规则。
- Key、短链、截图任务页面使用统一北京时间展示；短链自定义到期输入保留清晰标注的设备本地时区，提交时转 UTC。北京展示与输入来源不能混为一谈。无偏移字符串的旧版本兼容仍保留，当前新响应不得依赖它。
- Compose MySQL 默认 +00:00，JDBC 使用 connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true，JVM 显式 UTC。CI MySQL/JVM 默认 UTC，并增加洛杉矶 JVM 聚焦检查。日志带 Z，按 UTC 日期滚动。生产配置示例及本地测试说明已同步；被忽略的本机 local 配置仅替换旧时区连接参数，没有输出秘密。
- 后端没有 Webhook 管理 Controller/Service；前端页面及模拟契约不等于真实管理接口验收。本轮未补接口，现有投递链路的测试和功能不受此范围限制。

## 2026-11-08 验证证据

1. 聚焦单元/MVC 39/39，在 JVM 默认 America/Los_Angeles 下验证注册、Key 与 Usage。新增 CI 同范围扩展至 Application/配额的本地等价执行为 67/67；尚未推送或执行 GitHub Actions。
2. 隔离 MySQL 8.4 故意设置全局 +08:00，JVM 为洛杉矶，真实持久化/HTTP 18/18。应用 Hikari 与独立 Flyway 数据源实际连接均为 UTC；固定时钟写入、Key 轮换/删除级联，以及用户、截图、Webhook 配置/Secret/Outbox 的默认和自动时间均通过。
3. 完整后端 mvn -o test：638/638，0 失败、错误、跳过，包括既有真实 Chromium、短链/截图到期及 Webhook 租约、重试、JVM 崩溃恢复用例。首次完整执行仅旧日志滚动格式断言失败，改为验证 UTC 格式后完整重跑通过；没有降低业务断言。本轮没有把该命令称为 clean test。
4. 前端生产构建、E2E 类型检查及完整模拟浏览器 103/103 通过。新增三个设备时区跨日展示，以及三种设备时区的自定义短链到期输入检查。新增页面测试首次用整页跳转丢失内存登录状态，改为实际页面导航后通过，未改变登录持久化行为。
5. 已测试代码打包成功；独立 Compose 项目 nexus-utc-acceptance 用专用 18191/18192 和全新卷启动。真实前端代理下注册、登录、Application/Key、UUID 成功，Application/配额/Key 响应带 Z；MySQL 全局与会话 +00:00，JVM UTC，Flyway V1–V13。UID 10001、可写字体缓存/结果目录、用户命名空间及启用沙箱的 Chromium 空白页通过，无字体缓存权限错误。本次没有重做容器对外网页截图验收。

证据保存于忽略目录 data/utc-acceptance/，最终完整后端 XML 位于 full-final-reports/；full-final.log、timezone-final.log、frontend-full.log、compose-verification.log 分别对应上述结果。临时 MySQL 使用专用 13385 与内存目录。测试结束仅删除本轮测试容器、网络、两个新 Compose 卷和临时秘密文件；13385/18191/18192/4173 无监听。本轮新构建的两个 acceptance 镜像保留供检查，未覆盖原 Compose 镜像标签。

## 升级前环境与当时边界（历史）

现有 Compose 的三个容器 ID 保持不变，旧镜像和历史数据没有自动升级、转换或清理。本轮没有修改 ECS、重写 Flyway V1–V13、提交或推送。

程序和配置已达到隔离验证检查点；现有实验数据尚待独立处理。下一步先确认全部受影响数据及保留要求，再决定按明确范围清理或转换，之后才能把新 JAR/dist 镜像与新配置一起应用到现有环境。不能只改服务器时区、盲目给历史 DATETIME 加减八小时，或把旧配额计数直接当成新周期数据。


## 现有 Compose 实验环境升级（2026-11-08）

开发者明确批准删除 `nexus-docker-compose-lab_mysql-data` 与 `nexus-docker-compose-lab_screenshot-data` 后，已精确删除并重新创建；原秘密文件校验内容不变。新 JAR/dist 和镜像已构建，现有 Compose 三服务运行，新 MySQL 已应用 V1–V13。MySQL 全局/客户端会话 UTC，JVM UTC，应用 JDBC 配置为强制 UTC 会话。前端代理注册、登录、创建 Application/Key、UUID 成功，Application/配额/Key 时间带 Z；强制重建 MySQL 与 Nexus 后，登录与原 Key UUID 成功，两个容器 ID 均变化。新建验收账户、应用和 Key 保留在新实验库。

本轮外站异步截图返回 `SCREENSHOT_PAGE_FETCH_FAILED`，该容器已重建导致原容器日志不可再查；随后容器和 Windows 对目标域名均解析到 `198.18.0.91` 特殊用途地址，与此前被现有 DNS 安全策略拒绝的现象一致，但本次未保留具体 DNS_FAILURE 日志，不能断言直接原因。故本轮未验证新环境截图 PNG 与截图卷跨重建；UID、缓存和结果目录可写、用户命名空间以及 Chromium 沙箱空白页通过。此前独立环境 Chromium 沙箱空白页和 Compose 截图持久化是旧验收证据。未修改 ECS、其他项目或提交。

2026-11-08 后续开发者报告：调整网络后异步截图任务 `SUCCEEDED`；重建 Nexus 容器后 ID 改变，同一 PNG 前后 SHA-256 一致。该项为开发者提供的验收结果，截图结果仍按现有规则过期。
