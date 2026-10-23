# ECS 正式 Nginx Windows 服务（隔离端口阶段）

这组脚本把已验证的 Windows 服务边界落到 ECS，但仍只监听
`127.0.0.1:18080`。它不会停止 IIS/Tomcat，不会修改安全组或 Windows
防火墙，也不会占用 80/443。

## 固定边界

- 服务名：`NexusNginx`
- 服务账号：`NT SERVICE\NexusNginx`（无需保存密码）
- 启动方式：`Manual`
- 进程树：`WinSW -> PowerShell 7 -> Nginx master -> worker`
- 失败恢复：10 秒、30 秒各重启一次，此后停止；1 小时后重置计数
- 正常停止上限：WinSW 140 秒；Nginx 旧 worker 120 秒
- 监听：仅 `127.0.0.1:18080`

只读目录是 Nginx 程序、Nginx 配置、候选前端和服务脚本；可写目录只有
Nginx/WinSW 日志、PID 和五个临时目录。ACL 只在这些明确目录上重建，不
修改 `C:\NexusDeploy` 根目录、备份、状态或其他候选目录。

## 前置文件

服务器须已有：

- `C:\NexusDeploy\shared\nginx\nginx-1.31.6\nginx.exe`
- `C:\NexusDeploy\candidates\legacy-library\V2026.10.21.1__nginx-relative-paths\dist\index.html`

把本目录的 PowerShell 脚本和 Nginx 配置上传到：

```text
C:\NexusDeploy\staging\windows-nginx-service\
```

另把已固定的 `WinSW-x64-2.12.0.exe` 放入该目录。安装脚本会核对 Nginx、
WinSW 与 PowerShell 可执行文件的 SHA-256；任何版本或内容偏差都会停止，
不会用“名称相同”代替来源验证。WinSW 本身没有 Authenticode 签名，因此安
装前还要求 Microsoft Defender 已启用并对固定文件完成一次自定义扫描。

## 安装 PowerShell 7.6.6

Windows Server 2022 没有 winget。先在提升权限的 Windows PowerShell 5.1
中执行下列脚本。它只从 PowerShell 官方 GitHub Release 下载固定 x64 MSI，
同时验证官方公布的 SHA-256 和 Microsoft Authenticode 签名，然后静默安装；
不会替换系统自带的 Windows PowerShell 5.1。为保持服务运行时可复现，MSI
自动更新被关闭，后续安全升级需要按变更流程主动完成。

```powershell
Set-Location C:\NexusDeploy\staging\windows-nginx-service
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File .\install-powershell.ps1
```

如果结果为 `REBOOT_REQUIRED=True`，停止本轮并另行安排重启窗口；不能在旧
系统仍提供服务时直接重启。

## 安装

在提升权限的 PowerShell 7 中执行：

```powershell
Set-Location C:\NexusDeploy\staging\windows-nginx-service
& 'C:\Program Files\PowerShell\7\pwsh.exe' -NoLogo -NoProfile -File .\install.ps1
```

成功后服务已安装，但保持 `Stopped` / `Manual`。

## 验收

验收约需三分钟。它会验证精确首页、回环监听、虚拟账号、正常停止、手动
重启、强杀 master 后的有限恢复，以及真实 SSE 连接上的 120 秒旧 worker
退出上限。最终服务再次保持停止。80/443 监听快照必须前后一致。由于本机
同版本空闲 PowerShell 7 监督进程的工作集约为 83 MiB，验收开始前要求 ECS
至少有 256 MiB 可用物理内存；这只是本轮瞬时安全闸门，不是容量规划结论。

```powershell
& 'C:\Program Files\PowerShell\7\pwsh.exe' -NoLogo -NoProfile -File .\accept.ps1
```

结果写入：

```text
C:\NexusDeploy\evidence\windows-nginx-service\install-result.json
C:\NexusDeploy\evidence\windows-nginx-service\accept-result.json
```

本阶段通过后仍不能接管公网。域名、证书、IIS 释放 80/443 和失败回滚窗口
属于下一阶段。

## 已安装但出现默认 error.log alert 时

如果首次安装的 `nginx -t` 在成功前报告无法打开
`shared/nginx/nginx-1.31.6/logs/error.log`，不要创建可写的程序目录。上传新版
脚本后，在服务仍为停止状态时运行 `repair-bootstrap-error.ps1`。修复会让
Nginx 最早期诊断通过 `-e` 写入专属服务日志目录，同时保持程序目录只读。

动态创建或重建的日志、PID 和临时文件可以继承其受保护父目录的 ACL；验收
要求每个受控根目录阻断外部继承，并逐项确认所有后代的有效 ACL 只能包含
SYSTEM、Administrators 与对应虚拟服务账号。不能把“每个动态文件都单独
关闭继承”误当作最小权限条件。

## 低内存 ECS 的无桌面验收

如果验收进程内的可用物理内存低于 256 MiB，不降低闸门。在服务仍停止且
18080 未监听时，可用 `register-headless-accept.ps1` 注册一次性 SYSTEM 计划
任务，然后在触发前注销 RDP。计划任务仍运行同一份 `accept.ps1`，但释放
Explorer、DWM、Server Manager 等桌面会话内存；它不会停止任何业务服务。
任务最多运行 10 分钟，结果写入 `scheduled-accept.log`、
`scheduled-accept-status.json` 和原验收 JSON。检查结果后应删除一次性任务。
