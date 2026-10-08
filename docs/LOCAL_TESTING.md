# Nexus 本地可重复测试

以下命令以 Windows PowerShell 为例。后端需要 Java 21、Maven 3.9+ 和本机 MySQL；前端需要 Node.js 24、npm。先在 MySQL 中准备**专用测试库**（例如 `nexus_test`）及只对该库有权限的本地用户。可用 MySQL 管理工具执行以下 SQL，并把占位密码替换成新生成的本机密码：

```sql
CREATE DATABASE nexus_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'nexus_test_user'@'127.0.0.1' IDENTIFIED BY '<本机新密码>';
GRANT ALL PRIVILEGES ON nexus_test.* TO 'nexus_test_user'@'127.0.0.1';
```

不要把测试指向日常开发库或生产库；集成测试会创建、修改和清理业务数据。Flyway 会在 Spring 启动时应用 `nexus-server/src/main/resources/db/migration/` 中的迁移。

## 1. 配置测试库与临时 JWT 密钥

在 `nexus-server/config/application-local.yml` 配置测试库连接。该文件已被 Git 忽略，不提交真实密码；至少需要以下属性：

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/nexus_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8
    username: <本机测试库用户>
    password: <本机测试库密码>
nexus:
  auth:
    jwt:
      issuer: nexus
      access-token-ttl: 30m
      secret: ${NEXUS_JWT_SECRET}
```

在运行 Maven 的同一个 PowerShell 窗口生成本次运行专用的 JWT 密钥，勿把输出写进仓库：

```powershell
Set-Location D:\Nexus\nexus-server
$jwtBytes = [byte[]]::new(32)
[Security.Cryptography.RandomNumberGenerator]::Fill($jwtBytes)
$env:NEXUS_JWT_SECRET = [Convert]::ToBase64String($jwtBytes)
mvn test
```

`mvn test` 包含不依赖数据库的单元/MVC 测试，以及使用真实 Spring、Flyway、MySQL 和随机 HTTP 端口的集成测试。若连接失败，先检查 MySQL 是否运行、测试库用户权限、当前工作目录和本地配置；若 JWT 属性缺失，确认环境变量设在运行 Maven 的同一窗口。不要用跳过测试或关闭 Flyway 来掩盖这些错误。

若完整套件在本机出现 MySQL `Too many connections`，可以在**当前测试进程**中限制每个 Spring 上下文的连接池，再重跑：

```powershell
$env:SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = '2'
mvn test
```

这只调整测试期间的连接池上限；不要以此替代检查数据库其他连接占用。运行前仍须按上文生成本次专用的 `NEXUS_JWT_SECRET`。

## 2. 前端构建与模拟浏览器回归

在另一个 PowerShell 窗口运行：

```powershell
Set-Location D:\Nexus\nexus-console
npm ci
npx playwright install chromium
npm run build
npm run type-check:e2e
npm run test:e2e
```

Playwright 测试自行启动 `127.0.0.1:4173` 的 Vite 服务，使用模拟的 `/api/` 和部分 `/v1/` 响应；不要求 Spring 或 MySQL 正在运行。端口被占用时先释放该端口。失败截图和 trace 在 `nexus-console/test-results/`。这组测试验证界面行为，不能替代下面的真实后端验收。

## 3. Usage 页真实后端验收

使用可丢弃的本地测试库。分别保持后端和前端两个窗口运行，前端默认在 `http://localhost:5173`：

```powershell
# 窗口 A：在 nexus-server，配置并生成本次运行的 NEXUS_JWT_SECRET 后
mvn spring-boot:run
```

```powershell
# 窗口 B
Set-Location D:\Nexus\nexus-console
npm run dev -- --host localhost --port 5173 --strictPort
```

第三个窗口执行：

```powershell
Set-Location D:\Nexus\nexus-console
node scripts/accept-usage-real.mjs
```

脚本通过真实 HTTP 创建随机测试账号、Application 和**全新** API Key；用该 Key 调用 UUID，并轮询异步 Usage 写入。随后在 Chromium 中登录控制台，通过页面内链接进入 Key 的“调用统计”，核对累计数、`uuid.generate`、今天及自定义北京时间范围；禁用 Key 后核对机器接口 403 和历史可见，再重新启用并调用。成功时输出 `PASS` 和非敏感的资源 ID。脚本在结束时软删除本次创建的 Application；注册的测试账号仍留在测试库中。因此建议在可重建的专用测试库运行，不要在正式数据中反复执行。

