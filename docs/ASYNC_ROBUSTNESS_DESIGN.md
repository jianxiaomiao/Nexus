# 前端健壮性与异步状态边界

2026-11-19。用户授权实现专项审查方案的第 1—7 项。修改仅针对源码、本地构建与隔离验证，不包含部署、安装版替换或生产数据操作。

## 问题与选择

旧列表存在成功、失败、finally 乱序；详情的读取版本没有覆盖写入回填；一次性凭据守卫可能阻止退出；Android 续期失败没有使并发读取失效；Web 截图创建响应丢失后缺少安全恢复。

保留 Vue/Pinia、现有三端认证和单体后端。没有引入查询框架、Redis、队列或统一业务基类。

## 审查结论与实现位置

下表是代码确认的问题及本轮处理，并非凭操作习惯推断。严重程度按可能造成的身份污染、重复扣额、错误操作和恢复困难判断。

| 分类 | 触发与原影响 | 程度／实际端 | 实现位置与验证 |
| --- | --- | --- | --- |
| 已确认，已修复 | 连续翻页、改变每页数量或刷新，旧成功、旧错误、旧 finally 覆盖最新内容或 loading | 高；Web 与共用 Renderer 的桌面 | `src/views/ApplicationsView.vue`、`ApiKeysPanel.vue`、`ShortLinksView.vue`；`createRequestGate` 与查询版本；可控 Promise 分别释放新旧响应 |
| 已确认，已修复 | 在 A 的改名／轮换请求期间切到复用的 B 详情，迟到结果回填 B，或已保存但未关闭的完整 Key 跟随页面 | 高；Web／桌面 | `ApplicationDetailView.vue`、`ApiKeyDetailView.vue`、`ApplicationWebhookPanel.vue`；页面代次、不可变操作快照、敏感值清理；真实路由复用测试 |
| 已确认，已修复 | 未保存一次性 Key 时发生强制退出，离页守卫阻止返回登录；全局编辑确认框仍可提交 | 高；Web／桌面 | 上述凭据页面及 `ApiKeysPanel.vue` 的会话感知守卫；退出优先清理和关闭待确认操作；模拟会话失效测试 |
| 已确认，已修复 | Android 续期失败终止会话，但同期旧管理请求成功仍被接受 | 高；Android 会话核心 | `nexus-mobile/auth/session.js`；终止推进 epoch，成功须属于当前有效会话；先挂起查询，再使刷新失败，最后释放查询 |
| 已确认，已修复 | 已发送写入断网／超时后直接重试，可能再次创建任务或扣额 | 高；Web／桌面机器调用，相关管理页面 | `mutation.ts`、`openApi.ts`、Playground 与 tracking Store；结果未知先核对；截图额外有 V15 服务端幂等与真实 MySQL 事务验证；其他写入不宣称服务端幂等 |
| 已确认，已修复 | 移动统计查询未结束时切自定义，旧查询失效但 loading 不退出，无法发起自定义查询 | 中；移动端 Usage 组件 | `nexus-mobile/components/key-usage/key-usage.vue`；切时段／输入使旧统计失效；浏览器夹具以 Promise 屏障稳定复现 |
| 已确认，已修复 | 机器 fetch 或响应体持续挂起，页面没有等待期限 | 中；Web 机器接口与 Renderer 发起的任务创建 | `networkDeadline.ts`；整个交换的超时／取消；测试包含忽略 abort 的适配器和挂起响应体；Main 原有独立请求期限继续保留 |
| 已确认，已修复 | 位于后页时服务端资源变为空，仍停在无效页码；反复变化的总数可能引起无限补查 | 中；Web／桌面列表、移动端列表与 Usage 事件 | 修正页码最多补查一次；矛盾的空列表与非零总数显示失败；删除至零、分页回退回归 |
| 已确认，已修复 | 编辑后删除成功，但客户端跳转被阻止或抛错时误报删除失败，诱导再删 | 中；Web／桌面；移动端单独处理返回失败 | 详情先清资源，再分别处理导航结果；浏览器分别注入返回失败与抛错；移动端失败回到所属列表 |
| 潜在风险，已增加保护 | 同时编辑旧详情可能覆盖他人较新的修改；原始代码没有统一前置条件 | 中；有编辑入口的管理端 | 可选 `expectedUpdatedAt` + `409 RESOURCE_CHANGED`，前端刷新核对；服务端真实事务冲突测试。没有运行旧版客户端来推断其行为 |
| 潜在风险，已增加保护 | 人类换账号时机器任务的本机跟踪可能残留，迟到 PNG 或清理结果重建旧预览 | 高；桌面 Main／Renderer 各自处理 | `nexus-desktop/main.cjs`、`screenshotTrackingStore.ts`、`initScreenshotTracking.ts`；真实源码 Electron／IPC + 固定模拟后端，不等同安装版验收 |

