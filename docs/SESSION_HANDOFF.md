# Nexus Session Handoff

## 下一单元：后端Refresh Token设计，尚未定稿或实现（2026-11-15）

- 用户开始讨论后端Refresh Token，请先讲和当前刷新恢复的区别，以及会话表、轮换、吊销边界；核心仍按学习分工由用户先设计/实现，本轮未授权写后端核心或迁移。用户已理解页面刷新只从Main恢复原Access Token、Refresh Token用来换新Access Token；把1/3 TTL刷新时机称为轮换、认为退出需刷新RT，以及将“不匹配/过期”统称吊销，需要分别纠正。
- 已读当前`nexus-server/src/main/java/com/nexus/auth/{service/LoginService.java,token/JwtTokenService.java,web/BearerUserIdResolver.java,dto/LoginResponse.java}`：登录只返回accessToken/tokenType/expiresInSeconds，JWT含issuer/subject/iat/exp，无sid；Bearer解析验签后查当前User，现有迁移V1–V13没有登录会话/RT表。不要将工作区的webhook secret_refresh字段误当认证刷新实现。当前刷新恢复/退出代码和测试已完成下节范围，未改动。
- 本轮说明/建议，**尚非最终架构决定**：轮换指一次有效RT消费后签发新AT+RT并使旧RT失效，和何时触发刷新不同；Token不是JWT签名密钥，退出应撤销当前后端会话及清客户端，不签新RT。拟用高熵随机不透明RT、MySQL仅存摘要；如要求旧RT重放检测，建议稳定auth_sessions与多代refresh token摘要记录，而非只覆盖当前摘要。同一session代表一次登录实例/凭据链，不是一用户唯一一行，也不直接复用Main内存revision。会话绝对寿命上限和可选闲置期限需再决定，避免无限滑动续期。
- 未知/伪造RT只拒绝，不能凭客户端给的userId/sessionId吊销他人；确认匹配已消费旧RT时才讨论撤销所属会话链（严格轮换方案），并发刷新要客户端合并+后端事务/条件更新。成功响应丢失后旧RT重试可能触发重放，不能假设数据库摘要能还原新RT；首版可选重新登录或另行设计受限幂等，尚未决定。过期是按时间即时拒绝，不依赖清理定时任务。
- 最先待用户决定的产品边界：退出是否要求已发出的AT立即失效，还是只禁止续期、允许AT到原到期时间。前者需要JWT关联session并在认证时检查后端会话，当前只有用户检查不能达到；后者更简单但必须明确剩余有效窗口。普通退出当前会话与退出全部会话分开；刷新必须检查当前User状态，不能凭旧RT恢复禁用/删除账号。客户端自动刷新时机、TTLs、接口传输/错误、锁和测试场景须之后逐步讨论，不先生成DDL。
- 客户端约束仍适用：Electron RT由Main持有，不返回Renderer；Web的HttpOnly Cookie/CSRF另行设计。现有Main转发不支持Cookie/Set-Cookie。AT临近过期/401处理将来才从“直接清理”适配为“受控刷新后再决定”，不对所有401盲刷或无条件重放写请求。跨进程记住登录独立于RT签发，不自动承诺。
- 参考已核对的IETF RFC 6749 §6及RFC 9700 §4.14（轮换/保留关联/重放检测与撤销），Nexus是自己的第一方认证流程，不因参考这些规则而引入完整OAuth服务器或宣称OAuth实现合规。后端设计是新的学习单元，建议下一轮用本交接及本地代码在新对话继续；不要重复桌面历史验收或创建截图任务。

## 当前单元：过期/401的隔离Electron受控验收通过（2026-11-15）

- 最新用户要求先说明步骤，再准备隔离Electron过期/401测试，禁止真实截图任务。Agent新增独立测试入口`nexus-desktop/scripts/session-invalidation-controlled-main.cjs`和驱动`test-session-invalidation-controlled.cjs`；不修改Main/Preload/Store/Router/HTTP生产实现。测试入口在D盘.runtime隔离每场景profile，保留正式Main/Preload/nexus协议/构建页面/来源校验及会话处理器，只将Main fetch替换为固定假登录与Application查询响应，没有真实fetch回退、不绑定8080，并阻断Renderer HTTP/HTTPS。当前真实后端无需停止，也没有被访问。
- **六场景均通过**：Router自然过期、HTTP请求前自然过期、当前401、Main保存新会话之前的旧401、Main保存之后的旧401、清空已执行但IPC确认丢失。过期用模拟登录TTL=10秒自然等待，不改系统时间或直接改expiresAt，触发前不调用会自动过期清理的read IPC；分别点击真实开发文档链接和分页条数按钮，让正式守卫/HTTP执行清理，Main clear各一次，过期查询未到达Main fetch。当前401也从真实分页事件进入API与协议转发。四个清理场景都在同PID刷新后仍为空/revision2，无再登录/再写入/再清理。
- 旧401场景由正式页面发出查询，测试入口延迟该响应；已登录时公共登录路由会被守卫拦回，所以第二次登录意图通过真实Store beginLogin/completeLogin及真实写IPC注入固定假响应（并非再次真实表单登录或后端续期）。分别在Main revision仍1/已2时释放旧401，操作代次已经变化；Main clear为0，Store/Main快照与释放前完全一致。随后刷新恢复revision2的新会话。IPC丢确认场景是正式clear先真实执行，再由测试包装器抛错；在测试自己的诊断read之前，已观察到Store通过真实IPC只读核对一次并确认空状态，避免观察操作掩盖遗漏。
- 证据`nexus-desktop/.runtime/session-invalidation-controlled-AWEMKr/evidence.json` passed=true/phase=complete，脚本退出0，六窗口closed=true且PID检查均已退出。累计18条模拟Main请求（6次假登录、12次查询，其中4次401），8次成功写IPC、4次清理；范围外请求0、Renderer HTTP/HTTPS尝试0、pageerror0。userData均为本轮场景独立D盘profile，未接触安装版或默认用户配置。`humanVisualConfirmation=false`：窗口真实可见且DOM/状态自动核对，没有用户逐屏视觉确认、没有截图任务或资源创建，也不声称真实JWT/后端/安装版自然过期通过。桌面类型检查/构建及新脚本语法检查通过。
- 当前客户端“登录→刷新恢复→退出/过期/401清理”的源码检查点已完成其记录范围：真实后端登录/主动退出证据见下方，过期/401为本轮真实Electron+模拟响应。Refresh Token、跨进程记住登录、后端JWT即时吊销及安装版更新仍未实现/验收；若继续长期登录，应先讨论后端Refresh Token的会话、轮换和吊销边界，不重复创建截图任务。

