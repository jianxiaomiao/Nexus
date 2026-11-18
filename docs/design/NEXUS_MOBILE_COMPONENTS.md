# Nexus 移动端基础组件

普通 uni-app Vue 3 项目 `nexus-mobile/` 的界面从两层复用：`styles/theme.css` 保存颜色、间距、字体和圆角；`nexus-*` 基础组件保存控件的样式与交互。登录页和 Application 列表已使用这层组件。登录校验、会话、接口、权限和业务状态映射仍由页面或业务模块负责。

设计依据为 [总规范](NEXUS_DESIGN_GUIDE.md)、[视觉预览](NEXUS_VISUAL_PREVIEW.html)、[页面基准](NEXUS_CONSOLE_SCREENS_V1.md)、[图标规范](NEXUS_ICON_SET.md) 及 Web 的 `nexus-console/src/assets/main.css`。设计图中的虚构字段、早期功能进度和桌面侧栏不作为移动端需求。

## 视觉约定

- 暖白 `--nexus-paper`、鼠尾草绿 `--nexus-sage`、内容表面 `--nexus-surface`、深墨绿 `--nexus-ink`、青绿 `--nexus-teal` 和金色 `--nexus-gold` 延续现有品牌。
- 成功 `success`、禁用业务资源/警告 `warning`、失败/危险 `danger`、已删除/未知 `neutral` 各用独立的文字色和背景色。不可点击的控件使用 `disabled` 色；这与业务资源的“已禁用”标签含义不同。状态必须同时有文字。
- 按钮触控区域至少 44×44px，常规按钮和输入框高48px。文字大小使用px保持窄屏可读，布局间距使用rpx；卡片细边框、轻阴影，正文与业务数据不叠加插图。
- CSS变量在 `App.vue` 全局导入，并由页面继承。页面只写布局，基础控件的颜色、边框、聚焦、禁用、错误样式写在各组件内。`uni.scss` 是原项目的第三方插件兼容入口，当前组件不依赖其中的默认变量，也不需要安装Sass。
- 品牌星星复用现有 `brand-spark.svg`，移动端资源在 `static/brand/`；源SVG更新时同步这个副本。通用操作当前保留清楚的文字，不引入Element Plus或Unicode图标代替SVG；后续确有图标需求再按图标规范导出所需资源。
- 空状态复用已有猫星球静态图，小尺寸展示；3D仍只在原登录品牌组件中运行。危险确认继续保持严肃，不使用猫插图。

## 使用方式

