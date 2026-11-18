# Nexus Mobile

基于普通 uni-app、Vue 3 和 HBuilderX 的移动管理控制台。目前完成安卓调试基座上的首版管理功能验收，复用 Nexus 现有管理接口，不新增后端接口或数据库表。

## 已完成

- 登录、注册、退出，以及关闭进程后恢复登录。访问令牌只保留在内存；刷新令牌通过 Android Keystore 加密保存。登录仍受服务端当前 24 小时绝对会话期限约束。
- Application 列表、创建、详情、改名、启停和删除。
- API Key 列表、创建、详情、改名、启停、轮换和删除。完整凭据仅在创建或轮换后于当前页面临时展示，离开页面即清除。
- 配额、Usage 时段统计、北京时间自定义区间、每日图表、接口分布和调用记录分页。
- 统一主题、七个 `nexus-*` 基础组件及配额、密钥展示、用量统计业务组件。
- 登录页橘白猫星球：安卓使用本地 Three.js 资源，支持手指旋转与回到正面；小程序保留静态海报。

## 本地运行

1. 在 HBuilderX 中导入本目录，使用 Vue 3 的普通 uni-app 编译方式。
2. 单独启动 Nexus 测试后端。当前 `config/backend.js` 指向 `http://127.0.0.1:18086`，请勿连接日常或生产数据库进行测试。
3. 安卓手机开启 USB 调试并授权电脑，通过 `adb reverse tcp:18086 tcp:18086` 将手机端口转发到本机测试后端。
4. 在 HBuilderX 选择“运行到手机或模拟器”，使用安卓调试基座运行。

H5 仅用于界面预览；真实登录目前仅支持安卓。HBuilderX 的项目 AppID、小程序 AppID、正式签名和发行配置需要在对应平台阶段另行配置。发布前需要将测试服务地址替换为正式 HTTPS 地址，并重新验收。

## 代码如何组织

```text
pages/         页面、表单校验和业务交互
components/    基础控件与重复的业务展示
styles/        品牌主题与移动布局
api/           管理接口契约及 uni.request 传输
auth/          会话、续期、退出与安卓安全存储
utils/         时间区间与北京时间展示
graphics/      登录星球的 renderjs 适配
static/        图片、品牌资源及离线星球包
scripts/       回归检查与星球资源构建
```

页面通过 `api/management.js` 调用会话模块，再经传输层访问现有后端。管理请求的路径与方法有明确限制；遇到 401 不自动重放写操作。写入结果不确定时先核对状态，避免盲目重复创建、删除或轮换。

从 Web 迁移时复用业务字段、状态含义和确认规则，将宽表格改为单列卡片与独立页面；模板使用 uni-app 的 `view`、`text`、`input`、`button` 等组件。基础组件通过 props 接收数据，通过自定义事件通知页面，由页面负责业务调用。

## 验证

使用 Node 执行：

```sh
node --test scripts/test-auth-session.cjs scripts/test-management-contracts.cjs
```

当前 48 项会话、传输和时间契约检查通过。安卓与独立测试后端的真实流程已验证登录、管理操作、轮换、配额与 Usage、进程重启恢复、退出后重启保持退出；临时数据已清理。

`scripts/test-ui-components.cjs` 和 `scripts/test-management-ui.cjs` 依赖相邻 `nexus-console` 中安装的 Playwright。先启动 HBuilderX Web 预览，默认测试地址为 `http://127.0.0.1:5186/`，也可通过 `NEXUS_MOBILE_TEST_BASE_URL` 指定。管理页面测试使用模拟响应，不代表 H5 支持真实登录。

星球离线包已随源码提供；需要更新时，`scripts/build-login-planet.cjs` 从相邻 Web 项目读取场景源码并构建 App/Web 资源，构建后需重新验证安卓显示与交互。

正式签名 APK 的安装与发行验收、iOS 真机、小程序安全会话与真机验收尚未完成。编译通过不等于这些平台已验收。

`unpackage/`、`.hbuilderx/`、`.runtime/` 和 `cache/` 为本机输出，不提交到 Git。
