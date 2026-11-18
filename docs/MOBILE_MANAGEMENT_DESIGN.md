# 安卓管理控制台的页面迁移与真实接口

2026-11-18。首版管理范围为登录/注册、Application、API Key、配额和Usage。普通uni-app Vue3沿用Nexus品牌和现有后端契约，用适合触摸的卡片、表单、确认弹窗和日期选择替换Web布局；没有新增“安卓专用”的业务接口、数据库表或基础设施。

## 页面与责任

| 页面 | 已接入的能力 |
| --- | --- |
| `pages/login/login.vue` | 真实登录、恢复已有会话、密码显示切换、进入注册；保留现有离线3D猫星球。 |
| `pages/register/register.vue` | 昵称/邮箱/密码/确认密码、失焦和提交校验、真实注册后回到登录。切后台/离开时清空密码。 |
| `pages/applications/applications.vue` | 所属应用分页、创建、进入详情、列表配额、退出及确认。 |
| `pages/application-detail/application-detail.vue` | 概览/API Keys；应用改名、启停、按名称确认删除；配额查询；Key分页和创建后一次性展示。 |
| `pages/key-detail/key-detail.vue` | 所属Key详情、改名、启停、强确认删除、带Public ID比较条件的轮换；密钥信息/调用统计。 |

API文档、开放API调试、短链接、截图、Webhook等不是这一轮管理端迁移的范围。iOS和小程序的安全登录适配仍独立处理；H5继续只提供界面预览。

## 如何从Web迁移

复用Web的业务术语、字段、状态和确认规则；没有复制Element Plus组件进移动端。模板改用uni-app的view/text/input/button/picker/scroll-view，基础外观由 [移动端基础组件](design/NEXUS_MOBILE_COMPONENTS.md) 和 `styles/theme.css` 提供。业务重复部分提取为 `application-quota`、`key-reveal`、`key-usage`，确认和表单使用 `nexus-dialog`。

Web的侧栏和宽表格改成独立页面、页内标签与单列卡片。名称优先，不外显数据库内部ID。日期、Key预览和Public ID使用明确标签，长字符串换行；每日柱图可横向浏览。主要触控区域至少44px，避免把桌面图标按钮缩小后搬到手机。

Web Router跳转改用uni.navigateTo/navigateBack/reLaunch；路由只传资源ID。父页面onShow重新读取资料，所以编辑或删除后返回能看到真实状态。Application详情按当前用户的应用列表接口加applicationId筛选；Key详情按所属应用的Key列表加apiKeyId筛选，不为客户端增加单条查询接口，也不先拉全量分页再找记录。

组件props负责接收值，press/输入事件通知页面；页面拥有校验、接口调用和忙碌状态。数据流为：

```text
页面输入和确认
  → api/management.js 组装现有HTTP契约
  → auth/session.js.management 校验路径/方法、检查会话、必要时续期
  → api/transport.js → uni.request → 独立测试后端
  → 后端Bearer身份、资源所有权、业务服务和MySQL
  → 响应回到当前页面 → 基础组件展示
```

AT只由会话模块持有，RT继续由Android Keystore加密存储。页面不自行保存认证凭据。管理接口与使用API Key的机器接口保持分离；手机页面不替用户向机器接口附带Bearer。

## 复用的HTTP契约

| 操作 | 接口 |
| --- | --- |
| 注册 | POST `/api/auth/register`，成功201；不附Bearer。 |
| 登录/续期/退出 | POST `/api/auth/login`、`/refresh`、`/logout`，沿用JSON认证协议。 |
| 应用分页/按ID筛选 | GET `/api/application?current=1&size=10&applicationId=...` |
| 应用创建/修改/删除 | POST `/api/application/create`；PUT `/api/application`；DELETE `/api/application/{id}` |
| 单条配额 | GET `/api/application/{id}/quota`；列表使用服务端批量嵌套quota，不逐卡请求。 |
| Key分页/按ID筛选 | GET `/api/apiKey/{applicationId}?current=1&size=10&apiKeyId=...` |
| Key创建/修改/删除 | POST `/api/apiKey/create`；PUT/DELETE `/api/apiKey`。后两者请求体含applicationId、apiKeyId。 |
| Key轮换 | POST `/api/apiKey/{apiKeyId}/rotate`，请求体传expectedPublicId。 |
| Usage汇总 | GET `/api/usage`，查询参数含applicationId、apiKeyId、timeRange及可选自定义边界。 |
| 调用记录 | GET `/api/usage/events`，查询参数含applicationId、apiKeyId、current、size。 |