- 用户已明确授权Agent实现Router/HTTP接入及IPC异常后的只读核对，并做受控测试。HTTP附Bearer时复制operationVersion/mainRevision到每请求config.authContext，仅作为本地元数据；明确管理路径、Bearer、401和INVALID_ACCESS_TOKEN且存在原上下文时才调用invalidateSession。新登录刚开始而Main revision尚未改变的旧401也会被丢弃，不清Store、不发IPC、不导航；其他401、403、500、网络错误和缺上下文不误清。原请求始终reject，不自动重放。
- Router守卫已异步等待过期清理或复用进行中的清理，成功且操作仍有效后按最新状态决定导航；stale或失败取消导航。HTTP请求前发现过期先清理并拒绝发送，clearing/clearError阻止后续管理请求；清理确认后且代次仍匹配才replace(login)，并发结果复用登录导航。导航能力由Router注册到HTTP，HTTP不导入具体页面路由；避免API类型依赖拉入Vue页面。失败沿用Store错误区并有分组提示；Docs页显示提示，未另增全局恢复界面，必要时用户显式刷新重新核对Main。
- Store在clear IPC抛错或SESSION_CHANGED后只读一次：契约合法、session=null且revision至少原目标+1才确认完成；仍原版本非空或读取失败/坏快照返回CLEAR_FAILED，较新非空返回SESSION_CHANGED。每次等待后核对操作，绝不换成新revision自动重清，也不把非空读取结果恢复进正在退出的Store。结构校验复用isAuthState。
- 受控浏览器发现Pinia开发工具为各action创建不同this代理，原WeakMap键因此找不到共享清理任务，第二个同目标401被当作stale提前结束。根据本地Pinia patchActionForGrouping实现，改以toRaw(this)作稳定键，同时修复同形初始化去重；保留不同Store实例隔离，新增两个代理级回归。并发浏览器场景现已通过，未关闭开发工具或绕过错误。
- **本轮验证通过**：真实Pinia Store的初始化/登录/失效脚本共49/49；Chromium模拟API及Main桥的auth、desktopSessionLogin、desktopSessionLogout、新desktopSessionInvalidation四文件42/42（新失效场景21项）；应用类型检查随build:renderer通过，E2E类型检查通过，renderer构建通过，范围内diff检查通过。覆盖原始401仍reject、不重放、上下文不发网络、Main版本未变/已变的旧401、并发去重、Router/请求前过期与失败、IPC丢回执只读确认、冲突不追清、迟到结果与导航取消、无桥Web。首轮并发失败已修复；测试进程默认权限退出等待曾中断，最终完整回归在正常可管理测试子进程的权限下退出0。未访问真实后端、创建截图/其他业务任务、修改Main/Preload/数据库/ECS或更新安装版；构建有现存大chunk提示，未做无关优化。
- 上述49/42检查当时只覆盖Store和模拟浏览器；后续真实Electron受控调用方验收已补，使用本节最上方独立证据，不扩大为真实后端JWT或安装版。Refresh Token、跨进程记住登录仍未实现。已有真实主动退出/刷新证据继续见下方。
- 用户授权真实退出验收，Agent重建Renderer通过类型检查/构建，给既有test-session-refresh-acceptance.cjs增加--logout模式并复用受限Main入口，不改业务核心。证据`.runtime/session-logout-live-tsY6IZ/evidence.json`为passed=true/mode=logout/phase=complete，执行会话57047退出0。用户正常登录并逐项点击三个面板，聊天确认全部完成。
- **真实退出与刷新不恢复通过**：正式页面按钮→Store invalidateSession→真实Preload/IPC→Main clear确认一次；Main/Store由revision1有会话变为revision2空会话、回/login，clearing=false且无clearError。同Main PID43604刷新后仍/login、Main/Store均空、revision仍2，无新登录/写入/清理；不是通过重启Main来掩盖结果。清理完成后测试窗口正常关闭，PID已退出，真实后端127.0.0.1:8080/Java PID24144仍监听；默认安装版及用户数据未动。
- 请求审计保留两次登录POST（先401后200），成功Main会话写入仅一次、清理一次，列表Bearer GET200两次、范围外请求0、Renderer HTTP/HTTPS请求0；没有截图任务或其他资源创建，未记录凭据/正文。此轮真实源代码验收只覆盖主动退出成功及刷新，不等于自然过期、401、失败/冲突真实环境或安装版通过；失败/冲突/迟到结果已有模拟测试。
- Store统一清理已由另一会话实现（invalidateSession、clearing/clearError、同目标去重、冲突只读核对、新登录阻断及测试脚本）。用户本轮明确要求Agent实现退出按钮，Agent只改ConsoleLayout.vue的调用方和错误/忙碌UI，不修改Store、Main、Preload、Router或http核心：调用invalidateSession({reason:'logout'})后记录本次操作代次，等待结果；stale、代次变化或组件卸载不导航；failed留当前页展示持久错误及重试；只有ok且返回代次仍匹配才router.replace(login)。本地logoutPending覆盖Main已完成但导航未完成的窗口，防重复点击；导航被守卫阻止或抛错时区分为“退出已确认，但页面切换失败”。
- 新增desktopSessionLogout.spec.ts，受控Main状态放在Node测试侧以跨页面刷新保存，验证等待/去重、成功后刷新不恢复、拒绝/异常后重试、冲突不追清、迟到确认不覆盖新本地会话、组件卸载、导航阻止。新7项浏览器场景7/7通过；此前同轮原Web认证12项及桌面登录2项均通过，应用/E2E类型检查通过。测试定位曾误匹配登录成功toast及重试后的旧按钮文案，已精确到退出错误区和稳定按钮；未改业务来绕过失败。模拟回归未访问真实后端；之后真实补验结果见上。
- 后端仍无Refresh接口/会话迁移，真正续期还需分别设计后端、桌面Main及Web适配，跨进程记住登录另行决定。当前过期/401调用方已按上方记录实现，并通过模拟浏览器及真实Electron受控验证。
- 默认核心由用户先写；其已明确逐项授权Agent实现Store初始化、退出按钮，以及本轮Router/HTTP和清理异常核对。这些授权不延伸到后端续期、数据库、安装版或部署；保留原有未提交修改。
- 最新代码已在另一对话接入登录同步：beginLogin在HTTP之前记录操作代次和Main版本，completeLogin等待writeAuthSession、核对返回状态后applyMainState；LoginView只在保存成功且操作仍有效时提示成功并导航，Web仍为内存登录。迟到成功写入仅按其返回的确切revision尝试清理，不重放到较新会话；保存失败留在登录页。用户报告另一对话通过24项Store检查、51项模拟浏览器测试及应用/E2E类型检查，本对话只读核对实际代码，未重复运行这些整套检查。
- 退出、过期及401在源码均已接统一Main清理并通过各自受控检查；过期/401另有本节最上方真实Electron+模拟响应证据。此前session-refresh-live-KWnmTg的真实验收早于退出按钮接入，不能把该证据当作退出/401通过；真实退出使用单独session-logout-live-tsY6IZ证据。main.ts已接显式Main初始化并等待ready；LoginService仍只签发JWT、无Refresh Token。
- 用户随后启动真实8080后端并授权打开隔离窗口，自行登录。Agent重建Renderer（类型检查及构建通过），新增session-refresh-acceptance-main.cjs和test-session-refresh-acceptance.cjs，仅测试入口隔离userData及观察Main fetch/IPC结果，不改Main/Preload/Store业务代码。只转发固定登录POST和Application列表GET，拒绝其余路径/方法；不记录密码、Token、请求体/响应正文或查询参数。没有启动Vite或使用安装版0.0.1。
- **真实验收通过**：`.runtime/session-refresh-live-KWnmTg/evidence.json` passed=true、phase=complete，执行会话81677退出0。首次空会话ready/revision0；用户登录POST200一次、Main write成功一次，Main/Store token一致性仅在Renderer内比较并返回布尔，revision1及绝对expiresAt匹配。同Main PID30176刷新后仍/applications，版本和原到期时间不变，无新登录POST/写入，新增Bearer列表请求200；关闭整个进程后用同D盘隔离profile重开为新PID27072，回/login且session=null/revision0。用户通过三个面板按钮逐步确认登录页/刷新页/重开页正常，并在聊天确认全部完成。进程已全部退出、真实后端仍127.0.0.1:8080/Java PID24144监听。
- 请求审计：共一次真实登录POST200、四次Application列表GET200；另一次范围外GET被测试入口拦截，未发送后端。日志不保留未知原始URL，不能推断具体路径/起因；不掩盖此记录或扩大为其他页面验收。没有创建截图任务或其他资源，Renderer HTTP/HTTPS请求0。首轮`.runtime/session-refresh-live-hW9oph/`在setup检查窗口可见性过早退出，无后端请求；测试改为等待isVisible后重开新隔离窗口成功，不修改应用显示逻辑。此轮没重跑用户报告的24/51套模拟检查，也未进行安装版、退出清理、到期或401真实验收。
- 用户已选择先单独实现Electron进程内刷新恢复——Main内存保留原有效期会话，可信主页面通过窄IPC恢复，Router等待初始化，过期/退出清空，应用退出后不保留；Web仍用原内存模式。刷新不能重算expiresAt延长JWT寿命，也不将localStorage明文持久化当作续期。
- 后续真正续期再讨论后端随机不透明Refresh Token、MySQL仅保存摘要、过期/吊销/轮换/重放与并发边界及账号状态；桌面由Main持有长期凭据且不回传Renderer，Web的HttpOnly Cookie/CSRF是另一个客户端适配。当前forwardBackend不转发Cookie/Set-Cookie，不能默认桌面已支持Cookie方案。跨应用重启的记住登录还需单独设计受保护落盘与恢复策略，不随本轮说明自动实施。
- 用户已补齐Main返回状态：read空会话也status=ok并返回authState（附带SESSION_IS_NULL code）；write/clear成功均返回修改后authState及SUCCESS。clear现在接收对象{expectedRevision}，不再是裸数字；普通非法字段返回INVALID_REQUEST、合法但不匹配版本返回SESSION_CHANGED。已验证空读→写→清→空读→再登录版本0/1/2/2/3、到期只清一次、快照结构化克隆独立、无效输入及旧版本不改状态、同版本两个write仅一个成功、来源先于输入校验。均为抽取处理器的Node VM隔离检查，不是Electron端到端。
- 用户移除了clear中校验前的解构，最新复测可信来源的null及省略参数（空/有会话共四种组合）均返回INVALID_REQUEST，session/revision不变；空读及write/clear最新状态、旧clear返回SESSION_CHANGED回归通过，语法通过。Main目前未再发现阻塞问题，可进入Preload/类型声明接线；Agent仍未改核心代码。
- 用户确认IDEA未解析提示标在input.expectedRevision的属性名上，不是独立变量；当前CJS处理器参数未声明输入结构，代码无可见未声明变量且运行检查通过，解释为符合IDE类型信息不足的情形。建议给回调参数补JSDoc结构说明（如@param {{expectedRevision:number}} input），不关闭检查、不删除运行时校验；无需为提示迁移整个Main到TS。
- 用户已在Preload既有nexusDesktop对象增加readAuthSession/writeAuthSession/clearAuthSession，频道分别为auth-session:read/write/clear；read无参，write传{expectedRevision,session}、clear传{expectedRevision}，均原样返回invoke Promise，无通用invoke暴露。Agent按明确授权补齐desktop.d.ts的AuthSession/AuthState、两个Input及三个独立Result（read成功code可选SESSION_IS_NULL；write/clear成功code固定SUCCESS；read失败仅FORBIDDEN，修改失败另有INVALID_REQUEST/SESSION_CHANGED），未修改Main/Preload核心代码。
- 应用类型检查通过；E2E类型检查起初发现三处旧NexusDesktop模拟缺少新增方法，Agent仅机械补齐desktopScreenshotDisplay/desktopShortLinks/trackingInitialization三个fixture为意外调用即抛错的未使用会话方法，复查通过。Preload语法通过；隔离加载真实Preload连到抽取Main处理器，固定频道、无参/对象参数、空会话、最新状态、旧版本及非法输入返回契约通过。未启动真实Electron/浏览器、未运行截图或后端请求，未更新安装包。
- 当前用户要求先讲Store初始化、登录/退出同步及revision/迟到响应，核心由其先写。核对清理入口包含Console退出按钮、Router过期判断、http请求过期判断及401/INVALID_ACCESS_TOKEN响应，不可仅接按钮而让其他入口只清Pinia。建议由Store集中管理纯本地应用/清空与同步Main的操作，恢复Main快照不再次触发write、不重算expiresAt。
- 初始化入口建议main.ts在app.use(pinia)后、app.use(router)前await，useUserStore(pinia)明确实例；App.onMounted对首次导航太晚。Main revision未读到前用null而非猜0；初始化幂等/复用Promise，无桌面桥时Web维持内存模式，IPC失败区分于成功读取空会话。本轮先指导用户写Store状态、applyMainState/initializeSession，再审查和接启动流程，不一次代写完整链路。
- 登录意图开始（HTTP发出之前）捕获本地操作代次和Main版本，HTTP成功后仅当前意图可write，Main成功返回的状态才应用Pinia。退出先使旧本地响应失效、清本地凭据，再同步Main；只有Main确认才宣称退出完成。Main写/清需按序协调前序确认的revision，不能只忽略旧UI响应而遗漏已在Main完成的写入；SESSION_CHANGED不盲目取新版本重放旧意图。Main revision防过时修改，本地操作代次防迟到结果覆盖当前UI，两者职责不同。详细并发规则留到后续同步动作逐步指导。
- Agent按局部授权实现userStore初始化：mainRevision初始null、operationVersion、initializationStatus idle/loading/ready/failed及静态initializationError；applyMainState校验并只应用Main快照，拒绝低版本回滚，保留原expiresAt，传输中已到期则只清本地凭据、不写/清Main。原expiresInSeconds兼容字段恢复时取剩余秒数，不用它重算expiresAt。initializeSession按每个Store的WeakMap去重进行中Promise，ready后不重复读取；无桥时保留Web内存状态，有桥缺方法/拒绝/异常/坏快照记failed并允许显式重试，不冒充空会话成功；初始化Promise正常完成后调用者仍须检查status=ready，不能只await就安装Router。
- 为挡住迟到初始化，现有setSession/clearSession只新增operationVersion递增，不增加Main写/清。较新本地动作发生后，旧读取成功或失败均不再应用，初始化回idle；读取期间已有较新Main快照则保留新版本。Agent未接main.ts、LoginView、Router、http，也未修改Main/Preload。测试nexus-console/scripts/test-auth-session-initialization.mjs通过15/15，命令node --test scripts/test-auth-session-initialization.mjs（console目录）：真实Pinia/TS Store、受控桥与固定时钟，无Electron/浏览器/后端；覆盖Web新Store不恢复、快照与到期、并发去重及实例隔离、迟到成功/失败、失败重试、旧桥、低版本回滚等。应用和E2E类型检查通过，diff检查通过。
- 用户问“Store写在console，网页也具备吗”：共用代码不等于有Electron能力，普通Web无window.nexusDesktop，不会读取Main或因此刷新保持登录。最新源码版登录/刷新恢复已通过上述真实验收；退出/过期/401的Main清理尚待实现，安装版0.0.1未重建。
- 2026-11-15用户要求先解释main.ts如何等待初始化、ready后再安装Router及失败处理，核心启动逻辑由用户写；Agent仅只读核对，未改main.ts。澄清Vue main.ts只调度启动，不规定Token等实际会话数据：Electron Main持有数据，Store通过桥读取。initializeSession内部捕获失败并正常完成Promise，因此await完成不等于ready；ready可以是session=null。失败或idle均不安装Router，失败需可见错误/显式重试，不伪装成未登录直接跳登录页。建议app/Pinia/ElementPlus只创建安装一次，重试只重跑初始化，成功后Router安装和App挂载也仅一次，可等待router.isReady；不要在App.onMounted才恢复。具体失败面板样式可由Agent后续机械补齐，不自动实现整个阶段。
- 用户已实现main.ts异步bootstrap：创建app/Pinia、安装Pinia/ElementPlus→await初始化→非ready写失败文字并return→安装Router→await router.isReady→mount，外层catch仅记录错误名称和展示通用失败文本。Agent未改main.ts；类型检查及内存转译隔离检查通过等待/ready门控/失败或idle不导航不挂载/意外异常/新页面只挂载一次。**当前重试是用户整页刷新，不是页内按钮。** 函数内创建app/Pinia且无重入锁；模拟重复调用会两次安装Router并请求mount，但源码目前没有再次调用或按钮绑定，不能据此误报现有启动重复挂载。未来若加按钮需先改为实例只创建一次及防并发/重复挂载。
- 启动开始使用readAuthSession后，三处此前刻意抛错的E2E桥模拟需要适配；Agent仅将desktopScreenshotDisplay/desktopShortLinks/trackingInitialization的readAuthSession改为成功返回revision0/session=null，write/clear仍意外调用即抛错，未改变业务测试规则。应用和E2E类型检查通过，三文件37项模拟Chromium回归37/37通过（1.4分钟）；没有真实Electron、后端或截图任务，本轮Main.ts未修改。当前“单次启动/失败后整页刷新重试”范围检查通过，不代表已支持页内按钮重试或完整登录刷新恢复。
- 用户要求先讲统一退出/过期/401清理入口、revision冲突和迟到响应；本轮仅解释及读代码，不实现核心。建议Store集中异步invalidateSession（命名由用户定），区分主动logout与针对旧观察/旧请求的自动失效；自动事件必须在修改本地代次/凭据前核对原operationVersion及mainRevision，不匹配即stale。401还保留管理路径、已发Bearer及401/INVALID_ACCESS_TOKEN条件，不对登录错误、403、机器API或网络错误清管理会话；原请求失败继续reject，不自动重放业务请求。
- 建议被接受的清理意图推进一次operationVersion并先清本地凭据、保留Main已知revision，再用捕获版本调用clearAuthSession，确认返回空会话及新版本；同目标重复清理去重，共享进行中任务。清理未确认时阻止新登录，现有在途completeLogin仍保留按确切saved.revision清理迟到写入的补偿，不将“忽略UI响应”等同撤销Main修改。Web只清本地即可；桌面IPC失败明确failed，不伪装退出成功。
- 冲突初步建议：SESSION_CHANGED时可只读一次Main，若当前操作仍有效且Main已空，采纳合法新revision并确认完成；若Main仍非空则返回冲突，不自动换新revision再clear，不把该非空凭据恢复进正在退出的Store。每次await后检查本地操作代次，旧完成不再apply空快照/提示/导航；只在当前清理被确认时由调用方导航，Store不直接依赖Router。第一小步由用户先写Store清理入口与结果，之后再接退出按钮及两个过期入口/401；不一次生成整个阶段。
- Refresh Token、跨进程记住登录及JWT服务端即时吊销均不在本轮。Main清理只删除客户端持有的会话，不会自动使后端签发的JWT失效。相关文件：nexus-console/src/stores/userStore.ts、src/api/http.ts、src/router/index.ts、views/ConsoleLayout.vue；nexus-desktop/main.cjs、preload.cjs及session-refresh验收脚本。核心继续开发者先写，Agent仅在明确局部授权下实现；不修改ECS、数据库、既有截图业务或自动提交。