路径未写端前缀时相对 `nexus-console`；文件行号以当前源码为准，后续变更应按函数定位。

以下为本轮收尾核对的代码位置，便于从规则追到实现和复现用例：

| 项目 | 关键代码位置（仓库相对路径与行号） | 稳定复现／验证位置 |
| --- | --- | --- |
| 1 请求竞态 | `nexus-console/src/views/ApplicationsView.vue:63`；`src/views/ApplicationDetailView.vue:59`；`src/utils/pageScope.ts:5` | `nexus-console/e2e/asyncRobustness.spec.ts:30`（写 A 后切 B）、`:74`（旧 finally） |
| 2 重复提交 | `nexus-console/src/views/ApplicationsView.vue:118`；`nexus-server/src/main/java/com/nexus/job/service/ScreenshotJobService.java:62` | `nexus-server/src/test/java/com/nexus/job/service/ScreenshotSubmissionIntegrationTests.java:70`、`:87`、`:97`（重放、回滚、不共享锁） |
| 3 异常恢复 | `nexus-console/src/api/networkDeadline.ts:2`；`src/api/screenshotSubmission.ts:6`；`nexus-mobile/components/key-usage/key-usage.vue:316` | `nexus-console/e2e/screenshot.spec.ts:93`（410 后保留标识）；`scripts/test-network-deadline.mjs:5` |
| 4 会话变化 | `nexus-console/src/api/http.ts:124`；`nexus-mobile/auth/session.js:38`；`nexus-desktop/main.cjs:875` | `nexus-console/e2e/asyncRobustness.spec.ts:54`；`nexus-desktop/scripts/test-robustness.cjs:23`；三端既有会话测试 |
| 5 数据一致性 | `nexus-server/src/main/java/com/nexus/application/service/ApplicationService.java:85`；`nexus-console/src/views/ShortLinksView.vue:121`；`src/views/ApplicationDetailView.vue:197` | `nexus-console/e2e/asyncRobustness.spec.ts:98`；`ScreenshotSubmissionIntegrationTests` 的 stale edit 场景与各业务集成测试 |
| 6 清理 | `nexus-console/src/stores/screenshotTrackingStore.ts:77`；`src/desktop/initScreenshotTracking.ts:10`；`src/utils/pageScope.ts:15` | 源码 Electron 身份场景；`e2e/trackingInitialization.spec.ts`；`e2e/desktopScreenshotDisplay.spec.ts` |
| 7 测试入口 | `nexus-console/e2e/asyncRobustness.spec.ts:1`；`nexus-server/scripts/verify-async-robustness.py:1`；`nexus-mobile/scripts/verify-builds.cjs:1` | 下文复验步骤和本轮结果；实际运行日志分开记录，避免混淆平台与重跑次数 |

已有保障经本轮相关回归继续保留：Web HttpOnly RT、同源 CSRF、跨标签页凭据协调与未知刷新标记；Main 私有 AT／RT 和合并续期；Android Keystore／working 标记；原业务 401 不重放写入；Key／Webhook 轮换的独立版本条件；服务端所有权和输入校验。前端校验只改善反馈，不作为安全边界。

无法确认：本轮没有连接 Android 真机；未做 iOS／小程序安全适配或真机测试；未替换已安装 Electron／APK；没有验证本轮改动的生产 HTTPS 行为。H5 夹具和 App 编译不能替代这些验收。多进程任务容量调度不在本轮改变，现有单 JVM 部署约束保留。

## 两层隔离

- `api/http.ts` 在管理请求的响应边界比较原会话意图，并核对网页共享状态。旧成功和旧错误不进入新身份的页面；管理响应还需有合法的 SUCCESS 信封。
- `usePageScope` 管理组件寿命、资源身份和人类会话意图；`createRequestGate` 管理同页查询先后。成功、失败、loading、提示、Secret、后续请求和导航分别检查自己的上下文。
- AT 正常轮换不等于换账号；不以 AT 字符串或每次刷新 revision 丢弃同一会话的有效读取。
- Android `end()` 改变 epoch，在途管理成功必须仍属于有效会话。显式退出、迟到凭据补偿、持久 working 标记和不重放写请求的规则保留。

