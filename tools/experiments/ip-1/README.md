# IP-1：观察两段连接的对端地址

这是单层代理、没有配置 IP 转发头的隔离实验。Java 使用 JDK HttpServer 观察原始连接，不是 Nexus 的 Spring Controller；它与 Servlet `getRemoteAddr()` 要观察的概念相同，但未验证未来 Servlet 包装器或 forwarded 配置的行为。

不连接数据库、不使用凭证、不修改日常 Nginx 配置或后端；Nginx 只监听本机 18080，Java 只监听本机 IPv4 18081。

## 预测

Nginx 看到客户端到 Nginx 的连接对端；Java 看到 Nginx 到 Java 的连接对端。Java 看到的地址来自 socket，不是 Nginx 写入请求头的值。

## 运行

从 `D:/Nexus` 的 PowerShell 执行：

```powershell
& .\tools\experiments\ip-1\run.ps1
```

脚本默认使用 `D:/nginx/nginx-1.31.6/nginx.exe` 和真实 JDK 可执行文件 `D:/java21/bin/java.exe`；其他位置用 `-NginxExe`、`-JavaExe` 指定。需要 Java 21、curl.exe、本机 IPv6回环和允许子进程/回环通信的执行环境。

脚本检查端口空闲、创建忽略的 runtime 目录、检查 Nginx 配置、启动实验进程、发三个只读请求，最后只关闭本轮进程。日志位于 `runtime/`。Java直接使用源码启动，不需要Maven或额外依赖。

Windows Oracle javapath 启动器在本环境会派生真实 JVM；因此使用真实 JDK 路径，保证启动和清理的是同一个实验进程。

## 2026-10-14 实际结果

在允许子进程及回环通信的环境完成一次运行，三个请求均返回200。

| 请求 | Nginx观察到的对端 | Java观察到的对端 | Java端转发头 |
| --- | --- | --- | --- |
| 直接访问Java | 未经过Nginx | `127.0.0.1:49950` | 三种均不存在 |
| IPv4客户端经Nginx | `127.0.0.1:49955` | `127.0.0.1:49956` | 三种均不存在 |
| IPv6客户端经Nginx | `[::1]:49957` | `127.0.0.1:49958` | 三种均不存在 |

三种头指 `X-Real-IP`、`X-Forwarded-For`、`Forwarded`。连接端口为当次动态分配值，重跑会变化。

IP-3阶段按开发者要求将当前输出合并为 `peer=IP:端口`、`local=IP:端口`，IPv6端点带方括号；下方保留IP-1当次原始输出，重跑的字段展示会不同。

Nginx实际记录：

```text
peer=127.0.0.1:49955 listener=127.0.0.1:18080 upstream=127.0.0.1:18081 status=200
peer=::1:49957 listener=::1:18080 upstream=127.0.0.1:18081 status=200
```

最后一个请求的Java返回：

```text
peerIp=127.0.0.1
peerPort=49958
localIp=127.0.0.1
localPort=18081
xRealIpPresent=false
xForwardedForPresent=false
forwardedPresent=false
```

## 解释与限制

客户端经IPv6连接Nginx，而Nginx配置通过IPv4连接Java，所以两端观察到的对端IP不同。没有生成转发头，Java无法从本次请求中知道原客户端是 `::1`。

全IPv4时两端IP恰好都是 `127.0.0.1`，不能据此判断Java看到了原客户端；两段连接的端口也不同。本机实验不能推断用户公网地址。

首次运行遇到runtime缺少temp父目录，已经补齐；随后在受限执行环境中代理请求5秒超时，记录499，不能计作通过。清理时发现Oracle启动器派生的JVM仍在，核实源码路径后清理；脚本改用真实JDK路径，允许回环通信后重跑通过。现有业务配置未因此调整。

下一步IP-2只改变一个变量：让Nginx生成转发头，比较Java原始对端与头中地址；生成头本身不会自动改变Java原始连接对端。