## 安装版通知受控验收通过，模拟服务已停止（2026-11-14）

- 用户亲自暂停真实后端、从普通Windows桌面启动已安装的0.0.1，并在主页面Console逐步执行固定测试片段；Agent只运行模拟服务和只读核对，避开Codex启动链及AppData重定向。未修改Main/Preload/Store/ASAR或打包配置，未创建真实截图任务、未连接数据库、未返回PNG。安装ASAR哈希仍为FAD674EBBA737C815D6D470D7F9EB41E5B2929A1E339AB2FD56B7836E8C8CA86。
- 新增独立脚本nexus-desktop/scripts/serve-installed-notification-mock.cjs：--prepare生成01成功开始/02读状态/03重跟踪去重/04失败开始/05清理；--self-test在临时端口通过状态、双回环、鉴权、Host/正文/路由/方法拒绝、端口冲突和片段语法检查；--serve --dir只在127.0.0.1及::1:8080同时绑定成功后READY。服务仅接受两个假UUID及假Key的GET查询，通过stdin success/fail切换终态，其他请求拒绝，无创建/PNG/HTTP控制接口。测试工具不进入ASAR。
- 三个场景通过：RUNNING时无通知，SUCCEEDED后用户实际看到成功通知且02为TERMINAL/SUCCEEDED；stop后重跟踪同ID仍为成功终态，用户最小化观察约10秒无新通知，Main PID35404/创建时间/路径保持一致、服务记录两次成功终态响应；另一ID的FAILED后用户实际看到失败通知且02为TERMINAL/FAILED。真实Preload/IPC、Main校验/状态/去重和Windows通知均参与；未替换Notification、未采集其show/failed事件，不把HTTP200单独当作可见性证明。
- 准备时漏查根Store收到SUCCEEDED会自动尝试取图（即使登录页）；两次成功后共两条未匹配GET被服务404拒绝，与源码该逻辑一致。日志不保存原始URL，不能凭日志单独证明具体路径；没有PNG返回，不修改正式业务绕过该行为，也不把取图失败误记为通知失败。
- 用户执行05确认IDLE/jobId=null后正常关闭Nexus。Agent向执行会话52478输入stop，服务退出0；只读确认无Nexus进程、无8080监听。共46个GET、两次success/SUCCEEDED和一次failure/FAILED响应、两条404、非GET为0。证据nexus-desktop/.runtime/installed-notification-mock-T3Q88G/summary.json为passed=true，另有requests.jsonl、user-observations.json、调用片段；service-summary.json的passed=null保留为服务自身不判人工结果的设计。
- **当前0.0.1保持安装，应用关闭、模拟服务已停止，用户已恢复真实后端。** Agent只读确认127.0.0.1:8080由Java进程PID55260（D:\java21\bin\java.exe）监听，已更新summary.json；没有调用业务接口，不将监听恢复表述为业务健康检查。本地安装生命周期及安装版通知检查点均通过，不代表真实截图执行/下载回归、通知点击跳转、进程重启后的去重持久化、签名、自动更新或登录续期已完成。按既有分工，核心设计/代码仍由开发者先写；尚未启动下一功能。

