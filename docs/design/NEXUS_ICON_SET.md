# Nexus 最小图标集 v1

原则：品牌符号自有；通用操作使用 `@element-plus/icons-vue`，按需导入，不另画一套近似图标，也不全局注册所有图标。实际界面使用 SVG，而不是 Unicode/emoji 字形。

| 用途 | 来源 | 页面中的形式 |
| --- | --- | --- |
| 品牌星星 | [`brand-spark.svg`](../../nexus-console/src/assets/icons/brand-spark.svg) | 金色实心四角星；只作 Nexus 品牌/装饰 |
| Application | Element Plus `Grid` | 侧栏导航，20px |
| 收起 / 展开侧栏 | Element Plus `Fold` / `Expand` | 按钮图标，18px |
| 更多 | Element Plus `MoreFilled` | 更多操作按钮，18px |
| 返回 / 前进 | Element Plus `ArrowLeft` / `ArrowRight` | 链接辅助图标，16px |
| 退出 | Element Plus `SwitchButton` | “退出登录”按钮，18px |
| 创建 | Element Plus `Plus` | 主按钮内，跟随按钮文本 |

约定：

- 通用图标通过 `el-icon` 承载，继承所在控件的 `currentColor`；品牌星星固定使用设计规范中的金色 `#E7BD58`。默认 18px，导航 20px，文字链接 16px；不为每个页面单独发明尺寸或线条。
- 装饰图标使用 `aria-hidden="true"`；品牌星星图片另设 `alt=""`。图标不能代替状态文字，运行中/已禁用始终显示文字。
- 有可见文字的按钮保留文字；折叠导航或其他仅图标的交互控件必须有 `aria-label`。不要把 `aria-hidden` 放到可点击的按钮或链接本身。
- 若 Element Plus 图标与特定品牌场景明显不合适，再评审是否追加自有 SVG；不预先绘制整套图标库。
