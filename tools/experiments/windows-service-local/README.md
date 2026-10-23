# Windows Java 服务账号与恢复实验

## 边界

本实验只验证一个 Java 服务：

- 服务名 `NexusJavaLab`；
- 虚拟服务账号 `NT SERVICE\NexusJavaLab`；
- 手动启动，不随 Windows 开机启动；
- 真实 Nexus JAR、`local` Profile、专用 `nexus_test`；
- 仅监听 `127.0.0.1:18082`；
- 正常停止不重启，手动重启产生新 Java PID；
- 真正监听 18082 的 Java 进程异常退出后由 Windows 服务恢复一次；
- 不修改 `D:\NexusDeploy`、日常 80/8080、仓库本地配置或数据库结构。

Nginx 服务账号和权限不在本轮范围，避免同时改变两个变量。

实验必须使用 `JAVA_HOME\bin\java.exe` 或固定的 `D:\java21\bin\java.exe`。Oracle `javapath\java.exe` 是转发程序，会让 WinSW 跟踪外层 PID 而实际 Spring JVM 成为下一层进程；这会削弱停止和异常恢复的进程树保证，因此脚本明确拒绝它。

## WinSW 来源

固定使用官方 GitHub Release `v2.12.0` 的 `WinSW-x64.exe`：

```text
URL: https://github.com/winsw/winsw/releases/download/v2.12.0/WinSW-x64.exe
Bytes: 18243033
SHA-256: 05B82D46AD331CC16BDC00DE5C6332C1EF818DF8CEEFCD49C726553209B3A0DA
Authenticode: NotSigned
```

下载文件位于忽略的 `runtime/`。首次执行前已经使用启用实时防护且病毒库为 2026-10-19 的 Microsoft Defender 做指定文件扫描，未发现匹配威胁。未签名状态不会被描述成签名验证通过。

## 运行

`run.ps1` 必须由管理员 PowerShell 执行。脚本拒绝覆盖已有同名服务或已有实验目录：

```powershell
Set-Location D:\Nexus
& .\tools\experiments\windows-service-local\run.ps1
```

脚本结束时服务保留为已安装、已停止、启动类型 Manual。常用练习命令：

```powershell
Start-Service NexusJavaLab
Stop-Service NexusJavaLab
Restart-Service NexusJavaLab
Get-Service NexusJavaLab
```

不要直接修改生成的 WinSW XML 或测试配置后启动服务；若要重新实验，先运行管理员清理命令：

```powershell
& .\tools\experiments\windows-service-local\cleanup.ps1
```

清理脚本卸载服务并删除本轮 `runtime/lab`，但保留已下载并固定哈希的 WinSW 原文件。

## 权限结果

实验根目录关闭继承，只显式保留当前操作者、`SYSTEM`、`BUILTIN\Administrators` 和虚拟服务账号。虚拟账号对整个实验目录只有读取和执行，对 `logs/` 与 `temp/` 额外拥有修改权限。它不能修改 JAR、配置或 WinSW XML。

测试配置含专用测试库密码和一次性 JWT，仅存在于忽略的实验目录，ACL 限制为管理员和服务账号可读；脚本和日志不得输出其内容。

## 2026-10-19 实验结果

严格复跑通过，最终采用的证据如下：

```text
START: service=Running javaPid=4836 owner=NT SERVICE\NexusJavaLab health=401
STOP: deliberate service stop remained stopped; no recovery restart occurred.
MANUAL-RESTART: oldJavaPid=33344 newJavaPid=35636 health=401
FAILURE-RECOVERY: killedJavaPid=35636 recoveredJavaPid=29124 health=401
FINAL: service=Stopped startMode=Manual port18082Listeners=0
```

- 服务最终仍已安装，但状态为 `Stopped`、启动类型为 `Manual`，因此不会开机自动启动；
- 启动、手动重启和异常恢复后的 HTTP 探针均得到预期认证 `401`；
- 人为结束真正监听 18082 的 JVM 后，WinSW 按失败策略启动了新 JVM；正常 `Stop-Service` 不触发恢复；
- WinSW 日志中的子进程 PID 与 Spring 日志中的进程 PID 一致，且端口所有者的父进程为 `NexusJavaLab.exe`；
- release 与配置只读，`logs\java`、服务包装器日志和 `temp` 可写；敏感日志模式命中 0；
- 80、443、8080、18080、18081、18082 均无实验残留监听，`D:\NexusDeploy` 的 V1 指针未改变。

第一次看似通过的运行不作为有效证据：它使用了 Oracle `javapath\java.exe` 转发器，WinSW 跟踪的是外层转发进程而非真正监听端口的 JVM，清理时因此发现一个孤儿 JVM。该进程已按命令行与监听端口精确确认后清理。脚本随后改为只接受直接的 Java 21 可执行文件，并把“监听 JVM 必须是 WinSW 直接子进程”加入断言，以上结果来自修复后的完整复跑。