## 跨版本升级0.0.0 → 0.0.1通过；当前新版已安装（2026-11-14）

- 用户要求先重装已卸载的旧版，再做真实升级并保留配置。所有安装器/应用均由用户从资源管理器/桌面手动启动，Agent只构建和只读核对，避开Codex路径及注册表视图差异。证据`.runtime/nsis-upgrade-20261114-145231/summary.json`为passed=true。旧0.0.0安装包已复制留存在该目录old-installer并校验原SHA256不变；用户未找到备份目录时改用release中哈希相同的原旧包，未重复创建资源。
- 旧版重新安装到原目录后，ASAR哈希与原产物一致，真实C盘保留配置逐字节不变，快捷方式恢复；用户手动启动确认旧版登录页和布局恢复后关闭。这也补验了卸载保留数据→重装旧版的恢复路径。
- Agent仅机械同步package.json与package-lock.json根/包版本为0.0.1，使用npm version --no-git-tag-version --ignore-scripts，未提交/打标签/发布。appId com.nexus.desktop、name nexus-desktop、productName、打包配置、Main/Preload/window-state与Renderer内容不变；窗口配置格式version仍为1，无数据迁移。
- `npm run pack:win`通过类型检查/Renderer构建/NSIS生成。新安装包release/Nexus-Desktop-0.0.1-x64-Setup.exe，124160528字节，SHA256 60430AB3465065EFD7DBB1D1C14AF61DF6EA06D7DE265AFF56D94D8BDC82B0B1，未签名；新ASAR SHA256 FAD674EBBA737C815D6D470D7F9EB41E5B2929A1E339AB2FD56B7836E8C8CA86。新旧ASAR逐文件比较仅package.json变化，其他文件字节相同。Windows exe ProductVersion为四段0.0.1.0、FileVersion为0.0.1，应用包版本为0.0.1，不混为版本错误。
- 用户保持旧版安装，直接手动执行新0.0.1包，没有先卸载；安装目录与两个lnk目标/空参数/AUMID保持一致，安装exe和ASAR均核对为新版本载荷。用户在普通Windows设置确认只有一个Nexus Desktop 0.0.1，再从桌面手动启动，确认登录页和旧布局正常后关闭。关闭后真实C盘配置（x512/y129/1154x868、maximized=false、schema1）哈希仍与升级前一致，配置文件和exe句柄均确认真实C盘。
- 工具HKCU仍显示旧0.0.0私有/合并视图，记录但不据此误判升级失败或手动删键；正常Windows版本结论依据用户设置界面确认与实际新exe/载荷。版本元数据变化覆盖安装器识别/替换/配置保留，不代表将来配置格式迁移、自动更新、签名/公开发布或新业务兼容性已验收。未新增/重跑真实截图、短链或数据库测试。
- **当前状态：Nexus Desktop 0.0.1保持安装，测试窗口已关闭，用户配置保留；旧/新安装包与证据保留，源码与锁文件为0.0.1。** 本地安装升级单元已达到当前检查点；用户尚未指定下一任务。默认图标、作者/正式版本策略、签名、自动更新和Refresh Token仍未完成，不自动进入下一大功能。

## 安装生命周期验收通过；当前已卸载（2026-11-14）

- 用户要求避开Codex路径重定向；本轮应用启动/移动/最大化/关闭、同版本安装器与卸载器全部由用户从桌面、资源管理器或Windows设置手动执行，Agent只读核对，不启动应用/安装器/卸载器。证据`.runtime/nsis-lifecycle-20261114-143143/summary.json`为passed=true，相关分阶段JSON同目录；未修改核心代码、窗口策略或系统权限，没有业务API/截图任务验收。
- 第一轮用户认为位置没保存，实际保存x349/y207/1280x860，底边1067超过当前工作区1019；resolveWindowBounds按“完整位于一个工作区内”规则回退213/79。隔离无窗口Electron查询确认屏幕1707x1067、工作区1707x1019、scaleFactor1.5；重放现有函数得到同一回退。用户明确选择保留规则，改用完全可见窗口验收，不改算法。
- 用户缩小并移动后关闭/重开，确认布局正常；配置x512/y129/1154x868、maximized=false，完整落在工作区。随后最大化关闭/重开，用户确认与原生IsZoomed均为最大化，配置maximized=true且普通矩形不变。用户还原并关闭后maximized=false，普通矩形仍一致。normal-restart/maximized-restart/before-overwrite记录覆盖上述观察。
- 用户从资源管理器手动执行同一0.0.0安装包至原目录，属于同版本覆盖重装，不是跨版本升级。安装后载荷ASAR哈希、窗口配置哈希不变，单一卸载项、两个lnk目标/空参数/AUMID正确。用户从开始菜单手动启动，确认登录页与布局恢复后关闭；配置仍逐字节一致。
- 用户在Windows设置手动卸载并保留数据。程序目录、exe、两个快捷方式及进程均消失；真实C盘Roaming/nexus-desktop/window-state.json句柄确认未重定向，内容哈希与卸载前一致。用户刷新Windows设置确认无Nexus，并从Win+R启动普通cmd执行指定HKCU卸载项reg query，确认找不到注册项。工具上下文仍能读到旧项，HKCU及显式HKU/NtQueryKey均呈相同逻辑名，不能靠逻辑名排除虚拟化；记录为环境视图差异，未手动删键。正常会话结果与Microsoft的HKCU私有写入/合并视图机制相符，不认定为Nexus真实卸载残留。
- **最终状态：程序已卸载，真实用户数据保留，工作区安装包与win-unpacked产物保留。** 本轮未做跨版本升级、卸载后再安装、签名/自动更新或安装版正式通知验收；版本0.0.0和默认图标仍为本地检查点。用户未指定下一任务，若继续使用需由正常Windows会话重新安装；不要未经新需求重复截图任务或清理用户配置。

## EXDEV 原因已收敛：Codex上下文路径重定向，手动启动保存正常（2026-11-14）

- 对照不是“C盘禁止写入”：D盘项目目录与C盘普通Temp的Node/原生Win32重命名均正常，Codex上下文的Roaming/nexus-desktop失败。ACL显示当前用户FullControl，两个卷为NTFS、支持EFS、NtfsDisableEncryption=0；未发现普通写权限不足证据。带Encrypted属性是相关现象，不作为EFS根因证明。
- 关键证据为GetFinalPathNameByHandle：工具上下文看到的C:\Users\ggbond\AppData\Roaming\nexus-desktop及临时文件实际落在D:\WpSystem\[用户SID]\AppData\Local\Packages\OpenAI.Codex_2p2nqsd0c76g0\LocalCache\Roaming\nexus-desktop。探针矩阵：逻辑C→逻辑C报EXDEV，物理D→物理D成功，逻辑C→物理D成功，物理D→逻辑C报EXDEV；失败与逻辑目标路径处理相关。不是Node独有，原生MoveFileEx同样返回Win32错误17；.NET Move能成功不证明原子替换，因为其可走复制删除。
- 安装exe与桌面/开始菜单lnk句柄仍指向真实C盘，安装载荷/快捷方式验收有效。只有应用数据测试路径观察到Codex包LocalCache映射。当前辅助进程GetCurrentPackageFullName返回15700（无包身份），不把“继承包身份”的具体机制当成已证实；未继续追踪重定向层内部实现。
- 用户按问题卡亲自从桌面启动、移动窗口并正常关闭，真实C:\Users\ggbond\AppData\Roaming\nexus-desktop\window-state.json成功保存（version1，x349/y207/1280x860/maximized=false），句柄最终路径确认真实C盘，文件属性Archive而非Encrypted。证据manual-explorer-state.json；因此此前工具启动链EXDEV不能直接判定为普通用户启动下Nexus持久化缺陷。未修改程序算法、用户数据路径、ACL、真实目录加密属性或系统策略。
- 证据集中`.runtime/nsis-acceptance-20261114-140208/`：path-matrix.json、package-identity.json、installed-physical-paths.json、manual-explorer-state.json、filesystem-diagnosis.json和更新summary.json。独立探针已清理。一次涉及探针解密的实验被自动审批拒绝、未执行；安全替代仅对全新空Temp探针尝试加密，返回不支持50，不能用于证明EFS因果关系，未解密真实用户数据。
- 下一步可恢复安装生命周期验收：完整重启恢复、覆盖安装、卸载/数据保留仍需分别验证。保留现有同目录临时文件+rename方案，不为工具上下文特性改成copy+delete。

## NSIS 安装与快捷方式验收；当时发现EXDEV（2026-11-14，原因更新见上）

