# 实验11 / RL-4：同一Application跨接口共享速率桶

日期：2026-10-14。隔离Java实验通过。固定一个认证后的Application和同一到达时刻，只改变机器接口；对比“Application + API Code”分桶与仅按Application分桶。不使用真实凭证、网络、线程、Spring或数据库。

## 当前规则

本轮产品规则是所有认证成功的`/v1/*`机器接口共享Application速率，包括：

- UUID和Hash；
- 机器短链创建、查询、更新和删除；
- 网页正文提取；
- 静态网页截图。

JWT管理接口`/api/*`和公开短链跳转`/s/*`不属于这个机器接口限流范围。认证失败的请求尚未进入Application身份边界，后续HTTP实验应验证它们不会创建或等待Application桶。

## 速率限制与Quota不同

Quota回答：

```text
一个720小时周期内还能消费多少次？
```

Application速率限制回答：

```text
这个Application现在发请求的节奏是否过快？
```

当前Quota规则只对UUID、Hash、短链创建、提取和截图扣1次；短链查询、更新和删除不扣Quota。但这些不扣Quota的请求仍会消耗CPU、数据库连接和线程，也可能被机器人高频轮询，所以仍进入`/v1/*`共享速率桶。

未来如果证据显示截图等接口需要更严格保护，可以在Application总桶之外叠加端点并发或端点速率限制。不能用多个端点桶替代Application总桶，否则换接口可以获得多份额度。截图当前已有独立并发槽，它和请求开始速率仍是不同约束。

## 固定请求

八个请求都属于Application `9001`，并在虚拟`10000ms`同时到达：

| API Code | 方法与路径 | 是否扣Quota |
| --- | --- | --- |
| `utils.uuid` | `GET /v1/utils/uuid` | 是 |
| `shortlink.list` | `GET /v1/short-links` | 否 |
| `web.screenshot` | `POST /v1/web/screenshot` | 是 |
| `shortlink.update` | `PUT /v1/short-links` | 否 |
| `utils.hash` | `POST /v1/utils/hash` | 是 |
| `shortlink.delete` | `DELETE /v1/short-links/77` | 否 |
| `web.extract` | `POST /v1/web/extract` | 是 |
| `shortlink.create` | `POST /v1/short-links` | 是 |

这里的Quota标记只用于证明策略差异，实验没有实际调用Quota服务。

## 对照A：错误地按Application和接口分别建桶

桶键为：

```text
applicationId + apiCode
```

实际创建8个桶，八个请求都安排在`10000ms`，等待均为0。调用者只要轮换接口，就能在同一时刻获得多份立即时隙。

## 对照B：只按认证后的Application建桶

桶键始终为`9001`：

| 顺序 | API Code | 安排时刻 | 等待 |
| ---: | --- | ---: | ---: |
| 1 | `utils.uuid` | 10000ms | 0ms |
| 2 | `shortlink.list` | 10100ms | 100ms |
| 3 | `web.screenshot` | 10200ms | 200ms |
| 4 | `shortlink.update` | 10300ms | 300ms |
| 5 | `utils.hash` | 10400ms | 400ms |
| 6 | `shortlink.delete` | 10500ms | 500ms |
| 7 | `web.extract` | 10600ms | 600ms |
| 8 | `shortlink.create` | 10700ms | 700ms |

最终只创建一个桶。接口和Quota策略都没有产生额外速率额度。

## 本轮边界

本实验仍是顺序调用和虚拟时间，只验证跨接口共享身份。它没有接入Controller或Filter，没有真正等待，也没有实现并发安全、10个等待上限、1秒期限、429、取消处理或Application状态回收，不能直接作为生产实现。

## 运行

从`D:/Nexus`执行：

```powershell
& .\tools\experiments\rl-4\run.ps1
```

最终输出：

```text
RL-4 PASS: all sampled /v1 operations shared one Application schedule.
```
