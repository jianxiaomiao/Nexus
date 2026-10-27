# V9 + OBS-1 Ubuntu 回环发布

本目录只服务从 `V2026.10.22.1__ubuntu-initial` 升级到
`V2026.10.27.1__v9-obs1` 的一次版本化发布。它不会开放公网端口、安装
Prometheus/Grafana、修改安全组或放宽 AppArmor。

发布分为五个明确边界：

1. `01-stage-release.sh` 校验归档、清单、SHA-256 与干净 Git 来源，把候选安装为只读 release，并配置新 `dist` 的最小 ACL；不重启服务、不切换 `current`。
2. `02-deploy-release.sh` 停止回环 Nginx 和 Nexus，保存 V8 数据库/配置/环境文件/Nginx 备份，在临时 schema 实际还原备份并比对表/行数及 Flyway checksums，清理临时 schema 后才原子切换候选。执行 Flyway V9，并验证独立回环 8081、最小端点、指标、Nginx TLS 与 `/actuator` 404；成功后仍保持 `PENDING`。
3. `03-accept-v9-obs1.py` 通过回环 TLS 创建临时 User/Application/两把 Key，验证 UUID、异步截图、跨 Key 隐藏、PNG、Quota、Usage、固定 Chromium、AppArmor 与凭证日志，随后物理清理精确数据和结果文件。
4. `04-promote-release.sh` 只接受第三步的成功标记，原子推进 `rollback-release` 与 `last-successful`，不重启服务。
5. `05-rollback-release.sh --restore-v8` 只允许在提升前执行。它保留失败 V9 dump，恢复切换前的 V8 dump、配置、Nginx 与旧 release；不会删除失败候选或诊断证据。

本地先运行 `New-NexusReleasePackage.ps1`，再运行相邻目录的
`New-V9Obs1DeploymentBundle.ps1`。后者生成上传清单及 ECS 端的精确命令。

安全边界：

- 管理端口固定为 `127.0.0.1:8081`，只暴露 health、metrics、prometheus；
- 业务 8080、管理 8081、MySQL 3306、Nginx 18080/18443 均只在回环；
- Nginx 对 `/actuator` 明确返回 404，不把它交给 Java 或 Vue fallback；
- 80/443 不监听，UFW 不增加 Allow；
- Chromium 继续使用 Playwright 1.63.0/v1243 的固定目录和精确 AppArmor profile；
- 任何切换失败只停止服务并保存证据，数据库恢复必须显式执行带 `--restore-v8` 的独立脚本。

所有操作使用同一个文件锁，拒绝并发执行。上传后先核对两个归档的 SHA-256，再把工具包解包到 root 拥有的 `/opt/nexus/deploy-tools`，从那里执行。操作员需要具备现有 `sudo` 权限；脚本不把 `nexus.env` 当作 shell 脚本执行，也不打印其中的值。

回滚总是恢复已验证的 V8 备份，不依赖旧 JAR 对未来 Flyway V9 的兼容性推测。它只适用于本次尚未提升、未接收真实外部写入的回环验收窗口；提升后拒绝执行。停止后、备份完成前失败时，脚本会重新启动尚未修改的旧服务；备份完成后会写入恢复状态，再开始修改配置或 `current`。

执行第二步前，操作员应核对阿里云入方向规则仍无 80/443/8080/8081/18080/18443/3306 等新增 Allow。主机内脚本只能检查监听和 UFW，无法读取云安全组。

本地离线验证命令：

```powershell
python -B -m unittest discover -s tools/deployment/linux/v9-obs1 -p test_release_tools.py -v
```

该组测试验证归档安全、篡改拒绝、备份 SQL 改写，以及真实 HTTP 合同形状的模拟验收成功/失败与清理边界。Bash 脚本另做语法检查。这些都不代替 ECS 上的 systemd、MySQL 恢复、Nginx/AppArmor 和真实 Chromium 验收。

2026-10-27 本地发布准备验证：后端以显式local、专用nexus_test_user/nexus_test、Hikari 2/1、一次性JWT、既有离线Maven缓存和Chromium执行`mvn -o package`，452/452通过，0失败/错误/跳过并生成可执行JAR；测试JVM/Chromium无残留。前端生产构建和E2E类型检查通过，未重复完整模拟浏览器套件；界面源码未修改。发布工具离线测试12/12和Bash/PowerShell语法检查通过。

打包准备的失败记录：最初选择了不完整的`.m2repo`，离线依赖解析失败，改用既有完整缓存；额外注入独立Flyway变量时451/452通过，通用`jdbc.connections.active`缺失；随后用SetEnvironmentVariable(...,$null,...)清理时，在当前PowerShell实际留下空变量，导致Flyway driver初始化失败。通过小实验确认`Remove-Item Env:...`确实移除变量后，恢复既有测试边界，完整452/452通过。没有跳过测试、改业务或改本地数据库账号权限。ECS本就使用独立迁移身份，发布验收以Hikari实际指标为连接池检查，并保留该环境下`jdbc.*`可用性差异，不把本地测试结论外推为ECS已通过。