请求入口只允许上述管理路径和匹配的HTTP方法。不能把任意URL、auth路径或/v1机器接口交给management并自动携带Bearer。传输层仅向页面保留已知错误码和状态，不透传服务端诊断正文。

Application与Key的0表示启用、1表示禁用；其他值显示未知。禁用与额度耗尽是两个不同状态，不能只靠颜色区分。服务端仍独立验证所有权和操作条件，客户端的disabled按钮不能代替授权。

## 写入、确认与结果不确定

创建、改名、启停、轮换和删除都有局部忙碌状态。资料刷新期间不能按旧资料提交修改；迟到的读取通过页面/查询版本丢弃，切回前台重新核对。Usage每次查询使用独立版本，快速改日期时先前响应不能覆盖新选择。

应用禁用说明所有Key会受影响；删除应用输入当前完整名称确认；删除Key也输入名称确认。删除接口确认成功后先清理弹窗和忙碌状态，再返回，避免页面自己的onBackPress把已成功操作的导航拦住。

网络中断、不可确认的服务响应或5xx不能当成“肯定没写入”。页面显示结果未知，阻止直接重复提交，并要求先刷新核对。不会自动重试创建、删除或轮换；这不是服务端幂等机制。当前AT的401续期也不重放原业务请求。

轮换带着读取到的Public ID作为比较条件。服务端返回冲突时先刷新资料，不以新Public ID悄悄再次轮换。未知结果下可能已经生成的新Secret无法找回；核对后由用户明确决定再轮换或删除。

## 完整Key的生命周期

创建和轮换成功后，完整凭据只保留在当前页的临时secret字段，交给key-reveal显示；不写入Store、路由、Storage、日志或证据文件。列表与详情状态只保留明确挑选的元数据，不能把创建响应整包塞进列表。

保存弹窗提供主动复制；复制是用户明确操作。完成按钮要求勾选“我已经保存”，完成后清空secret。Android返回在弹窗显示时被拦住；切后台/离开页面会直接清空，回到页面也不会重新获取。警告文案说明这一取舍，避免让用户误以为系统替其保管了Secret。

如果创建/轮换响应在离开页面后才到达，不把凭据重新写回隐藏页。只保留非敏感的核对提示。没有“再次查看完整Key”接口或入口。

## 配额、Usage与北京时间

配额由后端返回usedCount/quotaLimit/restCount和周期起止，前端只计算展示百分比，不生成或重置周期。零上限显示无可用额度，接近上限与耗尽有文字；查询失败显示原位错误，不伪装成0。所有Key共用当前30天配额，刷新只查询。

Usage提供今天、昨天、近7天、近30天、本月、上月及自定义。自定义日期/时间使用原生picker，按北京时间传给后端，最多90天，区间左闭右开。绝对时刻按北京时间显示，不受手机默认时区影响；每日日期采用服务端的统计日。

累计与选中时段分别显示，空时段保留真实累计值。每日图从零开始，接口占比以选中时段次数为分母，零次数不除零。调用记录单独分页，明确说明不跟随上方时段筛选；展示实际接口码、HTTP状态、耗时和北京时间。

## 验证入口与范围

- `scripts/test-auth-session.cjs` 与 `test-management-contracts.cjs`：共48项，通过会话保护、管理路径/方法限制、写入401不重放、201注册、错误净化及时间边界等检查。
- `scripts/test-management-ui.cjs`：在浏览器测试中替换平台适配器和HTTP响应，覆盖完整页面流程、重复名称、禁用、零Usage、配额失败重试、旧响应隔离、一次性保存门槛、模拟剪贴板复制、切页清除、创建/轮换丢响应、删除及窄屏。发出的请求全部被测试夹具拦截；不把这些检查称为真实后端验收。
- `scripts/test-ui-components.cjs`：基础组件、触控尺寸、输入与窄屏展示回归。
- 独立nexus_test与Android调试基座的真实验收记录在忽略目录 `.runtime/management-live/`；注册由开发者实际点按确认，自动流程使用另一随机临时账号。通过真实管理操作和受控UUID调用核对Usage/配额，不创建截图业务任务或访问外部站点。证据不包含密码、AT/RT或完整Key。

真机自动化须使用WebView触摸事件，并选择getTopWebview对应的栈顶页面；底层旧页可能仍报告isVisible。填写后先结束输入法焦点，再触摸操作。不能把测试工具未触发按钮当成接口失败，更不能为此绕过表单或服务端校验。

运行环境、最终清理计数和待办以 [SESSION_HANDOFF.md](SESSION_HANDOFF.md) 的移动端节为准。App源码验收不等于签名安装包、iOS、小程序真机或生产部署验收；保持登录仍受后端固定24小时会话绝对期限限制。