- 用户授权生成NSIS、检查后交互安装并验收两个快捷方式。`npm run pack:win`成功，release/Nexus-Desktop-0.0.0-x64-Setup.exe，124160467字节，SHA256 3DE4C2472DF110F66A330D6015D28F67CBF8DB38F0251F8C8C4DE8A90CBFB166，NotSigned（预期本地未签名包），有blockmap。PE头、版本、ASAR清单和构建NSIS记录检查通过，无测试/运行数据或提权helper进入载荷。
- 首次构建在复制默认elevate.exe辅助程序时UNKNOWN失败；缓存文件有Encrypted属性，但系统层根因未确认。当前方案强制当前用户安装且无自动更新，按该需求显式增加nsis.packElevateHelper=false后重建通过，未放宽权限或关闭安全设置。构建器缓存重命名另曾报EXDEV并自行copy+delete恢复；不是应用层原子写入方案已经解决。
- 安装前无Nexus卸载项/快捷方式；用户完成可见安装向导，退出0。实际安装在C:\Users\ggbond\AppData\Local\Programs\Nexus Desktop；HKCU卸载名Nexus Desktop 0.0.0，卸载命令带/currentuser。卸载项没有可选InstallLocation字段，通过DisplayIcon、卸载命令和快捷方式确认目录。安装ASAR与产物哈希一致；桌面和开始菜单lnk目标正确、参数空、AUMID均com.nexus.desktop。
- 实际分别从桌面lnk和开始菜单lnk启动，进程路径均为安装目录exe，用户通过问题卡分别确认正常登录页；两次试验窗口均经精确PID+路径核对后CloseMainWindow正常关闭。没有登录业务、创建截图/短链或覆盖/卸载试验。证据集中`.runtime/nsis-acceptance-20261114-140208/`（artifact/installed/desktop-launch/menu-launch/summary等JSON）。程序保持安装，未自动卸载。
- **尚未通过：默认userData窗口配置持久化。** 正常关闭后C:\Users\ggbond\AppData\Roaming\nexus-desktop\window-state.json未出现。对实际安装exe用Playwright只读定位（不覆盖userData）确认isPackaged=true及默认目录正确，捕获“窗口状态保存失败 EXDEV”；证据default-userdata-probe.json。独立普通Node同目录write+rename实验：D盘项目目录成功，C盘该userData子目录返回EXDEV，源临时文件存在、目标不存在。路径解析未显示目录链接，OS/加密/文件系统层精确原因未建立，不能仅凭EXDEV认定代码真的跨盘。此前隔离到D盘的packaged-smoke通过不覆盖这个C盘场景。
- 此条记录当时的待排查状态，最新结论见最上方路径重定向及用户手动启动对照：正常用户默认C盘保存已通过，未修改window-state算法。后续仍按原方案验证重启恢复、覆盖安装与卸载，不修改ECS/数据库。

## Electron 打包配置与应用目录检查点（2026-11-14）

- 开发者完成Main启动分支/userData调整：开发模式在ready前覆盖项目.runtime/user-data，打包版保留默认路径；移除打包即退出，统一启动流程。Agent核对通过后按授权完成机械配置：固定electron-builder 26.18.0及锁文件、electron-builder.yml、build/installer.nsh、pack:dir/pack:win命令、release忽略规则。Windows x64、ASAR、appId com.nexus.desktop、productName Nexus Desktop；保持0.0.0版本与默认Electron图标，未配置证书/自动更新/发布。NSIS带向导，通过customInstallMode强制当前用户，可选目录、桌面/开始菜单快捷方式，默认保留userData，安装后不自动运行。
- Agent补齐安装版固定AppUserModelId，开发版继续process.execPath；smoke/diagnose环境标志仅开发模式生效，避免安装版隐藏/退出或向ASAR写诊断文件。短链边界测试原来按旧注释截取辅助函数，已用现有TypeScript解析函数声明，适配开发者启动段改动；未修改业务转发/窗口算法。
- `npm run pack:dir`成功，实际产物release/win-unpacked/Nexus Desktop.exe。ASAR共51条且无清单外文件，无测试脚本/.runtime/node_modules；Main、Preload、window-state内容与源码一致。开发启动、真实短链IPC边界回归通过；受控启动检查覆盖开发/打包的路径、应用ID和协议注册。
- 新增 `npm run test:packaged-smoke`，直接运行实际打包exe，cwd设为独立测试目录且通过--user-data-dir隔离，验证app.isPackaged=true、appPath为ASAR、登录页/Preload/可信IPC/History刷新、窗口可见、Node全局未暴露及窗口状态写入隔离目录；刻意传开发诊断/冒烟标志仍正常运行。通过，证据`.runtime/packaged-smoke-Su2S2h/evidence.json`，进程关闭正常。测试未访问后端、创建短链/截图或触碰默认用户数据。
- 本轮只配置并验证打包应用目录，未运行pack:win，未生成/安装NSIS安装包，未验证安装/覆盖安装/卸载/快捷方式及正式通知表现。下一步先解释并生成NSIS，再做安装生命周期验收；核心取舍仍开发者先决定，Agent负责机械配置与检查。Electronbuilder安装报告开发依赖树存在moderate审计项，未自动做无关依赖升级；这些npm开发依赖未进入ASAR。

## Electron 短链真实浏览器联动验收（2026-11-14）

- 用户明确授权创建短链并真实打开系统浏览器。`node nexus-desktop/scripts/test-short-link-live.cjs`使用隔离userData、正式Main/Preload/桌面页面、真实localhost:8080与真实shell.openExternal；用户登录、填写已有Key并点击准备按钮后才创建。测试入口仅允许必要管理读取、两次短链创建、禁用本轮第一条记录及三次指定短地址打开，禁止截图等其他机器API，不记录凭据。
- 首轮创建第一条成功，选择短有效期的测试控件定位超时，尚未发送第二次创建或打开浏览器。调整为现有测试使用的Element Plus选择器，使用首轮证据恢复，复用第一条，仅补建第二条。累计两条记录（id85、86），未创建重复样本、Key、Application或截图。分别指向固定允许目标 https://weibo.com/；第一条验证302后通过管理页禁用，第二条10秒自然到期，未修改数据库或系统时钟。
- 最终`.runtime/short-link-live-Qs7B3j/evidence.json`为passed=true、退出0：Node手动重定向检查302/Location匹配/no-store/空正文；禁用后404 SHORT_LINK_NOT_FOUND、自然到期410 SHORT_LINK_EXPIRED均无Location。正式管理页按钮通过真实Preload/IPC触发三次系统打开且全部完成，用户在每次验收面板分别确认目标站跳转、404和410错误表现。Nexus仍在nexus://app/short-links；无测试范围外请求。首轮证据`.runtime/short-link-live-1FfQje/evidence.json`保留供累计创建次数核对。
- 成功提示30秒后隔离Electron正常关闭；外部浏览器标签页由用户管理。两条样本保留为禁用/已过期，不自动删除或恢复。本次是后端契约+真实系统打开+用户视觉确认，未接管系统浏览器读取其网络面板，不扩大为目标站完整业务可用性证明。数据库环境未独立确认，不标为nexus_test验收。
- 独立页面加载、/api/登录列表、/v1/UUID/Hash与短链桌面打开已完成各自真实检查点。下一阶段可讨论安装包；Refresh Token、通知点击等仍是独立后续范围，不因这次通过而推定完成。

## Electron 打开短链 Main/Preload 检查点（2026-11-14）

- 开发者先写的协议 /s/ 分支混用了IPC事件与返回值。经明确请求，Agent删除该分支并恢复protocol.handle仅接收request，新增专用 `short-link:open` IPC及 `nexusDesktop.openShortLink(shortCode)` 窄桥和可选能力类型。来源复用现有可信窗口/主框架/nexus地址校验；短码仅允许长度恰好12的ASCII字母数字（含末尾换行也拒绝），Main构造固定 `http://localhost:8080/s/{shortCode}` 后调用shell.openExternal。Promise完成返回opened，仅表示系统交接；同步/异步失败均返回OPEN_FAILED，非法输入INVALID_REQUEST，来源错误FORBIDDEN。
- `npm run test:short-link-boundaries`通过。隔离Electron加载正式Main/Preload，通过真实IPC验证23种非法输入、固定地址与大小写保留、系统打开同步失败/Promise拒绝/失败后恢复、Promise未完成不提前返回opened、同nexus地址的第二窗口被拒绝、协议/s/请求404且不触发打开。仅shell能力使用替身，未打开实际浏览器/访问后端/创建短链或截图；无未处理拒绝。来源辅助函数另有隔离检查覆盖子框架、不同URL、销毁/缺失窗口和失效框架。证据 `.runtime/short-link-boundaries-LeHoYx/evidence.json`。API转发24项受控回归、Main/Preload语法、Vue源码与E2E类型检查通过。
- 页面接入现已完成并由Agent独立复核（2026-11-14）：ShortLinkOpenButton用于桌面调试台创建/查询结果与管理页桌面/窄屏入口，只通过桥传shortCode；等待禁用、失败可重试，旧桥缺能力与IPC拒绝均提示，不回退桌面网页导航。桌面构建单独注入HTTP公开短地址，显示/复制与Main打开目标一致；Web保留原链接行为。重建与源码/E2E类型检查通过，真实Electron界面/IPC模拟后端与系统打开能力测试通过（`.runtime/short-link-ui-HuQt4h/evidence.json`），来源边界复跑通过（`.runtime/short-link-boundaries-HmRzjt/evidence.json`），相关浏览器测试12/12通过（shortLinks 7 + desktopShortLinks 2 + playground 3）。未发现本次范围内的阻塞问题，未修改核心实现。
- 不把opened解释为短链有效，不直接打开originalUrl，不放宽全局新窗口/外部导航策略。以上检查未实际打开系统浏览器或创建真实短链；正式浏览器与后端302/404/410的联动仍未验收。仍按本地8080，之后再处理安装包。

