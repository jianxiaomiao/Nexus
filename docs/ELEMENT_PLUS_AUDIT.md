# Element Plus 控件盘点与改造记录

2026-10-08 对 `nexus-console/src/**/*.vue` 中的原生交互控件做静态盘点（按标签实例计数，不含 Element Plus 组件）：共 **37 处**，分布在 **5 个组件**。其中原生表单字段 **14 处**（`input` 8、`select` 5、`textarea` 1），原生 `button` **23 处**；另有原生 `form` 2 处，表单容器本身不算控件问题。

| 组件 | input | select | textarea | button | 主要风险 |
| --- | ---: | ---: | ---: | ---: | --- |
| `ApiPlaygroundView.vue` | 6 | 3 | 1 | 9 | 调试表单、到期时刻选择器与现有 Element Plus 表单风格不同 |
| `ApiKeyUsagePanel.vue` | 2 | 0 | 0 | 0 | 截图所示的两个原生 `datetime-local` 弹层与页面样式不一致 |
| `ShortLinksView.vue` | 0 | 2 | 0 | 9 | 原生资源选择器和列表操作按钮混用 |
| `ConsoleLayout.vue` | 0 | 0 | 0 | 3 | 导航/遮罩专用按钮，按本次范围保留 |
| `DocsLayout.vue` | 0 | 0 | 0 | 2 | 文档导航/遮罩专用按钮，按本次范围保留 |

## 改造结果

按开发者确定的范围，`ApiPlaygroundView.vue`、`ApiKeyUsagePanel.vue`、`ShortLinksView.vue` 中的原生 `input`、`select`、`textarea` 和 `button` 均已换为 Element Plus 组件；原生 `form` 容器也换为 `el-form`。`ConsoleLayout.vue`、`DocsLayout.vue` 的 5 个导航/遮罩按钮保留。随后 `ConsoleLayout.vue` 因独立内容区滚动需求调整了布局，但这些按钮仍未改动。再次扫描 `nexus-console/src/**/*.vue`，只剩这 5 个原生交互标签。

Usage 的两个日期选择器用 `format="YYYY-MM-DD HH:mm"` 显示、用 `value-format="YYYY-MM-DDTHH:mm"` 绑定无时区字符串，继续把它作为北京时间墙上时间发给后端；不做浏览器时区转换。Playground 的自定义到期时刻也绑定无时区字符串，但它代表浏览器本地时间，提交前按原有逻辑转换为绝对时刻。两个业务语义不同，不应共用一个隐式时区处理。

滚动区域也统一使用 `el-scrollbar`：控制台正文、登录/注册页、文档正文与侧边目录、Usage 图表/记录、Key 详情标签、短链表格、调试台操作标签/代码块。Markdown 代码块和表格由渲染器拆成独立片段后交给 Vue 组件包裹，仍保留复制按钮与目录锚点。项目样式不再声明 `overflow: auto/scroll`；组件内部的滚动由 Element Plus 管理。切换控制台、文档或登录/注册页面时，所在滚动区会回到顶部。

验证：前端构建与 E2E 类型检查通过；Chromium Playwright 全套 56/56。新增检查覆盖手机日期弹层、短链本地到期时间、桌面/手机控制台独立滚动、登录/注册页、文档锚点及横向表格、Usage 横向记录。此轮未重跑真实后端验收；相关重复测试脚本仍见 `docs/LOCAL_TESTING.md`。