查询与写入分开：读取可以丢弃或取消；已经发送的写入不能因离页就宣称撤销。写操作锁包含异步校验、确认和发送；确认后重新检查上下文。旧写响应不能更新新资源或导航。写入成功后使较早资料读取失效。

## 编辑冲突

Application、Key、Webhook、短链更新支持 `expectedUpdatedAt`，当前 Web/桌面和 Android 的相关管理操作发送读取到的值。

- 先验证所有权，再比较预期更新时间；冲突返回 `409 RESOURCE_CHANGED`。
- Application、Key、Webhook 在既有事务/行锁内处理；短链管理更新同时带比较条件。
- 时间精度为毫秒；本轮修改的条件更新保证新时间至少比旧值多 1ms，避免冻结或同毫秒时钟令条件失效。
- Key 的 `expectedPublicId` 和 Webhook 的 Secret 版本条件继续保留，不能用更新时间取代凭据轮换约束。
- 为兼容已发行客户端，该字段可选。未携带条件的旧客户端继续使用原契约，不能声称所有旧客户端都获得冲突保护。这是业务并发控制，不是授权边界。

## 结果未知

请求未发出、服务端明确拒绝、明确成功、结果未知分别处理。普通取消和旧会话清理不弹出新账号错误。已发送写入的断网、超时、5xx 和不可信成功响应不能被当成确定未执行。

管理页面进入需核对状态，暂停直接重复提交；新读取确认资料后由用户决定下一步。Key 创建/轮换丢失的完整凭据无法找回，仍需轮换或删除。前端阻断不等于服务端幂等。

机器 API 的完整交换（含响应体）设定期限：普通工具、短链、异步任务创建/查询默认 15 秒；同步截图与提取 75 秒。期限结束停止客户端等待，不证明后端停止或未扣额。计时器、外部 abort 监听在 finally 清理。

非幂等写入恢复前要求明确核对；“核对后开始新操作”会说明可能再次扣额，不自动发送。异步截图使用下面的服务端契约恢复原提交。

## 截图创建幂等：V15

接口 `POST /v1/web/screenshot-jobs` 支持可选 `Idempotency-Key`：

```text
<13 位 Unix 毫秒时间戳>.<规范小写 UUID>
```

- `submission_key`：可空 ASCII 二进制比较 VARCHAR(50)，旧客户端为 NULL。
- `submission_expires_at`：可空 DATETIME(3)，UTC；有标识时为签发时间加七天。
- `(api_key_id, submission_key)` 唯一索引；相同标识跨 API Key 独立。
- 原目标 URL 是比较参数，不新增可泄露的完整凭据字段，不改变任务状态、主键或归属。
- 同身份、同标识、同参数返回原 Job ID，重放查找先于队列容量检查，不重复扣配额。
- 同标识不同参数返回 409；非法标识 400；过期标识 410；允许最多一分钟未来时钟偏差。
- 任务插入、唯一键占用与扣额共同提交/回滚。单 JVM 准入锁保留；数据库唯一键竞争失败时，配额事务回滚，再查询胜出的原任务。
- 标识的时间戳使过期重试在历史清理后也不能被当成新提交。有效期内元数据不清理，过期后继续服从既有历史清理窗口。PNG 保留期不因重试延长。
- 恢复时的 408／409／410／429 不能用来证明原提交未成功；客户端保留原标识与核对入口。特别是七天到期，不得因为收到 410 就自动换新标识。
- 没有标识的旧客户端保留“每次提交新建”契约。
- HTTP 重试仍可能产生多个调用审计事件；任务数、实际准入配额与 HTTP 尝试次数是不同指标。

浏览器持久记录仅含公开 Public ID、目标摘要、随机提交标识和已核对状态，发出前保存。未知结果的显式再次发送沿用原标识，不自动重试，不保存 API Key、Secret 或完整 URL。已知成功或明确终止后，新操作获得新标识；过期未知标识须明确核对后放弃，不能悄悄换号重建。

桌面 `forwardBackend` 仅对截图创建 POST 放行该请求头；通用任意头透传仍禁止。

