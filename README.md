# Nexus

Nexus 是一个用于学习系统设计与工程实践的 **Developer API Platform**。它将用户管理、Application、API Key、开放 API、配额和用量记录组织在同一个模块化单体中，并提供 Web、Electron 桌面端和 Android 移动管理端。

项目按实际问题逐步演进：先让功能可用，再完善正确性、安全、性能、可靠性和可维护性。当前使用单体后端与 MySQL，不为技术栈数量预先拆分微服务或引入中间件。

## 当前能力

| 范围 | 已实现的主要能力 |
| --- | --- |
| 用户与会话 | 邮箱注册、密码登录、JWT 访问令牌、刷新令牌轮换、会话撤销与退出。默认会话绝对期限为 24 小时，续期不会延长它。 |
| Application 与 API Key | 应用和 Key 的创建、查询、修改、启停、删除及 Key 凭据轮换；按所属用户验证资源访问权限。 |
| 开放 API | UUID、文本摘要、网页正文提取、短链接，以及同步/异步网页截图。 |
| 配额与 Usage | Application 维度配额与请求限速、Key 维度用量查询、时间区间统计和调用记录分页。 |
| 异步任务与 Webhook | 截图任务状态与结果查询、Webhook 配置、签名通知与失败重试。 |
| Web 控制台 | 管理页面、开发者文档、API Playground；网页会话使用 HttpOnly Cookie，并处理多标签页协调。 |
| Electron 桌面端 | 复用控制台界面，Main 进程持有会话、通过受限 IPC 提供能力；支持进程内续期和退出。完整退出进程后需要重新登录。 |
| Android 管理端 | 注册登录、加密保存刷新令牌、进程重启恢复会话，以及 Application、Key、配额和 Usage 管理。 |

以上描述源码能力，不代表所有客户端、安装包及生产环境均已完成同等范围验收。Android 当前以 HBuilderX 调试基座验收为主；H5 为界面预览，iOS、小程序安全会话及正式发行验收另行推进。

登录页使用可交互的小猫星球 3D 场景。针对 NVMTL 渲染器的 WebGL 兼容问题，仅在该渲染器上关闭实时阴影，保留模型、材质、动画、旋转和复位；其他渲染器沿用原阴影设置。

## 技术与目录

| 模块 | 主要技术 | 入口 |
| --- | --- | --- |
| 后端 | Java 21、Spring Boot 4.1.1、MyBatis-Plus、MySQL、Flyway | `nexus-server/` |
| Web | Vue 3、TypeScript、Vite、Pinia、Vue Router、Element Plus、Three.js | [nexus-console/](nexus-console/README.md) |
| 桌面端 | Electron、独立 Main/Preload、electron-builder | [nexus-desktop/](nexus-desktop/README.md) |
| 移动端 | 普通 uni-app Vue 3、HBuilderX、Android Keystore | [nexus-mobile/](nexus-mobile/README.md) |
| 工程资料 | API 契约、设计决策、测试与实验说明 | [docs/](docs/) |
| 工具 | Docker、部署、可观测性与独立实验工具 | `tools/` |

后端按业务模块组织，包含 `auth`、`user`、`application`、`apikey`、`openapi`、`quota`、`usage`、`shortlink`、`job`、`screenshot` 和 `webhook`。控制器、业务服务与持久层位于各自业务模块内。

管理接口与机器调用保持两套身份边界：

```text
人类用户 → 邮箱/密码 → 用户会话 → 管理接口 → 所有权校验
调用程序 → API Key → 开放 API → 配额/限速 → 业务执行与用量记录
```

MySQL 表结构由 `nexus-server/src/main/resources/db/migration/` 中的 Flyway 迁移维护。内部绝对时间使用 UTC，界面按北京时间展示；Usage 的自然日统计边界仍采用北京时间。

## 本地运行

### 环境准备

- Java 21、Maven 3.9+、MySQL 8.x。
- Node.js 24.12+ 与 npm；各客户端目录分别安装依赖。
- Android 开发另需 HBuilderX、Android 调试设备及 adb。
- 网页截图功能另需按[截图说明](docs/SCREENSHOT_API.md)准备匹配的浏览器运行环境。

### 1. 后端

