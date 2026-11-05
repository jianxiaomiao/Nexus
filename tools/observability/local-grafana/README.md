# Nexus 本地 Grafana 学习环境

本目录保存可审查、可重复执行且不含密码的 Grafana 本地配置。Grafana 程序、SQLite 数据库、日志和插件属于本机运行产物，位于仓库已忽略的 `D:\Nexus\data\observability\grafana`。

```text
浏览器 127.0.0.1:3000
    → Grafana
    → Prometheus 127.0.0.1:9090
    → Nexus 127.0.0.1:8081/actuator/prometheus
```

## 文件职责

- `custom.ini`：覆盖 Grafana 默认设置，定义回环监听、SQLite 位置和本地安全边界。
- `start-grafana.ps1`：推导仓库和运行目录，创建运行目录并以前台进程启动 Grafana。
- `New-NexusOverview.ps1`：生成独立 Dashboard JSON；默认数据源 UID 对应本机现有 Data Source，可通过参数覆盖。
- `nexus-local-overview-v1.json`：13 面板本地 Dashboard，导入时作为新 Dashboard 使用。
- `nexus-local-overview-v2.json`：改进低流量和二值指标刻度，并分离 Heap 使用量与报告上限的 14 面板版本。

不要修改解压目录中的 `conf/defaults.ini`。升级 Grafana 时可以替换 `app`，而 `state` 中的 SQLite 数据库继续保存 Data Source、Dashboard 和用户配置。

## 运行目录

```text
D:\Nexus\data\observability\grafana\
├── app\       Grafana 13.3.0 程序
├── download\  已下载的官方归档
├── state\     SQLite 数据库和其他状态
├── logs\      Grafana 日志
└── plugins\   后续安装的外部插件
```

`custom.ini` 使用环境变量引用这些绝对路径。启动脚本只为当前 Grafana 子进程设置变量，不修改用户或系统环境变量。

## 启动

先确认端口 3000 没有被其他程序占用，然后在仓库根目录执行：

```powershell
.\tools\observability\local-grafana\start-grafana.ps1
```

脚本以前台方式运行。正常停止时在该终端按 `Ctrl+C`，不要按进程名批量结束其他进程。

启动后检查：

```powershell
Invoke-RestMethod `
    -Uri 'http://127.0.0.1:3000/api/health' `
    -TimeoutSec 5
```

然后用浏览器打开 `http://127.0.0.1:3000`。首次登录使用 Grafana 初始管理员账户，并立即按照页面要求修改密码；不要把新密码写入仓库、脚本或聊天记录。

## 当前边界

- 只监听 `127.0.0.1:3000`；
- 不开放防火墙、Nginx 或公网入口；
- 禁止匿名访问和用户自助注册；
- 关闭使用情况上报和版本、插件更新检查；
- 禁止启动时联网预安装插件和从 UI 安装插件；当前 Prometheus Data Source 使用发行包自带插件；
- 使用本地 SQLite，只适用于单机学习环境，不代表生产部署决策；
- Grafana UI 中已由开发者创建本地 Prometheus Data Source、四面板 Dashboard，并验证 JSON 在同一实例导入副本；未配置告警。

## 导入完整 Dashboard

打开 Dashboards → New → Import，选择 `nexus-local-overview-v2.json`。这是独立 Dashboard，导入时检查名称和 UID；如果显示覆盖已有 Dashboard，生成新 UID 后再导入。该文件使用本机 Data Source UID，跨实例时需替换为目标 Prometheus 数据源 UID。原四面板 Dashboard 与导入测试副本不会被生成脚本修改。

推荐导入 `nexus-local-overview-v2.json`：低值面板使用软轴范围，实际值超出后仍可自动扩展，轴参考值不代表告警阈值或系统容量。CPU 的 process 只指 Nexus JVM，system 指整个操作系统，独立 Chromium 进程不计入 JVM 进程值。Heap 的 reported max 单独展示，不必然精确等于 `-Xmx`。

生成另一份文件的例子（不覆盖已有文件）：

```powershell
.\tools\observability\local-grafana\New-NexusOverview.ps1 `
    -DataSourceUid '<目标 Prometheus 数据源 UID>' `
    -OutputPath 'D:\Nexus\data\observability\grafana\nexus-overview-import.json'
```

面板包括抓取状态、uptime、HTTP 总速率和状态码、JVM Heap 和 CPU、Hikari 连接和等待、限流状态与决策、截图未完成数与执行槽、Usage 最终记录失败。所有速率都是五分钟平均值；截图未完成数的 `NaN`/缺失不视为 0，业务状态码不直接等于业务失败率。当前默认配置未导出 Tomcat 线程池指标，因此没有 Tomcat 线程池图；没有直方图桶，也不计算 p95/p99。

## 精确清理

正常停止 Grafana 后，只有明确决定丢弃全部 Grafana 本地配置时，才删除：

```text
D:\Nexus\data\observability\grafana\state
D:\Nexus\data\observability\grafana\logs
D:\Nexus\data\observability\grafana\plugins
```

删除 `state` 会永久丢失本地用户、Data Source 和 Dashboard。不要删除 Prometheus TSDB、Nexus 数据库或其他 `/data/` 子目录。