这是需要配套发布的新契约。旧后端可能忽略该请求头，客户端没有协议协商探针；只有匹配 V15 服务端时才能承诺截图创建幂等。不能仅发布新前端就认为线上已有该保障。

## 退出与资源所有者

人类退出/换账号优先清理完整凭据与弹窗，普通保存守卫不得阻止强制登录导航。普通离页仍提醒未保存凭据。离页后的响应不能重新写入 Secret，复制回调不能重新创建计时器。

桌面人类身份变化时，Renderer 缓存和 Main 本机跟踪清空；Main 取消轮询及结果读取，迟到结果不能复活旧任务。服务端任务继续执行，不伪装成取消。旧 revision 的失败退出/登录不能停止新身份任务。机器身份仍由 API Key 验证，不与人类身份混用。

页面订阅、Blob URL、页面计时器按页面寿命清理；应用级会话协调器按应用寿命存在。Android 隐藏页失效查询与敏感值，重新显示按当前资料重查。自定义统计输入变化清除不匹配的统计；切自定义立即退出旧 loading。

## 验证入口

- `nexus-console/e2e/asyncRobustness.spec.ts`：真实页面的乱序、loading、详情复用、退出守卫、会话响应和分页归零。
- `scripts/test-request-gate.mjs`、`test-network-deadline.mjs`、`test-screenshot-submission.mjs`：可控 Promise、时钟及存储边界。
- 原 Web 会话、桌面 Store/Main、管理、截图、提取、订阅测试继续回归；故障恢复测试要求显式确认，不能保留直接重试的旧预期。
- `nexus-mobile/scripts/test-management-ui.cjs`：平台适配器与业务响应全部替换的浏览器测试，含受控挂起请求后切自定义。
- `nexus-desktop/scripts/test-robustness.cjs`：独立 profile 的真实源码 Electron/IPC；不访问后端，验证请求头透传、身份清理、迟到轮询和旧退出隔离。
- `nexus-server/scripts/verify-async-robustness.py`：临时 MySQL 实例、随机测试凭据、仅 nexus_test，离线 Maven；结束停止并删除临时实例，不读取现有开发数据库凭据。

测试结果与实际平台边界见 `SESSION_HANDOFF.md` 的本轮条目。浏览器夹具、源码 Electron、App 编译、真机和安装包验证分别记录。

## 执行顺序与验收门槛

第 1—7 项已按依赖拆分实施，后续回归或排查也按此顺序执行：

1. 请求竞态：先验证请求边界与页面代次，再用可控响应顺序验证成功、失败、finally、A→B 页面复用和卸载；期望旧结果在所有出口都失效。
2. 重复提交：验证校验／确认／网络阶段互斥，未知结果不能直接重试；截图额外验证同标识重放、改参数冲突、跨 Key 隔离、过期和数据库竞争；期望一个逻辑提交只准入一次。
3. 异常边界：验证空／失败／断网／超时／格式异常和恢复按钮；测试适配器不响应取消、响应体挂起；期望旧资料不冒充新查询结果、页面可恢复。
4. 认证会话：保留并发合并与三端各自的凭据策略，验证退出／登录意图／刷新失效后的旧成功、旧 401 和迟到 Main 返回；期望不污染新身份、不重放原写入。
5. 一致性输入：管理输入仍由后端独立校验；验证编辑预期时间、所有权、并发冲突、分页收缩／归零、删除成功但导航失败；期望刷新核对而不是覆盖或误报重复操作。
6. 资源清理：验证凭据窗口、复制回调、订阅、定时器、AbortController、Blob URL 和本机跟踪寿命；退出或切换后不复活旧状态，服务端任务不被误称已取消。
7. 自动化收口：先纯状态测试，再浏览器夹具，再临时 MySQL 事务，再源码 Electron／IPC 和平台编译；真实设备和部署验收单独记录，不能混用证据。

## 优先级与剩余发布工作

| 优先级 | 状态与范围 | 验证要求 |
| --- | --- | --- |
| 上线前必须处理 | 本轮已修复身份／过期结果隔离、未知写入处理、截图数据库幂等和编辑冲突。发布时必须先备份并在隔离环境演练 V15，再发布匹配后端与客户端；本轮没有执行发布 | 迁移成功、幂等重放只扣一次、旧客户端兼容；按实际发行平台复验登录／退出／续期和失败恢复 |
| 建议近期处理 | 本轮已补可稳定复现的自动化；Android 新改动真机与正式包验收仍需设备，iOS／小程序仍依赖各自会话实现 | 区分编译、H5 夹具、源码 Electron、真机和安装包结果；生产 HTTPS 另有门禁 |
| 可延后 | 观察真实问题后再决定查询框架、普遍写入幂等或单独整数 revision；不在本轮引入 | 以实际冲突、未知结果和维护成本为依据；保留当前最小实现 |

