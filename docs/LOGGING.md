# Nexus 本地日志

后端沿用 Spring Boot 默认的 SLF4J/Logback，不增加日志框架。配置见 `nexus-server/src/main/resources/application.yml`。

从 `D:\Nexus\nexus-server` 启动时，`../logger` 对应仓库根目录 `D:\Nexus\logger`。Logback 首次打开日志文件时会创建缺失的父目录；该目录已被 Git 忽略。其他工作目录或部署环境应设置 `NEXUS_LOG_DIR` 为绝对目录，不依赖相对路径。

- 当前写入 `nexus.log`，控制台输出保留；归档文件按日期命名为 `nexus.YYYY-MM-DD.N.log.gz`。
- 单个归档文件达到 10 MB 也会滚动；归档保留 30 天，随后由 Logback 自动清理。
- `/api/*`、`/v1/*` 的请求失败由 `RequestFailureLoggingFilter` 收尾；它先于 `/v1/*` 的机器认证 Filter 进入。MVC 已由 `GlobalExceptionHandler` 记录的异常不会再被收尾 Filter 重复打印。
- 异步 Usage 队列拒绝由 `UsageInterceptor` 记录，最终入库失败由 `UsageRecordService` 记录。它们不经过 MVC 异常处理器。
- 自定义日志只记录方法、无查询参数的路径、HTTP 状态、错误码、异常类型、内部 ID 和事件 ID；不要记录完整 API Key、JWT、密码、请求体或原始短链目标 URL。日志不是 `usage_events`，Filter 拒绝仍不写 Usage 事件。

目前的默认路径假定后端在 `nexus-server` 目录启动。若从仓库根目录执行 Maven 或从其他位置运行 JAR，先设置 `NEXUS_LOG_DIR`，否则相对路径不会指向仓库的 `logger` 目录。