若服务使用其他地址，可设置 `NEXUS_ACCEPT_API_ORIGIN` 和 `NEXUS_ACCEPT_CONSOLE_ORIGIN` 后再运行脚本。脚本不会打印或持久化密码、JWT、完整 API Key。当前控制台会话只保存在页面内存，登录后通过页面内链接进入 Usage 页；整页刷新会回到登录页。

## 4. Usage 逐条记录分页

`GET /api/usage/events` 使用管理端 Bearer JWT，必填 `applicationId`、`apiKeyId`；`current` 默认 1，`size` 默认 10，允许 1–100。响应 `data` 包含 `total`、`current`、`size` 和 `records`，每条只展示 `apiCode`、`httpStatusCode`、`durationMs`、`occurredAt`（带北京时间偏移）。记录按调用时刻倒序，同一时刻按内部 ID 倒序；上方统计时间范围不限制逐条历史。已禁用 Key 可查历史，已删除 Key 不可查；Filter 拒绝的请求和公开短链跳转仍不写入 Usage。

可重复验证：在专用测试库运行 `mvn -q -Dtest=UsageHttpIntegrationTests test`，它插入同一时刻的 11 条记录，并验证默认第一页 10 条、第二页 1 条、自定义每页 5 条、禁用历史、无权访问及非法页大小。运行 `npm run test:e2e -- e2e/usage.spec.ts` 验证浏览器翻页和每页条数输入。完整回归仍按第 1、2 节执行；第 3 节现有真实浏览器脚本只覆盖 Usage 汇总与禁用历史，不覆盖 11 条翻页。

## 这些测试各自证明什么

| 层次 | 主要证明 | 不证明 |
| --- | --- | --- |
| 单元/MVC 测试 | 单个规则、输入边界、HTTP 翻译 | 真实数据库和完整服务接线 |
| Spring 集成与真实 HTTP 测试 | Spring 接线、Flyway/MySQL、认证及 API 路径 | Vue 页面在浏览器中的真实调用 |
| 模拟浏览器测试 | 页面交互、路由、请求形状和展示 | 后端状态与持久化 |
| Usage 真实验收脚本 | 新 Key → 机器调用 → 异步入库 → 管理查询 → Vue 展示及禁用历史 | 其他功能的完整人工验收 |

2026-10-08：在本地运行中的后端、MySQL 和 Vite 上，Usage 真实验收脚本通过；测试 Application 已软删除。后端完整测试 165/165、模拟浏览器测试 44/44 和构建通过是此前 Usage 检查点的结果，本次未重跑完整套件。

2026-10-08：加入逐条记录分页后，后端完整套件使用新生成的一次性 JWT 测试密钥通过 166/166，前端构建和模拟浏览器测试通过 45/45。第一次未设置密钥的完整后端测试出现 6 个上下文初始化错误；补齐临时测试配置后全部通过。新的分页验证是 Spring/MySQL/HTTP 与模拟浏览器两层，尚未通过真实浏览器执行 11 条翻页脚本。

2026-10-08：Application、API Key、短链接管理列表的现有查询已分页。可运行 `mvn -q -Dtest=ManagementListPaginationIntegrationTests test` 验证三个列表各 11 条跨页、自定义条数和权限隔离；运行 `npm run test:e2e -- e2e/listPagination.spec.ts` 验证页面翻页与页外详情。完整后端套件在限制本次测试连接池后通过 167/167，模拟浏览器回归 48/48。接口说明见 `docs/MANAGEMENT_PAGINATION.md`。

2026-10-08：Element Plus 组件与滚动条改造后，前端构建、E2E 类型检查和模拟浏览器回归 56/56 通过。提交前尝试重新运行后端全套时，从 Flyway 日志发现当前本地配置连接 `nexus` 而不是文档建议的独立 `nexus_test`，已中止运行；这次不能记作通过，也不保证中止前没有测试写入。后续只对核实属于 Usage 验收的三名 `usage-accept` 用户及其关联数据执行了定向清理，不能据此断言其他测试写入均已清除。再次运行前先核实专用测试库及连接配置，再使用新生成的临时 JWT 密钥。