回退应以发布批次为单位保留旧前端／后端产物。V15 新列可空，旧代码无需使用它们；若回退业务版本，先停止依赖新幂等保证的客户端恢复流程，保留迁移和已写记录，不直接删除列或重跑历史迁移。本轮仅提供源码与验证，不代替发布审批。

## 本地复验步骤

使用已有 Node 24、项目依赖和浏览器，不运行依赖安装或全局 `lint --fix`。以下操作只面向源码测试；不要给 Electron 会话验收加 `--live`。

```sh
# 仓库根目录：纯状态、传输、时钟、会话和幂等记录，共 161 项
node --test nexus-mobile/scripts/test-auth-session.cjs nexus-mobile/scripts/test-management-contracts.cjs nexus-mobile/scripts/test-management-lifecycle.cjs nexus-desktop/scripts/test-auth-session.cjs nexus-console/scripts/test-request-gate.mjs nexus-console/scripts/test-network-deadline.mjs nexus-console/scripts/test-screenshot-submission.mjs nexus-console/scripts/test-auth-session-initialization.mjs nexus-console/scripts/test-auth-session-login.mjs nexus-console/scripts/test-auth-session-invalidation.mjs nexus-console/scripts/test-auth-session-renewal.mjs

# 服务端：自动创建临时 MySQL，运行 113 项相关测试（含 UTC 持久化），最后销毁实例
python3 nexus-server/scripts/verify-async-robustness.py

# 移动端：复用 HBuilderX 已安装编译器，分别编译 h5 与 app
node nexus-mobile/scripts/verify-builds.cjs

# 桌面：构建源码 Renderer，再运行两个独立 profile、固定模拟响应的验收
node nexus-desktop/scripts/build-renderer.mjs
node nexus-desktop/scripts/test-robustness.cjs
node nexus-desktop/scripts/test-session-renewal-acceptance.cjs
```

浏览器回归在 `nexus-console` 目录执行；本机使用 `NEXUS_TEST_BROWSER_CHANNEL=chrome` 复用已安装 Chrome，业务响应由夹具接管：

```sh
node node_modules/vue-tsc/bin/vue-tsc.js --build --force
node node_modules/typescript/bin/tsc --noEmit -p tsconfig.e2e.json
NEXUS_TEST_BROWSER_CHANNEL=chrome node node_modules/@playwright/test/cli.js test e2e/asyncRobustnessReview.spec.ts e2e/asyncRobustness.spec.ts e2e/applications.spec.ts e2e/apiKeys.spec.ts e2e/quota.spec.ts e2e/usage.spec.ts e2e/listPagination.spec.ts e2e/shortLinks.spec.ts e2e/webhooks.spec.ts e2e/webSession.spec.ts e2e/desktopSessionInvalidation.spec.ts e2e/desktopSessionLogin.spec.ts e2e/desktopSessionLogout.spec.ts e2e/screenshot.spec.ts e2e/playground.spec.ts e2e/webExtract.spec.ts e2e/trackingInitialization.spec.ts e2e/desktopScreenshotDisplay.spec.ts e2e/auth.spec.ts --workers=3
```

移动管理浏览器测试需要先在专用回环端口启动 HBuilderX 的 H5 开发预览，再以 `NEXUS_MOBILE_TEST_BASE_URL` 指向该预览运行 `nexus-mobile/scripts/test-management-ui.cjs`。脚本注入安全会话适配器并拦截全部业务请求，不将 H5 预览称为真实移动认证。测试结束只关闭自己启动的预览。

临时数据库工具的 MySQL／Java／Maven 路径可分别通过 `NEXUS_TEST_MYSQL_HOME`、`JAVA_HOME`、`NEXUS_TEST_MAVEN` 覆盖；默认值为本机已有安装。它只连接自己新建的实例，不尝试修复或迁移已有数据库。若前置工具缺失，保持失败并补齐环境，不切到日常库。

## 首次实现验收结果（2026-11-19）