## 当前状态速览与阅读规则（2026-11-14 整理）

> 本节汇总下方已记录的检查点，不是本次新增测试结果。下方历史记录原文保留；其中“尚未实现 / 待验收 / 下一步”仅代表当时状态，不能直接当作当前待办。按同一事项的更新记录及实际代码判断，尤其注意“多端规划”标题日期之下还包含 2026-11-13 的更新。

### 已完成及验证范围

- **真实截图 → PNG → 系统通知**：2026-11-13 的“真实截图+通知联动自动验收通过”记录已覆盖早期“联动尚未验收”；正式 Main/Preload/Vue/Store 连接真实后端通过，开发者明确确认看到通知。不是仅受控通知测试通过。
- **已知 Job ID 的接手重试**：2026-11-13 的“真实接手重试验收通过”已覆盖早期 `pendingHandoffJob` 未解决/待验收状态；保留原 Job ID，只重试接手，不重复 POST 创建。暂停恢复亦已有真实验收记录。
- **独立构建与固定后端转发**：页面加载、`/api/` 登录/列表、`/v1/` UUID/Hash 的检查点见下方 2026-11-13 至 2026-11-14 记录。UUID/Hash 已完成真实闭环，覆盖匿名 401 与机器 ApiKey 身份隔离；不扩大为所有机器接口验收。
- **验证边界**：独立入口及转发改动后的 PNG、响应头与 Retry-After 是受控验证；没有重新执行真实截图到通知的完整链路。历史真实验收与最新改动的验证范围应分别报告。数据库环境未独立确认的验收不标为 `nexus_test`；定向测试不等同最新全量回归。

### 仍开放的事项与执行约束

- `creationUncertain`：未取得确认 Job ID 时已有防重复提交保护，但恢复方案仍未实现，不能直接重发创建 POST。
- 通知点击唤起/跳转、Refresh Token 与多设备长期会话仍待设计/实现。`/s/` 公开跳转策略及安装包在最近已记录检查点仍待设计；继续前应核对工作区的新改动。
- 不为清理旧待办而重复创建真实截图任务。已有验收记录足以关闭对应历史待办；新增验证须有具体未覆盖问题及明确授权。
- 本次只整理交接文档，未运行测试、修改代码、提交、推送或部署。编辑前工作区已有 `nexus-desktop/main.cjs` 修改，未纳入本次验收结论，保持不动。

## 检查点与历史记录（原文保留）

## Electron /v1/ UUID 与 Hash 真实验收（2026-11-14）

- `node nexus-desktop/scripts/test-utils-acceptance.cjs` 在隔离userData中运行正式Main/Preload/构建Vue，连接真实localhost:8080，不启动Vite。用户在窗口登录、填写已有完整API Key并点击测试准备按钮后，脚本通过真实Playground按钮各调用一次UUID与Hash。
- 无凭据UUID请求401；有效Key的UUID GET与Hash POST均200/SUCCESS。UUID符合v4结构；固定含中文输入的SHA256与Node按UTF-8独立计算一致，页面显示200及摘要。Main观察记录确认两个接口各一次ApiKey请求、无机器Bearer请求；Renderer HTTP/HTTPS请求0，测试范围外请求0，不调用截图/提取/短链API。成功提示30秒后关闭窗口。凭据与响应正文不保存，仅记录路径、方法、身份类型、状态及校验布尔值；正常调用会使用现有配额/Usage。
- 测试入口 utils-acceptance-main.cjs 限定固定后端与UUID/Hash，阻止其他机器接口及同接口第二次带Key调用；不修改正式业务代码。
- 当前 /api/ 登录/列表与 /v1/ UUID/Hash 已完成独立真实闭环；PNG/Retry-After由上一检查点受控验证，未重复真实截图验收。/s/公开跳转策略与安装包仍待设计，不扩大为全部机器接口端到端验收。

## Electron /v1/ 转发检查点（2026-11-14）

- 开发者将 /api/ 与 /v1/ 转发合并为 forwardBackend，分流位于静态GET限制之前，两个分支均返回Response。预算分别为8秒/1MiB与30秒/8MiB，共用64KiB请求上限。请求头仅复制Content-Type/Accept/Authorization；响应保留Content-Type、截图两个标记与Retry-After，统一no-store。
- 修正分支顺序、缺少return、前缀边界、响应头方向及重复实现后，语法与24项受控处理器检查通过：两前缀四方法、固定目的地/查询/body/身份头、两套超时参数与响应大小边界、PNG逐字节一致与标记、429 JSON与Retry-After、静态页面与排除路径。使用模拟后端；未创建真实截图任务，不能表述为 /v1/ 真实调用验收。临时复查入口为忽略目录 nexus-desktop/.runtime/check-v1-forward.cjs。
- /api/真实登录验收见下方前一检查点；重构后本轮仅受控回归。下一步可做独立桌面 /v1/ UUID/Hash 的真实验收，无需重复真实截图任务；/s/公开跳转与安装包尚待设计。

## Electron 管理 API 登录转发验收（2026-11-14）

- 开发者实现协议处理器内 `/api/` 固定转发至 `http://localhost:8080`：GET/POST/PUT/DELETE、查询与请求体保留、请求/响应头允许列表、64KiB 请求与 1MiB 响应上限、8秒整段超时、禁止自动跳转。后端业务错误保留，连接/超限/超时转换为 JSON 错误，不回退 HTML。调用方取消覆盖读取请求体、等待后端、读取响应，监听器 finally 清理；协议入口已复用 isTrustedAppUrl。Agent 的受控处理器与取消边界检查通过，未代写业务转发实现。
- `node nexus-desktop/scripts/test-login-acceptance.cjs` 使用隔离 userData、正式 Main/Preload/构建后的 Vue 与真实本地后端，不启动 Vite、不记录密码、令牌或响应正文。用户在窗口登录后验收通过：无效登录体400、匿名管理401、真实IPC来源校验ok、真实UI登录200、Bearer Application列表200、窗口可见、Renderer HTTP/HTTPS请求为0。证据保存在忽略目录 `.runtime/login-acceptance-*/evidence.json`，仅记录方法、路径、状态和是否携带Bearer。成功横幅显示30秒后自动关闭；登录会话仍是现有内存方案，不代表刷新续期通过。
- 首次连接失败由后端未监听8080引起：后端因 management 8081 与 Codex 监听冲突而启动失败。开发者将本地管理端口改为8082后报告启动成功，本轮真实8080请求已验证；未停止Codex或修改业务8080转发地址。
- 当前验收范围仅 `/api/` 登录/列表；`/v1/` 转发、`/s/` 跳转策略及安装包仍未完成。下一步继续由开发者先设计/编写机器API转发，特别确认JSON/PNG响应头、体积及耗时限制；不重复真实截图任务验收，不修改ECS、数据库或现有截图业务逻辑。

## Electron 独立页面加载（2026-11-13）

- Main 已注册 nexus 标准/安全/Fetch 协议，从 dist/renderer 提供本地产物；开发者实现目录边界校验、已知固定/动态页面 History 回退、isTrustedAppUrl 及 IPC/导航适配。API 和缺失资源不回退 HTML。开发者确认真实重启后登录页刷新正常。处理器直接调用及来源/IPC/导航模拟事件检查已通过，不能等同完整安全端到端验收。
- 新增 `npm start`（scripts/start.mjs），只启动 Electron，不启动 Vite；需先 `npm run build:renderer`，缺失入口时明确失败。新增 `npm run test:standalone-smoke`，同一启动器使用隔离测试入口/用户数据和正式 Main/Preload，阻断 HTTP/HTTPS。类型检查与构建通过；真实隐藏 Electron 登录表单、History 重定向、无 Node 全局变量检查通过，HTTP/HTTPS 请求为零。已有 Vite 未被关闭；阻断网络证明页面不依赖它。初次受限环境出现子进程 launch-failed/code=49，允许子进程执行后通过，未更改浏览器安全配置。普通可见 npm start 尚未由开发者人工确认。
- 现阶段仅独立页面加载；Main 固定后端受限转发尚未实现，登录不可据此宣称通过。下一步由开发者先设计/编写转发，暂用 localhost:8080，之后才处理安装包。旧 dev 启动器仍会启动 Vite，但 Main 固定加载 nexus://app/；旧截图验收脚本的开发代理假设不应当作当前独立入口验证。不要重复真实截图验收，不修改 ECS、数据库或截图业务。

## Electron 开发与窗口持久化检查点（2026-11-11）

