# Windows Firewall 本地入站边界实验

## 目的

使用平板作为电脑之外的真实客户端，验证 Windows 防火墙能阻断一个原本监听所有网卡的临时 TCP 服务。实验不启动 Nexus、不连接数据库，也不修改 80、8080 或现有 Nginx 配置。

这与 DEPLOY-1 验证不同：DEPLOY-1 证明 Java 只创建回环 Socket；本实验故意让无业务测试服务监听 `0.0.0.0:18090`，从而单独观察主机防火墙。

## 预测

1. 临时 Allow 规则存在时，平板访问电脑的热点/局域网 IPv4 和 18090，应看到 `NEXUS FIREWALL EXPERIMENT ALLOW`。
2. 删除临时 Allow 并对 18090 创建任意远端来源的 Block 规则后，平板访问随机新路径应连接失败，且临时服务不应收到该请求。
3. 脚本退出后，两个实验规则、监听进程和端口 18090 均消失，80/8080 的监听快照不变。

如果第一阶段已经不能访问，可能是热点启用了客户端隔离、选错电脑 IPv4，或还有其他网络边界；此时不能把第二阶段记作防火墙流量验证通过。

## 运行

平板和电脑连接同一热点。右键 PowerShell，选择“以管理员身份运行”，然后执行：

```powershell
Set-Location D:\Nexus
& .\tools\experiments\deploy-firewall-local\run.ps1
```

脚本进入 Allow 阶段后会显示候选 URL。用平板浏览器明确输入 `http://` 地址；看到固定文本后，在电脑窗口输入 `PASS`。随后脚本创建 Block 规则，平板使用脚本给出的新 URL 再试；确认连接失败后输入 `PASS`。

正常完成、输入失败或 PowerShell 异常都会进入 `finally` 清理。如果直接结束整个管理员 PowerShell 进程导致规则残留，重新打开管理员 PowerShell执行：

```powershell
Get-NetFirewallRule -DisplayName 'Nexus Experiment Firewall * 18090' |
    Remove-NetFirewallRule
```

再确认：

```powershell
Get-NetTCPConnection -State Listen -LocalPort 18090 -ErrorAction SilentlyContinue
```

没有输出才表示测试监听已消失。这个本地实验不证明阿里云安全组或云服务器 Windows 防火墙配置正确；云端仍需从电脑对服务器公网地址单独验收。

## 已知实验记录

2026-10-18 首次运行未进入 Allow 阶段：旧版就绪检查建立 TCP 连接后立即断开，测试 HTTP Job 向已关闭连接写响应时退出，随后本机 `Invoke-WebRequest` 报无法连接。`finally` 验证两个规则和18090监听均为0，80/8080快照未变化。修复为完整HTTP就绪请求，并让测试服务容忍客户端提前断开；该次结果不能记为防火墙通过。

第二次运行中，平板在 Allow 阶段看到固定文本，但开发者报告 Block 阶段等待后仍看到该文本。旧脚本没有记录服务端实际收到的来源地址和请求路径，因此无法区分请求穿过规则、浏览器保留旧页面或访问了旧URL；该次仍不能记为通过。后续脚本在 Allow 阶段要求服务端观察到非回环请求，Block 阶段先移除 Allow、增加测试端口的 Any-source Block，并确认随机新路径没有到达监听器。

第三次运行完成有效验收。Allow 阶段临时服务记录到平板 `10.222.60.213` 对 `/allow` 的真实请求；日志还收到上一次实验随机 Block 路径的延迟请求，说明移动浏览器确实可能在稍后重试旧导航。Block 阶段改用本次新生成的随机路径，平板无痕窗口连接失败，服务端观察到的新请求数为 0。脚本最终清理临时规则与监听器，证明 Windows 防火墙能够阻断同热点平板对本机 `0.0.0.0:18090` 测试服务的入站连接。该结果仍不代表阿里云安全组或云服务器 Windows 防火墙已经配置和验收。