组件遵循 `components/组件名/组件名.vue` 目录结构，可直接通过 easycom 在页面和其他组件中使用，无需逐页导入注册。它只省去注册步骤，仍然使用Vue的props、emit和slot。[uni-app easycom说明](https://uniapp.dcloud.net.cn/collocation/pages#easycom)

```vue
<template>
  <view class="nexus-page">
    <nexus-card title="应用名称">
      <nexus-input
        v-model="name"
        input-id="application-name"
        label="名称"
        :maxlength="64"
        :error="nameError"
        @blur="nameError = name.trim() ? '' : '请输入名称'"
        @input="nameError = ''"
      />
      <nexus-button block :loading="saving" @press="save">保存</nexus-button>
    </nexus-card>
  </view>
</template>
```

上例页面自己提供 `name`、`nameError`、`saving` 和 `save`。组件不会擅自请求接口，也不会根据按钮文案执行业务操作。页面重复业务结构尚未出现时，先组合这些基础组件，无需再造通用表单生成器。

## 组件接口

| 组件 | 常用props | 事件和slot |
| --- | --- | --- |
| `nexus-button` | `variant`: primary/secondary/text/danger；`block`；`compact`；`loading`；`disabled`；`label`为额外可访问名称 | `@press`；默认slot为按钮内容。loading同时阻止点击，compact也保留44px触控目标。H5支持Enter/空格和可见焦点。危险样式不执行确认或删除，页面负责确认。 |
| `nexus-input` | `v-model`字符串；必填`input-id`且页面内唯一；`label`；`placeholder`；`maxlength`；`error`；`hint`；`password`；`focus`；`disabled`；`type`；`inputmode`；`confirm-type` | `update:modelValue`和`input`传出字符串；`focus`/`blur`/`confirm`传出原事件；`#suffix`放密码切换或清空操作。error优先于hint展示。 |
| `nexus-dialog` | `open`；必填`title` | 默认slot放表单或说明，`#actions`放确认/取消。确认规则与返回拦截由页面负责，点击遮罩不自动关闭。 |
| `nexus-card` | 可选`title` | 默认slot放内容；`#header`替换标题区；`#footer`生成细分隔线和底部操作区。卡片不默认可点击。 |
| `nexus-notice` | `tone`: info/success/warning/danger；`title`；`message`；可选`code` | 默认slot可补内容，`#action`放重试操作。先显示易懂说明，再显示错误码；不要传入服务端堆栈或凭据。 |
| `nexus-badge` | 必填`label`；`tone`: success/warning/danger/neutral | 只负责呈现。业务数字状态在页面映射，组件不知道Application或API Key。 |
| `nexus-state` | `kind`: empty/loading/error；必填`title`；`description`；可选`illustration`资源路径 | `#action`放已实现的操作。加载、空状态和错误由页面决定互斥条件，不能在加载时先闪空列表。 |

按钮是普通操作的封装，不透传全部平台能力；微信授权、支付、原生 `form-type` 提交等要按平台单独设计。当前表单使用页面持有的v-model，按钮通过press调用页面提交方法，输入框通过confirm支持键盘操作。不要把自定义按钮当作可以透明跨组件提交原生form的保证。

## 输入的数据流

```text
页面 email
  → v-model拆成 :modelValue="email"
  → nexus-input把modelValue交给原生input的:value
  → 用户输入，原生input发出input事件（event.detail.value）
  → nexus-input emit('update:modelValue', 新文字)
  → 页面email更新 → 重新传给子组件
```

`props`传数据向下，`emit`通知父页面向上；子组件不直接改props。按钮内部监听原生tap，再发出自定义press；避免H5把tap监听转换为click后，页面意外依赖原生事件穿透。独立的`input`事件供页面清除旧错误，`blur`供页面执行离开字段后的检查。校验规则不写死在基础输入框内。[uni-app Vue 3组件v-model说明](https://uniapp.dcloud.net.cn/tutorial/vue3-components.html)

密码切换使用suffix插槽，输入框并不知道密码为何被隐藏：

```vue
<nexus-input v-model="password" input-id="password" label="密码" :password="!visible">
  <template #suffix>
    <nexus-button variant="text" compact :label="visible ? '隐藏密码' : '显示密码'" @press="visible = !visible">
      {{ visible ? '隐藏' : '显示' }}
    </nexus-button>
  </template>
</nexus-input>
```

## 预览与检查

组件预览页为 `pages/component-preview/component-preview.vue`，只展示控件、输入和状态，不发业务请求；未加入登录页或管理导航。在HBuilderX中运行该页面，Web也可直接打开 `http://localhost:5173/#/pages/component-preview/component-preview`（端口以运行控制台为准）。

`scripts/test-ui-components.cjs` 使用现有控制台项目的Playwright依赖，验证双向输入、父页面清空、失焦错误、禁用/加载点击保护、44px触控尺寸、语义色、登录密码切换，以及Application状态0/1/未知、配额、分页、加载/空/错/重试。列表检查使用浏览器内的固定展示数据，不冒充真实登录或后端验收。脚本默认访问回环5186，可用 `NEXUS_MOBILE_TEST_BASE_URL` 指定HBuilder的实际Web预览地址；截图写入忽略目录 `.runtime/ui-components/`。

Android和微信小程序编译检查与浏览器交互检查分别记录；编译通过不代表iOS/小程序真机已验收。普通uni-app Vue页面使用全局CSS变量，nvue/uni-app x不在这层封装的支持范围。组件抽取不改变既有会话和安全存储政策。


2026-11-18验证：最终浏览器脚本和安卓真机组件交互通过，Android与微信小程序编译通过。安卓验证包含press恰好一次、输入和父清空、失焦错误、主题继承、最小触控区域及无横向溢出；小程序/iOS尚未真机验证。本轮不调用业务API，真实认证验收仍见 [移动端会话设计](../MOBILE_SESSION_DESIGN.md)。

管理页面进一步组合了配额、一次性Key展示和Usage组件，完整调用链与真实验收见 [安卓管理页面迁移](../MOBILE_MANAGEMENT_DESIGN.md)。