- nexus-desktop 复用 nexus-console 的 Vue 与 Vite，固定回环 5178，代理到本地 8080；开发者确认桌面真实登录和 Application 列表加载成功。独立构建和正式转发入口、续期尚未实现；任务完成通知已于2026-11-13实现并验收，见下方最新检查点。
- 普通启动空白曾由启动器 windowsHide:true 触发显示差异，移除后开发者确认界面正常；保留 GPU 与浏览器安全设置。Playwright 启动绕过开发启动器，不能单独证明普通启动通过。
- 开发者编写 window-state.cjs 与 Main 接入。窗口配置通过同目录临时文件替换保存；screen 工作区校验不通过时回退主屏默认居中。当前仅支持启动时布局变化，不涵盖运行中热拔屏和小于800x600的工作区。
- Windows 150%缩放下存在创建/回读几何偏差；原生窗口对照已定位创建尺寸与矩形转换均参与，但未证明具体底层源码修复点。创建后 setBounds 候选不能通用收敛，未采用。详细证据与限制见 nexus-desktop/README.md。
- 采用 will-move / will-resize 标记，关闭时以 resolveWindowBounds 目标为基准：不操作保留目标，只移动更新坐标，缩放更新普通矩形，独立保存最大化标记。正式启动三轮不操作配置完全不变；真实移动后宽高仍1284x864；右下角缩放后保存800x601；最大化关闭保留普通矩形，重启最大化、还原后再关闭恢复maximized=false。实际操作由开发者完成并反馈，Agent逐步核对文件。最终配置为x=4、y=31、800x601、maximized=false，证据在忽略的.runtime目录。
- 该策略阻止保存值累积，未修复底层创建偏差；Windows贴靠、跨DPI拖动、键盘布局操作尚未验收。
- 截图另存为基本闭环完成：开发者实现Preload/contextBridge、受限screenshot:save处理器及Vue能力分流。前端类型检查、现有浏览器下载定向测试1/1通过；开发者确认真实Vue按钮取消安静结束、保存出现成功提示，Agent核对产物80037字节、1280x720、PNG签名/CRC/IDAT解压正常。`npm run test:save-boundaries`补验Main并发BUSY、真实目录写入失败后再次保存成功且PNG字节一致；Playwright使用真实桥接/IPC/文件系统，仅控制对话框返回并隔离userData，不涉及后端。非信任来源等其他边界仍未运行验收。下一候选任务通知，代码继续由开发者先写，Agent解释与审查。

## 多端规划（2026-11-10）

- 真实接手重试验收通过（2026-11-13）：`node scripts/test-resume-acceptance.cjs --handoff`在隔离Electron连接真实8080，用户登录填入Key/URL，手动关闭VPN并点击测试浮层准备按钮后才提交。仅创建1个任务；测试入口使首次start返回BUSY，确认待接手Job ID保留且状态请求为0；脚本随后点击“重试接手”，第二次start放行正式Main，沿用原Job ID、清理pending记录/次数，未重复POST。5次真实状态查询200并到SUCCEEDED，一次PNG获取200，79902字节、解码宽1280，长度和SHA-256匹配后端元数据；通知attempts=1/shown=1/errors=[]（本轮用户只明确确认拿到截图，不推断本轮通知人工可见性）。Renderer旧查询=0。证据`.runtime/handoff-acceptance-rf0HxU/`，用户报告完成并拿到截图，脚本退出0且已清理关闭隔离窗口。测试不操作VPN、不修改正式业务代码、不取消后端任务。该脚本已为默认/notify模式也加显式准备按钮，避免粘贴Key后立即提交。当前剩余creationUncertain无确认Job ID的恢复尚未实现；三次上限与迟到返回已由35项定向测试覆盖，无需重复创建真实任务验收这些分支。本条更新下方“真实接手重试待执行”状态。

- 最新接手重试检查点（2026-11-13）：开发者实现retryPendingHandoff(apiKey)、retryingHandoff、handoffRetryCount与3次手动上限，Key trim后发送；不重复POST，仅重试start。已有同Job非IDLE推送时优先本地确认，先于次数上限/Key/桥检查；返回及异常后重新判断是否已接手，不写回返回快照；成功清理待接手记录、错误和计数。提交/清理/恢复与重试互斥，取图不被重试锁阻止。Agent未改Store，仅接待接手Job ID、次数、重试按钮/加载状态和确认已接手入口，并补齐界面禁用条件。源码/E2E类型检查通过，桌面展示与根部订阅35/35通过（真实Vue/Store、模拟桥/HTTP）。覆盖并发去重、只创建一次、3次上限/第四次直接调用被阻止、上限后收到推送仍可无Key/无桥本地确认、空Key不计数、异常后手动再试、推送早于ok/reject/FORBIDDEN时保留终态与PNG、返回先到不应用快照、旧请求不污染新待接手记录。该重试流程的真实Electron受控验收尚未执行；creationUncertain无确认Job ID的恢复仍未实现，次数耗尽且无成功推送时保留记录等待人工核对。本条更新下方pendingHandoffJob未解决的历史状态。

- 真实截图+通知联动自动验收通过（2026-11-13）：`node scripts/test-resume-acceptance.cjs --notify`运行正式Main/Preload/Vue/Store与真实8080，用户在隔离窗口登录并填写Key/URL，未注入状态失败或模拟通知；仅包装真实Notification观察事件。创建POST=1，4次Main状态查询200，同一Job ID经历QUEUED/RUNNING/SUCCEEDED；结果获取=1、200，PNG为80064字节、解码宽1280，长度和SHA-256匹配后端元数据。通知attempts=1、shown=1、errors=[]；Renderer旧查询=0。证据`.runtime/screenshot-notification-acceptance-Y9JiQN/`，完成后保留窗口45秒再清理测试登录存储/本地跟踪并正常关闭。用户随后明确确认看到了本次真实任务通知，技术记录与人工可见性均通过；此前受控任务横幅也已确认。数据库环境未独立确认，不标记为nexus_test数据库测试；未修改正式业务代码。本条更新下方“联动尚未验收”的历史状态。

- 最新通知检查点（2026-11-13）：开发者实现Main进程内Job ID去重的任务完成通知，SUCCEEDED/FAILED在推送快照后触发，尝试前登记且stop不清集合；不等待PNG、不新增Preload/IPC。Agent隔离Electron受控验收通过：同Job ID跨stop/re-track不重复，RUNNING/EXPIRED不通知，支持检查/构造/show同步异常及异步failed、不支持均不改TERMINAL/SUCCEEDED且无未处理拒绝。测试入口最初直接重定义Electron.Notification失败，已改成仅为目标模块require注入独立门面；未改开发者业务代码。真实Windows通知起初因DisabledForUser失败，用户打开通知后系统读回Enabled，重测show=1、failed=0，用户截图确认横幅出现。证据`.runtime/notification-boundaries-wpSYVg/`；脚本`node scripts/test-notification-boundaries.cjs --native`保留窗口45秒后关闭，使用受控任务、不访问后端。去重范围仅本次Main进程，失败不自动重试，通知点击唤起/跳转未实现；真实后端截图+通知的一体化验收尚未单独执行。pendingHandoffJob恢复和creationUncertain仍未解决。本条更新下方历史“系统通知待实现”状态。

- 真实暂停恢复验收已通过（2026-11-13）：Agent运行`nexus-desktop/scripts/test-resume-acceptance.cjs`，隔离Electron userData，使用正式Main/Preload/Vue/Store，用户在隔离窗口登录并填写真实Key与URL；真实8080创建POST仅一次。测试入口只让Main第一次状态查询返回503，确认PAUSED和继续查询按钮，随后自动恢复，所有请求沿用同一Job ID。后续4次真实查询200并到SUCCEEDED，真实PNG结果200且仅获取一次；79999字节、解码宽1280，长度与SHA-256匹配后端元数据。Renderer旧轮询/结果请求数0。证据在`.runtime/resume-acceptance-wVsZde/`。脚本成功后自动清理本地跟踪和测试登录存储、关闭隔离窗口（用户因此询问窗口消失，已核实正常退出码0）。真实后端任务按原生命周期保留，不重新创建；尚未确定当前后端使用的数据库环境，不将本次验收标记为nexus_test数据库测试。正式业务实现未改动；pendingHandoffJob恢复、creationUncertain处理和系统通知仍待实现。本条更新下方“真实暂停恢复待验收”状态。

- 最新恢复检查点（2026-11-13）：开发者人工验收清理后卡片/图片消失、再创建Job ID变化且新图正常。随后实现Store.resumeCurrentTask(apiKey)、resuming/resumeError和提交/清理互斥；仅PAUSED恢复，复用start桥传入原任务Job ID与页面Key，不重新POST创建，返回快照不写回Store。applySnapshot的图片清理仅在任务ID变化执行，恢复错误在任务变化或离开PAUSED时清除。Agent未改Store业务代码，仅接继续查询/恢复中按钮、错误和Key说明，补上界面互斥。源码/E2E类型检查通过；桌面展示及根部初始化定向28/28通过（真实Vue/Store，模拟桥/HTTP），包含无latestJob的首次暂停、重复恢复去重、与提交/清理互斥、返回先到/终态推送先到、恢复失败和异常后重试、成功推送后异常不误报、旧任务异常隔离及恢复返回前自动取PNG且重复终态不重取。首轮测试27/28，失败为脚本在首次接手完成前注入暂停；补齐等待后完整重跑28/28。真实Electron暂停→恢复→PNG仍待人工验收。恢复未接手任务pendingHandoffJob、创建结果不确定creationUncertain和系统通知仍未实现。本条为下方历史状态的更新。

- 最新清理检查点（2026-11-13）：开发者实现Store.clearCurrentTask、clearing/clearError及提交/取图入口保护；停止返回值仅确认、不写回快照，IDLE推送复用applySnapshot清图与使旧请求失效，失败保留当前状态，已收IDLE后Promise拒绝不误报失败。Agent仅接清理按钮、提示和界面禁用条件，未改Store业务代码。源码/E2E类型检查通过；desktopScreenshotDisplay与trackingInitialization共20/20通过（真实Vue/Store，模拟桥/HTTP），包括IDLE先到和返回先到、失败/异常后重试、已收IDLE后异常、取图中清理拒绝迟到PNG，以及清理完成后第二次POST获得不同Job ID并预览新PNG。真实Electron清理后再创建仍待开发者人工验收。未解决pendingHandoffJob或creationUncertain：清理不解除其防重复提交保护；恢复接手和系统通知仍待实现。本条更新下方此前“页面清理任务尚未接入”的历史状态。

