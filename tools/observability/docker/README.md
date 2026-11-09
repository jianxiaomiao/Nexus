# Nexus 本地 Compose 监控

这是 `nexus-docker-compose-lab` 的可选监控覆盖配置，仅供本地学习，不是生产部署方案。与 FlowOps 共存：Nexus Grafana 为 `http://127.0.0.1:13000`，Prometheus 为 `http://127.0.0.1:19090`；FlowOps 的 3000/9090 不受影响。先检查端口空闲，不修改其他项目。

```text
Grafana → prometheus:9090 → nexus:8081/actuator/prometheus
```

Docker 内的 `127.0.0.1` 指容器自己。后端连接原默认网络及监控网络；Prometheus、Grafana 仅加入监控网络。Nexus 管理端口监听容器 `0.0.0.0:8081` 且不向主机发布，只暴露 health、metrics 和 prometheus。注意：监听 0.0.0.0 会覆盖 Nexus 的所有容器网络接口，默认网络的其他容器也可能访问管理端口；专用网络不构成端口级访问控制。Nginx 保持拒绝代理 `/actuator`。

Prometheus 和 Grafana 分别使用本地已有的 3.5.0、12.1.1 固定镜像摘要；与原 Windows 版本不同。新卷 `nexus-docker-compose-lab_prometheus-data` 保存 TSDB，保留期 7 天且配置 1 GB 大小边界（这不是整个目录的硬盘使用量上限，WAL 等文件也会占空间）；`nexus-docker-compose-lab_grafana-data` 保存用户及 Grafana 状态。旧 `data/observability` 中的 TSDB 和 Grafana SQLite 不自动迁移或覆盖。数据源 `nexus-prometheus` 与 14 面板 Dashboard `nexus-docker-overview` 从只读文件自动配置；修改自动配置的 Dashboard 请修改项目文件，而不是依赖 UI 编辑。

## 首次准备及启动

需要 PowerShell 7、Docker Desktop 和已经准备好的 `data/docker-compose-lab/local.env`。先确认无同名监控卷，且 13000、19090 空闲。管理员密码文件 `data/docker-monitor-lab/grafana-admin-password` 是本机明文秘密，继承父目录权限，须确保仅信任的本机用户能读取；不要提交或分享。仅首次运行以下生成脚本，文件已存在时脚本拒绝覆盖：

```powershell
Set-Location D:\Nexus
.\tools\docker\New-MonitorSecret.ps1
```

确认配置并启动（不删除现有 MySQL 或截图卷）：

```powershell
$envFile = 'D:\Nexus\data\docker-compose-lab\local.env'
$base = 'D:\Nexus\tools\docker\compose.yaml'
$monitor = 'D:\Nexus\tools\docker\compose.monitoring.yaml'
docker compose --project-name nexus-docker-compose-lab --env-file $envFile -f $base -f $monitor config --quiet
docker compose --project-name nexus-docker-compose-lab --env-file $envFile -f $base -f $monitor up -d --no-build
```

Grafana 用户名为 `admin`，首次密码来自本机密码文件；请在私密的 PowerShell 窗口使用以下命令查看，不要粘贴到聊天、日志或 Git：

```powershell
Get-Content -Raw 'D:\Nexus\data\docker-monitor-lab\grafana-admin-password'
```

密码只在 Grafana 数据库首次初始化时应用；修改文件不会自动重置已有用户密码。若在 UI 修改了密码，应妥善保存新密码；密码文件不再代表当前密码。重新创建容器会保留数据卷；删除 `grafana-data` 会丢失用户和 UI 状态，删除 `prometheus-data` 会丢失历史指标。

后续运行使用同一组 `-f $base -f $monitor` 参数。只停止监控时可执行：

```powershell
docker compose --project-name nexus-docker-compose-lab --env-file $envFile -f $base -f $monitor stop prometheus grafana
```

不要把 `down -v` 当成普通停止：它还会删除此 Compose 项目的 MySQL、截图以及监控数据卷。需要清理时先核实具体卷名和保留要求。

## 验证记录

2026-11-09：本机目标 `up`，Grafana 数据源健康且自动加载 14 个面板；一次真实 UUID HTTP 200 后，Prometheus 对应计数从 0 增至 1。新建一组专用验收用户/Application/Key，未输出凭据。重建 Prometheus 和 Grafana 容器后，旧 Prometheus 样本可查询、Grafana 验收文件夹仍在；随后只删除本次创建的验收文件夹。Grafana 登录页返回 200。FlowOps 原端口与 Nexus 原有数据库/截图卷均未删除。浏览器中人工观察曲线尚需开发者确认。


2026-11-09 完整 Compose down/up 验证：明确使用本项目的两个 Compose 文件，执行不带 `-v` 的 `down`，确认四个专用卷仍存在后 `up -d --no-build`。五个服务容器 ID 均变化；用户数量保持 2，Prometheus 重建前同一时刻的 HTTP 指标样本一致，Grafana 数据卷验收标记保留并只删除该临时标记。Prometheus 目标为 up，Grafana 登录页 200，前端无 Key 的 UUID 返回预期 401；FlowOps 十个容器 ID 不变。通过 Grafana API 确认 14 面板、数据源 OK，实际执行 HTTP 状态面板查询返回 200 和 3 组数据。该实验确认截图卷仍存在，未重新验证截图文件本身；截图文件仍按业务规则到期。开发者浏览器中的实际显示需由开发者确认。
