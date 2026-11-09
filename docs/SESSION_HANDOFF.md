# Nexus Session Handoff

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
- 前端已有 Webhook 管理页面及模拟接口，但后端当前没有对应管理 Controller/Service；该功能另行安排。
- 后端完整回归 638/638、前端模拟浏览器回归 103/103；隔离 Compose 与真实本地 Compose 的登录、Key、UUID、UTC 和容器重建验证见 docs/UTC_TIME_DESIGN.md。
- 开发者希望先说明每一步的目的、验证内容及遗漏的影响，机械工作由 Agent 完成，关键结果用于教学。

## 下次会话

开发者准备关机。下次开机后先只读检查 Docker Desktop 和 nexus-docker-compose-lab 的启动状态，再根据实际状态处理。不要修改 ECS 或清理数据，除非获得明确授权。先读 AGENTS.md、docs/UTC_TIME_DESIGN.md 和 tools/docker/README.md，以仓库与实际状态为准。