- 最新取图检查点（2026-11-13）：开发者确认真实Electron切换Applications返回后状态恢复并到达完成；随后通过真实Preload/IPC收到80105字节ArrayBuffer及no-store。开发者实现Main的screenshot-result:get（独立取消、8秒超时、流式5MiB上限、PNG签名、run身份校验）及Store自动取图、失败手动重试、请求版本失效。Agent本轮仅接页面加载/重试/预览模板与预览保留说明插槽，未改Store业务代码。源码/E2E类型检查通过；桌面展示、根部订阅和选定Web截图回归共20/20通过，真实Vue/Store配模拟桥/HTTP；新增验证含重复成功推送及直接调用去重、失败不自动重试、手动重试成功、PNG真实解码、离页预览恢复不重取，以及清理后同Job ID旧成功/失败/异常和finally不覆盖新请求。2026-11-13开发者随后反馈真实Electron自动取图、预览、离页返回、放大、取消保存和保存全部正常，并确认返回页面不会重新获取图片；这是开发者人工验收反馈，非Agent新增端到端测试。恢复接手、页面清理任务和系统通知仍未接入。下方2026-11-12条目为此前检查点。

- 最新Renderer检查点（2026-11-12）：开发者已实现snapshot Store、根部订阅初始化、App清理和桌面提交action/页面分流。后端创建成功即保留pendingHandoffJob，Main接手失败保留Job ID；创建结果不确定以creationUncertain阻止重复POST；start返回值不覆盖推送快照。经开发者指定，Agent补上Playground桌面状态展示分支，并将按钮文字统一读取requestLoading。源码/E2E类型检查通过；trackingInitialization.spec.ts与desktopScreenshotDisplay.spec.ts受控桥接测试9/9通过，覆盖首次等待、离页推送返回恢复、接手失败/IPC异常、迟到start返回不覆盖新终态、根部读取和清理。测试使用真实Vue/Store、模拟桌面桥与HTTP，不代表真实Electron离页验收。桌面不再运行旧Renderer轮询；恢复接手、清理当前任务、PNG结果获取和通知尚未接入，真实离页恢复待开发者验证。

- 2026-11-12 Main单任务跟踪已由开发者实现。`npm run test:tracking-boundaries`隔离Electron/真实IPC/受控Main fetch验证去重、串行、停止后迟到响应隔离、8秒超时PAUSED保留旧状态、恢复和三个终态停查通过。开发者随后在真实桌面Console创建任务并交给Main，截图确认QUEUED→RUNNING→SUCCEEDED，跟踪最终TERMINAL、无查询错误。前次失败任务已通过stop清理后重新创建；此前VPN为开发者反馈，未将其认定为确证根因。每条日志出现在VM69与VM194两处，说明残留两个Console订阅，不能据此认定重复轮询。下一步接Renderer Store和页面恢复，替换桌面旧轮询，最后接通知；离页/最小化端到端仍待验收。详见nexus-desktop/README.md。

- 2026-11-12 截图跟踪边界已确认：第一版只跟踪一个任务，切换页面后保留跟踪和状态，桌面需支持应用运行时的后台完成通知；点击×退出程序后不再本地跟踪，不引入托盘驻留。后端已接受的任务不因退出客户端而取消。状态/跟踪协议及Web与Main监控分工仍处于设计，尚未改动轮询实现；代码由开发者先写。

- 开发者确认 Mobile MVP 为只读：登录、Application 列表与详情、Quota 和 Usage；移动端沿用 Web 按 API Key 查看 Usage。先开发 Electron；随后 uni-app 验证顺序为 Android → iOS → 微信小程序，同仓维护独立客户端。后续移动端和小程序允许创建或轮换凭据，一次性展示和复制参考 Web；这些写操作不属于只读 MVP。
- 登录目标包括 Refresh Token 和多设备长期会话；当前尚未实现，设备会话、续期、撤销和安全存储方案待设计。
- 管理 API 暂不引入版本前缀，长期版本兼容策略待定；多端网络与部署入口暂不讨论、待定。共享契约包是否以及何时抽取待讨论，不预设实施。
- Electron 关注任务通知和截图下载，安全存储的具体需求待明确；不以通用文件工作流为目标。域名 myxiaomiao.com 正在备案，目前先做本地自用，备案完成后再继续服务器域名与 Nginx 入口工作。

## Webhook 管理与真实投递闭环（2026-11-09）

Webhook 管理后端已接通现有前端契约：Bearer JWT 下按 Application 归属查询、创建、更新 URL/启停和轮换 Secret。配置与首个 AES-GCM 加密 Secret 同事务提交，Application 唯一约束处理并发创建；轮换携带 `currentSecretVersion`，在锁内比较版本并原子切换 CURRENT Secret，旧 Secret 保留一小时 GRACE。完整 Secret 只在创建或轮换响应中返回。保存 URL 时预检 HTTPS、公网域名和全部解析地址，实际投递仍在连接时重新校验。

验证包括后端编译、HTTP 契约与 URL 策略 8/8、真实 MySQL 管理事务 3/3，以及“管理 Service 创建配置和 Secret → 截图任务终态 Outbox → 真实本地 TLS 接收端先 503 后 200 → 重试保持 eventId 和请求体 → HMAC 验签”1/1。前端构建、源码/E2E 类型检查通过，Webhook 页面 Playwright 4/4 通过。隔离 MySQL 容器已删除，原 Compose 数据未改动。

2026-11-10 开发者反馈：已通过真实浏览器连接新后端完成人工验收。未提供逐项验收结果及所使用的部署环境；是否已将新代码打包并更新本地 Compose 仍待确认，不将本次反馈推断为 Compose 更新或真实投递验收。

## 完整 Compose 重建验证（2026-11-09）

按开发者请求，使用 base + monitoring 配置执行不带 -v 的 `down` 和 `up -d --no-build`。五个 Nexus 服务容器 ID 均变化，MySQL、截图、Prometheus、Grafana 四个卷保留；用户数仍为 2、Prometheus 重建前历史样本一致、Grafana 卷标记保留后精确删除。目标 up、Grafana 登录页 200、前端未认证 UUID 401，FlowOps 十个容器 ID 不变。Grafana API 验证 14 面板、数据源 OK，HTTP 状态面板查询 200 且 3 组数据；尚未由 Agent 观察开发者浏览器的视觉显示。截图卷存在，但本轮未重测截图文件，已有结果按业务规则过期。详见 tools/observability/docker/README.md。

## Docker 监控检查点（2026-11-09）

开发者授权完成 Prometheus/Grafana Docker 接入。可选覆盖文件 `tools/docker/compose.monitoring.yaml` 使用 Nexus 独立监控网络，Grafana `127.0.0.1:13000`、Prometheus `127.0.0.1:19090`，FlowOps 的 3000/9090 保持使用。后端管理 8081 不发布主机端口，但监听容器所有网络接口，其他默认网络容器也可能访问；仅供本地学习。旧原生监控数据不迁移。

使用本机已缓存固定摘要的 Prometheus 3.5.0 / Grafana 12.1.1，生成独立本地明文管理员密码文件；数据卷和首次启动细节见 `tools/observability/docker/README.md`。真实 UUID 成功后 Prometheus HTTP 200 计数 0→1；Grafana 数据源健康、14 面板加载且实际查询成功。监控容器重建后 Prometheus 历史样本及 Grafana 数据均保留，临时验收文件夹已删除；浏览器曲线仍待开发者观察。监控验收创建一组新用户/Application/Key 保留在本地库，未输出凭据。

不要使用带 `-v` 的 Compose down，避免删除现有 MySQL、截图及监控卷。Grafana 管理员密码只在初始化时生效，修改密码文件不会重置已初始化的账户。ECS 和 FlowOps 未修改。

## 当前阶段（2026-11-08）

Docker Compose 和 UTC 时间调整已完成本地验证。经开发者明确授权，旧 Compose 实验数据库和截图卷已重建，原秘密文件保留；新镜像和 UTC 配置已应用到 nexus-docker-compose-lab，ECS 未修改。

开发者随后报告：异步截图任务 SUCCEEDED，Nexus 容器重建后 ID 改变，重新下载的 PNG 与重建前 SHA-256 一致。这是开发者提供的验收结果；结果文件仍按业务规则过期，不是永久归档。

## 重要约定

- 内部绝对时刻统一 UTC，API 返回明确偏移，控制台使用北京时间；Usage 自然日按北京时间，配额周期固定 720 小时。
- Docker Compose 使用独立数据库卷与截图卷；保存本地秘密文件，不提交 data、密码、API Key 或令牌。Webhook 租约仍由数据库 UTC 时间判定。
- Webhook 管理 Controller/Service 已实现并接入前端轮换版本契约；代码级管理到真实 HTTPS 投递闭环已验证，开发者反馈已完成真实浏览器连接新后端的人工验收；本地 Compose 更新状态待确认。
- 后端完整回归 638/638、前端模拟浏览器回归 103/103；隔离 Compose 与真实本地 Compose 的登录、Key、UUID、UTC 和容器重建验证见 docs/UTC_TIME_DESIGN.md。
- 开发者希望先说明每一步的目的、验证内容及遗漏的影响，机械工作由 Agent 完成，关键结果用于教学。

## 下次会话

开发者准备关机。下次开机后先只读检查 Docker Desktop 和 nexus-docker-compose-lab 的启动状态，再根据实际状态处理。不要修改 ECS 或清理数据，除非获得明确授权。先读 AGENTS.md、docs/UTC_TIME_DESIGN.md 和 tools/docker/README.md，以仓库与实际状态为准。
