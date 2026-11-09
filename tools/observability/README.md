# Nexus 本地指标采集与可视化

此目录记录本机学习环境的指标链路：

```text
Nexus Actuator 127.0.0.1:8081
  → Prometheus 127.0.0.1:9090（15 秒抓取、本地 TSDB）
  → Grafana 127.0.0.1:3000（查询与可视化）
```

## 本次完成

- Prometheus 3.13.5 本地抓取 Nexus，配置 7 天和 1 GiB 保留边界；验收脚本分阶段检查回环监听、目标状态、连续采样和 Prometheus 重启后历史样本。
- Grafana OSS 13.3.0 使用本地 SQLite 保存配置，关闭匿名访问、自助注册和插件预安装；Prometheus Data Source 通过 UI 配置。
- 提供可导入的 Dashboard JSON：V2 包含 HTTP、JVM、Hikari、限流、截图及 Usage 等 14 个面板。提供生成脚本以替换本机 Data Source UID。
- 用户完成 Grafana 重启后 Data Source 和 Dashboard 保留的验证，并通过一次普通 UUID 请求观察到 HTTP 200 曲线变化；这不是压力测试，也不等于对所有业务功能的健康验收。

## 文件与运行数据

- [Prometheus 配置、启动与验收](local-prometheus/README.md)
- [Grafana 配置、启动与 Dashboard](local-grafana/README.md)
- [Docker Compose 监控环境](docker/README.md)：使用独立监控卷和 13000/19090 回环端口，不自动迁移 Windows 数据。

Windows 二进制文件、下载归档、Prometheus TSDB、Grafana SQLite、插件和日志放在被 Git 忽略的 `/data/observability/` 下，**不包含在仓库中**。启动前需自行从官方渠道下载对应版本并验证发布方校验值，具体布局见各子目录 README。密码和令牌不得写入仓库。

## 观察边界

`up=1` 仅表示监控端点抓取成功；`No data`/`NaN` 不等于 0。HTTP 请求速率和状态码不等于业务错误率。CPU 的 process 指 Nexus JVM，system 指操作系统整体。当前默认配置没有导出 Tomcat 线程池指标，Timer 也没有直方图桶，因此 Dashboard 不声称展示 Tomcat 线程池状态或历史 p95/p99。该配置仅供本地学习，不开放公网监控入口，不代表生产部署方案。