| 边界 | 实际结果 | 不包含的结论 |
| --- | --- | --- |
| 纯状态／会话／传输／时钟 | 159/159，失败 0 | 不等同浏览器或真机 |
| Chrome 相关完整回归 | 169/169；最后新增过期恢复场景后，58/58 相关复验；重叠 57 项，累计 170 个不同用例 | 业务 API 由夹具接管，未访问真实服务；没有把重跑次数当不同用例 |
| 后端单元／控制器／事务 | 13 个测试类，110/110；临时 MySQL 从空库迁移到 V15；并发重放、两个独立服务锁竞争、插入失败扣额回滚、冲突编辑均通过 | 没有迁移日常库或生产库，没有启动真实截图作业 |
| 移动管理浏览器夹具 | 完整注册登录／Application／Key／配额／Usage／删除流程通过，包括挂起统计时切自定义 | 安全适配器与业务响应模拟，不能称为 Android 真机认证验收 |
| 源码 Electron／IPC | 身份清理、迟到轮询、旧退出不停止新任务、幂等头透传通过；固定模拟会话验收 6/6 | 本轮没有真实后端或安装包验收；客户端缩短期限只用于触发，不宣称真实 JWT 自然到期 |
| 类型／静态检查／构建 | Vue 与 E2E 类型通过；改动前端只读 ESLint 通过；Web、桌面 Renderer、H5／App 编译通过 | 构建保留现有大 chunk 提示，未做无依据拆包或换依赖 |

非秘密结果和完整运行输出在忽略目录 `data/async-robustness-20261119/`，汇总为 `verification.json`。临时 MySQL 已停止删除，本轮移动预览 5189 已关闭；用户原有服务、真实账号、生产和已安装客户端未由本轮改动。源码与文档保留工作区，未暂存／提交／推送。


## 二次全面复查与上传批次（2026-11-19）

用户明确要求重新审查第1—7项后上传 GitHub，暂不部署。本批次使用独立分支 `codex/async-robustness-review`，只提交本专项改动；主工作区中部署资料、其他任务和纯换行变化不混入。

复查并非只重跑原测试；新增确认并修复以下缺口：

| 发现 | 实际影响与端 | 处理与证据 |
| --- | --- | --- |
| 短链选择变化后旧 finally 不释放忙碌状态，且 A→B→A 只比较值 | 中；Web／桌面共用 Renderer，移动端没有该短链管理页面 | 选择变化递增操作代次、关闭旧确认并释放忙碌；新增挂起写入后切 B 和切回 A 两个真实路由测试 |
| 截图 localStorage 读改写跨标签未串行 | 高；Web／桌面 Renderer，Android 首版没有截图提交入口 | 用同源 Web Locks 串行准备、核对、结束记录；两个标签先被受控锁挂起，再放行，同目标复用标识、不同目标不丢记录。源码 Electron 额外确认自定义安全协议支持。无锁浏览器阻止提交；Node 单元夹具不调用浏览器锁 |
| 同步截图／提取等 5xx、异常成功或切工具取消未进入核对状态 | 高；Web 调试台及相同 Renderer 页面 | 保存发送时的操作、目标和机器身份快照；未知结果要求核对，异步恢复必须原目标／身份；补 5xx、异常响应、切工具中止三项受控测试 |
| 配额刷新被改名／状态写入使版本失效后 loading 未释放 | 中；Web／桌面 Application 详情；Android 的页面和配额版本单独核对 | 写入成功释放被失效读取的 loading；挂起刷新→改名→释放旧失败，确认刷新重新可用且不显示旧错误 |
| 旧机器客户端不带条件更新短链时可能重复旧时间戳 | 中；共同后端契约 | 所有机器更新均在归属行锁内推进版本时间；条件仍可选。临时 MySQL 将旧时间设在时钟前方，验证旧客户端也推进时间，新管理端旧版本冲突 |
| 编辑表单刷新元数据后采用了新时间戳而非打开时的值 | 中；Web Application 列表、Android Application／Key 确认弹窗 | 打开表单时保存预期更新时间；Web 后台刷新列表后仍提交原版本；两个移动端实际页面方法测试验证冲突提示、释放 busy 和阻止第二次写入 |
| 管理更新返回 SUCCESS 但 data 为空或身份／版本字段缺失 | 中；Web／桌面相关更新入口；移动端各自传输／页面错误处理保留 | Application／Key／Webhook／短链更新在写回前验证必要身份与时间字段，异常按结果未知处理；Application／Key 详情夹具验证旧详情保留且有核对入口 |
| 新增状态工具测试未纳入持续集成；独立类型检查入口未覆盖引用项目 | 覆盖缺口；开发验证 | 新的请求门、期限、截图记录和移动编辑生命周期测试进入现有 CI；独立类型检查使用 `vue-tsc --build --force`。原桌面构建已含引用项目类型检查，不将旧单独空项目检查当新证据 |

