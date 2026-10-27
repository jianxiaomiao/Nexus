# Nexus 本地日志

后端沿用 Spring Boot 默认的 SLF4J/Logback，不增加日志框架。配置见 `nexus-server/src/main/resources/application.yml`。

从 `D:\Nexus\nexus-server` 启动时，`../logger` 对应仓库根目录 `D:\Nexus\logger`。Logback 首次打开日志文件时会创建缺失的父目录；该目录已被 Git 忽略。其他工作目录或部署环境应设置 `NEXUS_LOG_DIR` 为绝对目录，不依赖相对路径。

- 当前写入 `nexus.log`，控制台输出保留；归档文件按日期命名为 `nexus.YYYY-MM-DD.N.log.gz`。
- 单个归档文件达到 10 MB 也会滚动；归档保留 30 天，随后由 Logback 自动清理。
- `/api/*`、`/v1/*` 的请求失败由 `RequestFailureLoggingFilter` 收尾；它先于 `/v1/*` 的机器认证 Filter 进入。MVC 已由 `GlobalExceptionHandler` 记录的异常不会再被收尾 Filter 重复打印。限流 Filter 内部已经记录堆栈的真实异常也设置同一个一次性标记，避免再按最终 500/503 重复记录。
- 异步 Usage 队列拒绝由 `UsageInterceptor` 记录，最终入库失败由 `UsageRecordService` 记录。它们不经过 MVC 异常处理器。
- 请求失败日志只记录方法、路由模板或稳定路径组、HTTP 状态、稳定错误码、失败分类、异常类型、安全诊断枚举/数值及耗时。MVC 使用 `HandlerMapping` 的路由模板；在映射前返回的 Filter 只使用 `/api/*` 或 `/v1/*`，不能退回可能含资源 ID 的原始 URI。
- 不记录完整 API Key、JWT、密码、密码哈希、Authorization/Cookie、请求体、查询参数、SQL、数据库连接串或原始目标 URL；异常消息也不作为结构化字段。容量拒绝日志不加入 User/Application/API Key/job/event ID。后台任务确有排障需要时可记录非敏感 jobId/eventId，但它们不得成为 Micrometer 标签。
- 日志不是 `usage_events`，Filter 拒绝仍不写 Usage 事件。

## 请求失败分级

分级按稳定原因而不是只看 HTTP 状态。未明确分类的 5xx 默认保持 ERROR，新增 5xx 不会自动降级。

| 级别 | 语义 | 当前例子 | 堆栈 |
|---|---|---|---|
| INFO | 服务健康的业务、安全或政策拒绝 | 400/401/403/404/409/410/422；`APPLICATION_QUOTA_EXHAUSTED` 429 | 无 |
| WARN | 已知、主动、可恢复的容量保护或请求生命周期中断 | `APPLICATION_RATE_QUEUE_FULL`、`APPLICATION_RATE_WAIT_TIMEOUT`、`RATE_LIMIT_GLOBAL_QUEUE_FULL`、`RATE_LIMIT_STATE_CAPACITY_REACHED`、`SCREENSHOT_BUSY`、`SCREENSHOT_JOB_QUEUE_FULL`、`RATE_LIMIT_WAIT_CANCELLED` | 无 |
| ERROR | 真实系统、执行或基础设施故障，以及所有未知异常/未分类 5xx | `RATE_LIMITER_UNAVAILABLE`、`SCREENSHOT_ENGINE_UNAVAILABLE`、数据库提交/任务创建/结果存储失败、502/504、未知异常 | 有 Throwable 时保留一次 |

WARN 表示保护机制正在工作，不等于单次事件就要触发事故告警。是否持续饱和由 `http.server.requests`、`nexus.ratelimit.admission`、限流 Gauge 和截图 Gauge 的趋势判断；日志用于解释具体事件，HTTP 响应用于当前客户端合同，Micrometer 用于低基数聚合、趋势和告警，三者不能互相替代。

目前的默认路径假定后端在 `nexus-server` 目录启动。若从仓库根目录执行 Maven 或从其他位置运行 JAR，先设置 `NEXUS_LOG_DIR`，否则相对路径不会指向仓库的 `logger` 目录。
