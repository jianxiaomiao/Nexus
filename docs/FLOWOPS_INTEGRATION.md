# Nexus 接入 FlowOps

一期从 Nexus Web 或 Electron 的「我的工单」提交。Nexus 提交事务保存申请和 Outbox，后台用同一个事件重试，校验 FlowOps 回执后显示对方工单号。FlowOps 将首次导入的工单放入绑定部门的待领取队列，当前部门员工通过工作台领取。双方分别迁移自己的数据库。

## 本地环境

- Nexus Flyway 增量为 `V16__support_ticket_delivery.sql`。FlowOps Flyway 增量为 `V5__external_ticket_integration.sql`。
- FlowOps 准备两个测试租户、各自的技术支持部门及已启用的 P2 SLA。每条接入绑定配置本租户的启用技术账号。以 `account_kind = 'MACHINE'` 创建技术账号，为既有必填密码字段设置无业务用途的随机 BCrypt 摘要。技术账号拒绝网端登录。
- 在 `ticket_integration` 配置接入 ID、租户、部门、技术操作人和优先级。每条配置的路由保持固定，密钥轮换通过 `ticket_integration_key` 完成。用 32 字节随机值的无填充 Base64url 字符串作为密钥。FlowOps 保存该字符串 UTF-8 字节的 SHA-256 二进制摘要。Nexus 发送 `Authorization: Bearer <keyId>.<secret>`。
- 给测试客服角色授予 `ticket:claim` 以及 `ticket:read:department` 或 `ticket:read`。继续处理所需的权限沿用 FlowOps 原有工单角色。权限更新后客服重新登录。
- Nexus 配置 `NEXUS_SUPPORT_FLOWOPS_INTEGRATION_ID`、`NEXUS_SUPPORT_FLOWOPS_ENDPOINT` 和 `NEXUS_SUPPORT_FLOWOPS_CREDENTIAL`。接入地址是诸如 `http://127.0.0.1:18080/flowOps/integration/v1/tickets` 的完整 URL。HTTP 仅供回环联调，其他地址使用 HTTPS。凭据由进程环境配置。
- Nexus 未启用接入时，新的工单提交返回 503。已保存的工单和历史仍可查询。修复故障后，维护人员可在核对接入行及其原始事件后，将指定 `BLOCKED` 的 Outbox 行改为 `PENDING`、重置 `attempt_count`，将 `next_attempt_at` 改为当前 UTC 时间。保留事件 ID、工单 ID、接入 ID 和载荷。先行核对已投递状态，重放操作仅匹配指定事件与 `BLOCKED` 条件。

## 在 Mac 普通终端跑完整验收

本机需能访问 Docker Desktop，并提供 JDK 21、Node.js/npm 和 Maven。脚本也支持 HBuilderX 自带的 Node.js/npm 和 IntelliJ IDEA 自带的 Maven。FlowOps 默认位于 Nexus 同级的 `flowops` 目录。脚本使用 8080、5173、18080 和 18081 的本机回环地址，MySQL 使用随机回环端口。

```bash
cd /Users/xiaomiao/Nexus
python3 tools/acceptance/run_flowops_nexus_local.py
```

脚本在临时目录按当前源码和依赖锁构建，在单独的 MySQL 8.4 容器中创建两个测试库。FlowOps 的临时测试副本使用内存向量库，工单验收无需启动 Milvus 或 Ollama。顺序验证 FlowOps V5、租户隔离和领取、浏览器工作台、Nexus 正常投递、丢回执后原事件重投，再运行 Worker 租约接管用例。结束后停止本轮进程并删除临时容器及其数据卷，保留临时日志。首轮构建可能下载缺少的 Maven/npm 依赖、MySQL 镜像和 Playwright Chromium。日志路径会显示在终端开头。

## 分步验收

在两个独立的测试库启动两个后端。通过环境变量 `NEXUS_FLOWOPS_TEST_*` 向 `tools/acceptance/nexus_flowops.py` 提供以下值。脚本只访问回环地址，并会在测试库创建工单和历史数据。

| 变量后缀 | 值 |
| --- | --- |
| `NEXUS_URL` | 本机 Nexus 后端根地址 |
| `FLOWOPS_URL` | 本机 FlowOps 后端地址，包含 `/flowOps` |
| `MACHINE_A`、`MACHINE_B` | 绑定不同租户的两把机器凭据，Nexus 投递端也配置 A |
| `STAFF_A1`、`STAFF_A2` | A 租户同一处理部门的两位客服 JWT |
| `STAFF_A_OTHER_DEPARTMENT`、`STAFF_B` | A 租户其他部门和 B 租户的客服 JWT |
| `NEXUS_USER`、`NEXUS_OTHER_USER` | 两个已登录 Nexus 测试用户的 Bearer Token |

先按 FlowOps 仓库的 `docs/17-nexus-ticket-acceptance.md` 跑 V5、两租户 HTTP 验收和工作台。加载 FlowOps 脚本生成的本地环境文件，再启动指向独立测试库的 Nexus。FlowOps 用 8080 时，Nexus 可用不同的本地端口，例如 18080。

运行 `python3 tools/acceptance/nexus_flowops.py --deliver-only`，检查同一提交键返回原 Nexus 工单、其他用户无法查看工单、Nexus 收到回执，以及 FlowOps 只存在一张回执工单号匹配的工单。省略 `--deliver-only` 会同时复跑 FlowOps 的 HTTP 用例。

故障注入时运行 `python3 tools/acceptance/nexus_flowops.py --drop-first-ack 18081`，把 Nexus 的 FlowOps 接入地址改为 `http://127.0.0.1:18081/flowOps/integration/v1/tickets`，重启 Nexus 后再跑 `--deliver-only`。代理会断开首次成功响应，并记录该事件再次收到的回执。通过标准是代理记录同一事件先丢弃 201、再转发 200，同时验收脚本确认 FlowOps 只存在一张匹配工单。

Nexus 侧用两个 Worker 和测试回执验证租约接管。在 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD` 指向回环的 MySQL 8.4 专用 `nexus_test` 后，从 Nexus 根目录运行：

```bash
NEXUS_SUPPORT_LEASE_ACCEPTANCE=true mvn -f nexus-server/pom.xml -Dtest=SupportTicketLeaseIntegrationTest test
```

用例对单个测试事件模拟租约过期，由第二个 Worker 接管。旧回执先返回时，数据库必须保留新 Worker 的认领令牌，只有新回执能标记送达。用例应用缺失的 Flyway 迁移，并清理自己创建的工单和 Outbox 记录。
