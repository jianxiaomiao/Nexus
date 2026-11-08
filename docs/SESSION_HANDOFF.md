# Nexus Session Handoff

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
