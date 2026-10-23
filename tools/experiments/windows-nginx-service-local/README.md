# Windows Nginx 服务账号与 ACL 实验

## 一句话模型

WinSW 把原本只能作为控制台程序运行的 Windows 版 Nginx 包装成 Windows 服务；服务使用系统自动管理的虚拟账号 `NT SERVICE\NexusNginxLab`，只读 Nginx 程序、配置和隔离 `dist`，只写日志、PID 与临时目录。WinSW 托管 PowerShell 7 监督进程，监督进程在每次启动 Nginx 前精确清理本实验留下的孤儿进程。

## 本轮边界

- 服务名 `NexusNginxLab`，启动类型 `Manual`；
- 隔离监听 `127.0.0.1:18084`，不修改 80、443、8080；
- 复制 `D:\NexusDeploy\current\console\dist`，不修改真实部署目录 ACL；
- 复用已固定哈希的 WinSW 2.12.0；
- 固定本机现有 `nginx/1.31.6` 可执行文件哈希；
- 验证精确返回 `dist/index.html`、日志可写、`dist` 对服务账号不可写；
- 验证正常停止、管理员手动重启和异常退出恢复；
- 失败告警不在本轮范围。

## 恢复前精确清理

Windows 版 Nginx master 被强制结束后，worker 可能继续存在。监督进程只把同时满足下列条件的进程列入可终止集合：

1. 可执行文件绝对路径等于实验目录中的 `nginx.exe`；
2. 命令行含精确的实验 `-p` 前缀；
3. 进程所有者等于 `NT SERVICE\NexusNginxLab`。

只要相关进程有一项不匹配，监督进程就拒绝启动新 Nginx 并保留现场。它不使用按名称全局终止的 `taskkill /IM nginx.exe`，终止后最多等待 10 秒并重新枚举实际进程；审计只记录时间、动作与 PID，写入 `logs\service\recovery-cleanup.log`。

没有使用 WinSW 2.12.0 的 `prestart`：真实实验中，prestart 命令本身成功后，低权限虚拟账号仍因 WinSW 尝试访问服务控制管理器而得到“拒绝访问”。扩大该账号的 SCM 权限不符合最小权限目标。当前结构为：

```text
Windows Service / WinSW
  -> PowerShell 7 supervisor
       -> 精确清理旧实验 Nginx
       -> Nginx master
            -> Nginx worker
```

## 管理员命令

运行完整实验：

```powershell
Set-Location D:\Nexus
& 'C:\Program Files\PowerShell\7\pwsh.exe' -NoProfile -File `
  .\tools\experiments\windows-nginx-service-local\run.ps1
```

实验结束后服务保留为已安装、已停止、Manual：

```powershell
Get-Service NexusNginxLab
Start-Service NexusNginxLab
Stop-Service NexusNginxLab
Restart-Service NexusNginxLab
```

彻底清理：

```powershell
& 'C:\Program Files\PowerShell\7\pwsh.exe' -NoProfile -File `
  .\tools\experiments\windows-nginx-service-local\cleanup.ps1
```

完整证据保存在忽略 Git 的 `runtime/lab/evidence`。

2026-10-20 最终复验中，正常停止保持停止，手动重启成功；强制结束 master PID 23824 后，审计精确记录并清理旧 worker PID 12612，新 master PID 2708 恢复并返回 HTTP 200。最终服务为 `Stopped`/`Manual`，18084 监听和实验 Nginx 进程均为 0，监督进程 stderr 为 0 字节。失败告警仍是后续独立边界。
