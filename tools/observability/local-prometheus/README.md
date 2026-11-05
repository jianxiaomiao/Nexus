# Nexus 本地 Prometheus 学习环境

本目录用于建立 Nexus 的第一段历史指标链路：

```text
Nexus 127.0.0.1:8081/actuator/prometheus
    → Prometheus 每 15 秒抓取
    → 本地 TSDB 持久化
    → 后续由 Grafana 通过 PromQL 查询
```

当前阶段只学习 Prometheus 采集与持久化，不安装 Grafana、不配置告警、不运行压力实验，也不访问或修改 ECS。

## 文件职责

- `prometheus.yml`：定义 Nexus 抓取目标、抓取周期和稳定标签。
- `start-prometheus.ps1`：以前台进程启动 Prometheus，并显式设置监听地址、TSDB 目录和保留边界。
- `accept-prometheus.ps1`：分两阶段验证连续采样、回环监听和跨重启持久化。

Prometheus 可执行文件和 TSDB 数据都属于本机运行产物，不提交到 Git。仓库根目录的 `.gitignore` 已忽略 `/data/`。

## 当前配置

| 项目 | 值 |
|---|---|
| Nexus 业务端口 | `127.0.0.1:8080` |
| Nexus 管理端口 | `127.0.0.1:8081` |
| Prometheus 端口 | `127.0.0.1:9090` |
| 抓取目标 | `127.0.0.1:8081/actuator/prometheus` |
| 抓取周期 | 15 秒 |
| 抓取超时 | 5 秒 |
| TSDB 目录 | `D:\Nexus\data\observability\prometheus\tsdb` |
| 时间保留上限 | 7 天 |
| 空间保留上限 | 1 GiB |

时间和空间限制同时生效，任一限制先触发时，Prometheus 都可以清理最旧的持久化块。1 GiB 是本地学习环境边界，不是生产容量结论。

## 前置条件

1. Nexus 使用 `local` 配置启动，并确认：
   - `http://127.0.0.1:8081/actuator/health` 返回 `UP`；
   - `http://127.0.0.1:8081/actuator/prometheus` 返回 Prometheus 文本；
   - 业务端口 `8080` 不暴露 Actuator。
2. 本机已有匹配版本的 `prometheus.exe` 和 `promtool.exe`。
3. `prometheus.exe` 和 `promtool.exe` 已放在仓库约定的本地运行目录 `D:\Nexus\data\observability\prometheus\bin`；不需要把该目录加入系统 `PATH`。
4. 端口 9090 未被其他进程占用。

建议的本地运行产物布局是：

```text
D:\Nexus\data\observability\prometheus\
├── bin\
│   ├── prometheus.exe
│   └── promtool.exe
└── tsdb\
```

## 启动前检查

在仓库根目录执行：

```powershell
& .\data\observability\prometheus\bin\promtool.exe check config .\tools\observability\local-prometheus\prometheus.yml
```

只有配置检查成功且 Nexus 管理端口已经可用时，才启动 Prometheus：

```powershell
.\tools\observability\local-prometheus\start-prometheus.ps1
```

当前脚本以前台方式运行。保持该终端打开；需要正常停止时使用 `Ctrl+C`，不要按进程名批量结束系统中的其他进程。

## 最小人工观察

Prometheus 启动后检查：

1. `http://127.0.0.1:9090/-/ready` 可用；
2. Targets 页面中的 `nexus-local` 为 `UP`；
3. 查询 `up{job="nexus-local"}` 得到 `1`；
4. 等待至少两个抓取周期后查询：

```promql
process_uptime_seconds{job="nexus-local"}
nexus_ratelimit_states{job="nexus-local"}
hikaricp_connections_active{job="nexus-local"}
```

时间序列应出现多个采样点。`up=1` 只表示 Prometheus 成功抓取目标，不等同于全部业务功能健康。

## 持久化验收

持久化不是“目录存在”就算通过。后续 `accept-prometheus.ps1` 至少应验证：

1. Nexus 8081 和 Prometheus 9090 都只监听回环地址；
2. `nexus-local` target 为 `UP`；
3. 至少两个抓取周期形成连续样本；
4. 正常停止并重新启动 Prometheus 后，重启前的历史时间点仍能查询；
5. TSDB 目录非空，且没有把运行数据写入受版本控制的目录；
6. Nexus 的 `/actuator/env` 和业务端口 Actuator 仍不可访问；
7. 抓取失败、指标 `NaN` 或时间序列缺失应显示为未知/失败，不能转换成健康的 0。

这些检查只需要少量普通请求产生指标变化，不能把 Maven 回归测试或真实压力实验混入本阶段。

先在 Nexus 和 Prometheus 都运行时执行重启前阶段：

```powershell
.\tools\observability\local-prometheus\accept-prometheus.ps1 -Phase BeforeRestart
```

该阶段等待 35 秒验证样本时间前进，并将检查点写入被 Git 忽略的：

```text
D:\Nexus\data\observability\prometheus\acceptance-checkpoint.json
```

通过后，只在 Prometheus 前台终端按 `Ctrl+C` 正常停止它。保持 Nexus 继续运行，重新执行启动脚本。Prometheus Ready 且 target 恢复为 `UP` 后执行：

```powershell
.\tools\observability\local-prometheus\accept-prometheus.ps1 -Phase AfterRestart
```

第二阶段要求 Prometheus PID 已改变、Nexus 进程启动时间未改变、重启前的精确样本仍能查询，且已经出现更新的样本。这样可以避免把“文件存在”误判为跨重启持久化，也避免同时重启 Nexus 引入第二个自变量。

## 抓取成本边界

大部分指标来自 JVM 内存中的 Meter；`nexus.screenshot.jobs.unfinished` 是例外，每次采样会查询 MySQL。当前 15 秒抓取周期是本地学习起点，不应在没有证据时改成 1 秒。

Prometheus 当前只能得到已有 Timer 的 count、sum 和窗口 max。仓库尚未为 Timer 发布 histogram buckets，因此本阶段不能在 PromQL 中计算可靠的历史 p95/p99；直方图区间和额外时间序列应在 Grafana 基础链路通过后单独审定。

## 安全边界

- 不把 8081 或 9090 绑定到 `0.0.0.0`；
- 不经 Nginx 代理 `/actuator`；
- 不增加 Windows 防火墙、UFW 或云安全组入站规则；
- 不在配置或标签中保存 JWT、API Key、密码、User/Application/Job ID、原始 URL；
- 不把本地实验结论描述为 ECS 或生产环境结论；
- 不为了接入 Grafana而直接修改 Nexus 的公网边界。

## 精确清理

1. 在启动 Prometheus 的终端中使用 `Ctrl+C` 正常停止；
2. 确认 9090 已无监听；
3. 只有明确决定丢弃全部本地指标历史时，才删除：

```text
D:\Nexus\data\observability\prometheus\tsdb
```

删除 TSDB 会永久失去全部历史数据，重新启动 Prometheus 只会从下一次抓取开始形成新历史。不要删除 Nexus 数据库、日志或其他 `/data/` 子目录。

## 下一检查点

先补齐并通过 `accept-prometheus.ps1`，证明采集、回环监听和重启持久化成立。该检查点通过后，下一学习单元才是接入本地 Grafana Prometheus Data Source，并建立不依赖 p95/p99 的第一版基础 Dashboard。
