# HTTP-3：经 Nginx 的响应、等待与日志实验

日期：2026-10-14。

## 问题

真实 HTTP 请求经过 Nginx 后，Java 返回的 JSON、PNG、认证失败和限流 429 是否保持正确？最长等待 1 秒时，Nginx 是否会提前把响应变成 504？限流拒绝是否会误扣 Quota？日志是否能观察状态与耗时且不泄漏凭证？

## 隔离边界

- Nginx 只监听 `127.0.0.1:18080`，Java 探针只监听 `127.0.0.1:18081`；日常 80/8080 入口与配置不变。
- 使用固定的实验凭证和内存计数器，不连接 Nexus、Spring、MySQL 或真实 Quota 表。
- 探针刻意使用阻塞等待 HTTP 处理线程，只用于观察代理行为；这不是生产等待机制的决定。
- Nginx `proxy_read_timeout` 为 3 秒，大于 1 秒等待期限，目的是让 Java 自己返回结构化 429。

## 场景与断言

1. 无效凭证经 Nginx 返回 401 JSON，限流器与 Quota 都不调用。
2. 有效 JSON 请求返回 200 和 `application/json`，Quota 计数一次。
3. 有效 PNG 请求返回 200 和 `image/png`，PNG 八字节签名保持完整，Quota 计数一次。
4. 暂停队列释放后，同时发出 12 个同 Application 请求：1 个立即成功，10 个进入等待并在 1 秒后返回等待超时 429，1 个因队列已满立即返回 429。
5. 两类 429 都是 JSON 并带 `Retry-After: 1`；11 个 429 均不调用 Quota 或业务，超时后等待数量回到 0。
6. Nginx 日志共有 15 行：3 个 200、1 个 401、11 个 429；其中 10 个 429 耗时至少约 0.9 秒。下游状态与上游状态一致，日志不包含凭证或 Authorization 字段。

## 运行

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\experiments\http-3\run.ps1
```

脚本会先检查端口与 Nginx 配置，结束时停止两个隔离进程。

## 尚未证明

- 这不是 Nexus 真实 Filter、Quota Service、截图服务或数据库集成测试。
- 阻塞等待会占用处理线程；全局等待量上限无法自动限制线程数，生产实现仍需选择可取消的异步 HTTP 等待方式或明确受控线程池。
- `proxy_read_timeout` 还需结合截图等业务最大执行时间设计，不能只等于 1 秒排队期限。
