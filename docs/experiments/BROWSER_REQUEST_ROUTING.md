# Playwright 浏览器请求控制实验

Playwright 提供浏览器自动化能力。测试框架用它打开页面、操作元素和验证结果；截图服务也可以用同样的浏览器控制 API 打开网页、处理网络请求并截图。这个实验是普通 Node 脚本，不运行 `playwright test`，也不需要 Nexus、MySQL 或任何凭证。正式 Java 服务可以使用 Java 版 Playwright，实验不把 Node 引入后端架构。

## 预测与样本

脚本临时创建两个只监听 `127.0.0.1` 的服务器：正常资源服务器提供 HTML、CSS、图片和 JavaScript；另一个哨兵服务器模拟不允许访问的内网目标。页面脚本还会向哨兵请求一张图片和一份 JSON。

预测：仅审查入口不能阻止两个后续请求；逐个资源拦截可让哨兵收到零次请求；用 Fetcher 取得响应再 fulfill 时，正常资源将由 Fetcher 获取。

实验专门允许脚本自己创建的固定样本地址与四个路径，这个本地许可不能复制到生产策略中。没有真实公网 DNS 查询，也没有复用 Java 的 `PublicWebDnsResolver`。

## 运行

在 PowerShell 执行：

```powershell
Set-Location D:\Nexus\nexus-console
node scripts/experiment-browser-routing.mjs
```

复用当前已安装的 Playwright 和 Chromium，无需启动前后端。三种模式使用同一个页面样本、独立浏览器上下文，并在结束时关闭浏览器与两个临时服务。

## 实际观测

2026-10-11 已运行，三种模式的断言全部通过：

| 模式 | 浏览器直接获取正常资源 | Fetcher 获取正常资源 | 模拟内网实际收到请求 |
| --- | --- | --- | --- |
| 只检查入口 | 4 | 0 | 2 |
| 逐个拦截资源 | 4 | 0 | 0 |
| Fetcher 获取后 fulfill | 0 | 4 | 0 |

三次运行中，外部 CSS 均正常生效、正常图片均成功显示、脚本均已执行。内网图片与脚本 fetch 在后两种模式中失败，哨兵服务确认没有收到请求；不是“收到后再丢弃响应”。

输出在 Git 忽略的 `nexus-console/test-results/browser-routing-experiment/`：

- `01-entry-only.png`：仅检查入口，哨兵收到两次请求。
- `02-abort-private.png`：正常资源由浏览器获取，哨兵请求被拒绝。
- `03-fetch-and-fulfill.png`：正常资源由 Fetcher 获取，浏览器负责渲染。
- `result.json`：计数、资源类型、处理动作及服务器实际请求记录。

## 沿代码看调用关系

`context.route('**/*', handler)` 在页面导航之前注册。浏览器准备发出普通资源请求时调用 handler：

```text
不允许的目标 → route.abort() → 哨兵没有收到请求
允许的目标   → labFetcher(url) → 得到资源字节和内容类型
            → route.fulfill(resource) → 浏览器渲染响应
```

第二种模式的 `route.continue()` 让 Chromium 自己建立连接；第三种模式的 `route.fulfill()` 使用我们交给它的响应，服务器观测到资源客户端变成了 Fetcher。Java API 中对应的直接放行方法名是 `resume()`。

因此，请求拦截 API 本身不是“测试专用”：测试可以用 fulfill 提供假响应，业务代码也可以用 fulfill 提供真实受控客户端获得的响应。实验中的断言只是帮助检查观察结果。

## 此实验的边界与下一步

本实验只证明普通资源请求的拦截、终止与响应交付机制。第二种模式不能证明 Chromium 的 DNS 安全；第三种模式的实验 Fetcher 也不是生产级安全下载器。后端落地时仍需要连接时 IP 校验、资源白名单、重定向策略和流式大小限制，并明确 Service Worker、WebSocket、浏览器其他出站通道与进程隔离的处理。

下一步先由开发者解释“谁发起资源需求、谁真正建立连接、谁负责渲染”，再确定截图业务是否执行脚本、允许哪些资源域名及失败时返回什么。不要把实验直接接进 Controller。

参考：[Playwright 独立库](https://playwright.dev/docs/library)、[Java BrowserContext 路由](https://playwright.dev/java/docs/api/class-browsercontext#browser-context-route)、[Route.fulfill](https://playwright.dev/docs/api/class-route#route-fulfill)。