| 平台／边界 | 二次复查实际结果 | 验收范围 |
| --- | --- | --- |
| 纯状态与会话 | 161/161，失败0 | 移动51（含新增页面方法2）、Main41、Renderer会话60、基础工具9；全部本地夹具 |
| 浏览器 | 完整178/178；最终管理相关73/73复验（新增3，重叠70），累计181个不同用例 | 各业务接口模拟；新增跨标签测试使用真实浏览器 Web Locks。未声称连接线上后端 |
| 后端 | 13类110/110 | 新建临时 MySQL，空库迁移V15、唯一键／扣额事务和各类冲突；旧客户端推进时间纳入既有集成场景 |
| 移动端 | 完整管理浏览器夹具、H5／App编译通过 | 原生生命周期由现有 uni-app 代码、平台夹具与页面方法共同验证；本轮没有新真机、iOS或小程序验收 |
| 桌面 | 最终源码 Electron 身份场景及固定模拟会话6项通过 | 不替换已安装版，不连接真实后端；Main与Renderer均验证，RT不回传页面 |
| 类型／构建 | 强制引用项目类型、E2E类型、改动前端只读ESLint、Web／桌面Renderer构建通过 | 不安装依赖，不扩大到无关重构 |

首轮复查的3个浏览器失败保留日志：两个旧用例直接重试未知写入，另一个使用缺少实际字段的短链更新夹具；已改为显式核对和完整真实契约后，完整178项重新通过。新增浏览器锁也暴露Node夹具平台检测问题，已令纯Node测试走本地夹具路径并以161项通过验证；没有绕过浏览器串行保护。

未发现当前验证范围内仍阻塞上传的功能问题，但不作“所有平台零缺陷”的保证：真实设备、发行安装包和生产HTTPS仍未验收；旧后端可能忽略幂等头；旧客户端不带编辑条件仍保留兼容语义。普通管理写入不具备普遍服务端幂等；本地准备失败保守进入核对可能多一次用户操作，不能因此自动重发。

新证据在忽略目录 `data/async-robustness-review-20261119/verification.json` 与相邻日志。GitHub现有工作流只构建／测试，没有部署步骤；本轮不迁移既有数据库、不操作生产、不更新安装版。临时 MySQL 自动删除，临时移动预览在收尾关闭。


## CI 完整回归补验：UTC 与编辑版本（2026-11-19）

上传后的 Nexus CI #29（be87e5c）前端、Electron、移动端通过，后端751项中在 `UtcPersistenceIntegrationTests` 失败1项：冻结时钟下旧断言要求轮换后仍为 `.123Z`，实际版本时间为 `.124Z`。该类未包含在此前110项相关复验中；这是上一轮覆盖遗漏，保留失败记录，不用重跑碰运气或放宽时间精度掩盖。

修复保持业务实现：创建与删除／级联仍使用原 UTC 时钟；活动资源的编辑版本严格递增。冻结 `.123Z` 时，轮换必须精确为 `.124Z`，随后编辑必须精确为 `.125Z`；测试同时核对响应与 MySQL 落值，创建时间不变。该规则不引入8小时换算，也不把版本推进当成数据库或系统时钟偏移。

`UtcPersistenceIntegrationTests` 的3项加入临时库脚本默认范围（14类113项），并加入CI洛杉矶默认时区的额外回归。此次本地 UTC 的113项与 Los_Angeles 的3项均通过，使用两个自动销毁的临时MySQL，不访问既有数据库。

CI原有的1项跳过来自 `WebSessionBrowserIntegrationTests` 的显式环境开关 `NEXUS_RUN_WEB_BROWSER=true`；没有新增跳过、禁用或放宽断言。默认流水线的通过不能冒称这项真实浏览器／后端集成已经运行。

本次合并门禁：修复提交对应的 Web、Electron、移动端、后端四项 CI 均成功，后端的跨时区步骤也必须成功；检查 PR 头与预期 SHA 后再合并 main。只更新源码仓库，不部署、不迁移既有数据库或替换安装包。