先按[本地测试说明](docs/LOCAL_TESTING.md)准备独立的 `nexus_test` 数据库、专用账号和本机配置。数据库连接、JWT 密钥及 Webhook 加密配置须由本机配置或环境变量提供，真实凭据不提交到 Git。

以下为 macOS/Linux 启动入口，在配置完成后执行：

```sh
cd nexus-server
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

启动时 Flyway 会迁移实际配置的数据库；运行前确认目标是独立测试库。公共配置不提供生产凭据或无 Profile 的本地连接回退。前端默认代理到 `localhost:8080`；其他端口需要对应调整本机开发配置。

### 2. Web 控制台

```sh
cd nexus-console
npm ci
npm run dev
```

使用 Vite 输出的本地地址，默认是 `http://localhost:5173`。开发服务器把 `/api`、`/v1` 和 `/s/` 请求代理给后端。网页 Cookie 会话依赖允许的 Origin；本地应持续使用同一个主机名和端口，配置说明见[网页会话设计](docs/WEB_SESSION_DESIGN.md)。

生产构建入口为 `npm run build`。生产环境由 Web 服务器提供构建后的静态文件，并转发 API；无需运行 Vite 开发服务器。

### 3. Electron 桌面端

先安装 Web 模块依赖，再在桌面模块执行：

```sh
cd nexus-desktop
npm ci
npm run build:renderer
npm start
```

后端需单独启动。安装包、平台差异、IPC 边界和独立测试入口见[桌面端说明](nexus-desktop/README.md)。源码更新不会自动更新已安装应用。

### 4. Android 管理端

在 HBuilderX 导入 `nexus-mobile/`，按[移动端说明](nexus-mobile/README.md)运行到 Android 调试设备。该端默认连接独立的回环测试后端 `127.0.0.1:18086`，通过以下命令建立 USB 通道：

```sh
adb reverse tcp:18086 tcp:18086
```

18086 后端需要单独配置与启动，不会自动复用 Web 默认的 8080 端口。正式构建需配置预期 HTTPS Origin，并重新完成对应平台验收。

## 验证入口

| 模块 | 入口与范围 |
| --- | --- |
| 后端 | 在 `nexus-server/` 执行 `mvn test`；真实数据库测试使用独立 `nexus_test`，详见[测试说明](docs/LOCAL_TESTING.md)。 |
| Web | 在 `nexus-console/` 执行 `npm run build`、`npm run type-check:e2e`、`npm run test:e2e`。浏览器模拟响应测试不等于真实后端验收。 |
| Electron | 在 `nexus-desktop/` 执行 `npm run test:auth-session`；IPC、窗口及真实会话验收使用该模块文档列出的独立入口。 |
| Android 会话与契约 | 在 `nexus-mobile/` 执行 `node --test scripts/test-auth-session.cjs scripts/test-management-contracts.cjs`。设备交互、原生存储和进程重启需另做真机验收。 |

构建通过、模拟接口测试通过、真实后端联调通过和安装包验收通过是不同的验证边界，不相互替代。实验截图、视频、测试输出和浏览器/Electron 用户目录属于本地验证资料，不作为发布源码提交。

## 文档导航

- [Open API 与机器调用](docs/OPEN_API.md)
- [配额设计](docs/QUOTA_DESIGN.md)与[UTC 时间设计](docs/UTC_TIME_DESIGN.md)
- [Web 会话设计](docs/WEB_SESSION_DESIGN.md)与[账号状态策略](docs/ACCOUNT_STATUS_POLICY.md)
- [Android 登录与安全会话](docs/MOBILE_SESSION_DESIGN.md)
- [Android 管理页面与接口接入](docs/MOBILE_MANAGEMENT_DESIGN.md)
- [移动端基础组件](docs/design/NEXUS_MOBILE_COMPONENTS.md)与[总设计规范](docs/design/NEXUS_DESIGN_GUIDE.md)
- [截图 API](docs/SCREENSHOT_API.md)与[Webhook 尝试语义](docs/WEBHOOK_ATTEMPT_SEMANTICS.md)
- [本地测试](docs/LOCAL_TESTING.md)与[部署配置边界](docs/DEPLOYMENT_CONFIGURATION.md)

项目仍在持续演进。功能与契约以代码和迁移为准；部署记录需结合对应环境的实际检查，不把源码进度直接等同于线上状态。
