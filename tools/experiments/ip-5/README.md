# IP-5：两层代理与可信 realip 还原

日期：2026-10-14。四个只读GET请求成功，三组转发头/Java端点核对及原始代理对端日志核对通过。本轮不连接数据库，不改日常Nginx或后端配置。

## 路径名不是处理指令

`/overwrite`、`/append`、`/raw`、`/restored` 是实验入口名称；名称本身不会覆盖、追加或还原。行为由匹配location中的 `proxy_set_header`、realip等指令决定。

## 拓扑

```text
客户端 -> 外层Nginx（18080）-> 内层Nginx（18082）-> Java（18081）
```

两层由同一个隔离Nginx进程的两个server模拟，是两个独立HTTP代理跳。两层与Java仅监听本机地址；本次配置不是生产可信代理名单或隔离验收。

外层覆盖X-Real-IP/XFF为它实际观察到的客户端IP，并清除Forwarded。内层两个location均根据自身remote_addr生成发给Java的X-Real-IP/XFF，并清除Forwarded。两条路径只差是否启用：

```nginx
set_real_ip_from 127.0.0.1;
real_ip_header X-Forwarded-For;
real_ip_recursive on;
```

三行分别声明本实验前一跳的可信来源、选择读取的头、开启递归可信链解析。整个实验使用回环源地址，其他本机进程也可拥有同一源IP；不把它当作无法伪造的Nginx进程身份。

## 运行

从 `D:/Nexus` 的PowerShell执行：

```powershell
& .\tools\experiments\ip-5\run.ps1
```

Nginx已核实编译了 `--with-http_realip_module`。脚本复用IP-1的启动、观察和清理，额外检查/等待18082实验端口。参数与前序实验一致。

## 实际结果

| 请求 | 内层实际连接对端 | 内层有效 remote_addr | Java peer | Java收到的XFF |
| --- | --- | --- | --- | --- |
| IPv6经两层，无还原 /raw | 127.0.0.1:63846 | 127.0.0.1 | 127.0.0.1:63847 | 127.0.0.1 |
| IPv6经两层，有还原 /restored | 127.0.0.1:63849 | ::1 | 127.0.0.1:63850 | ::1 |
| IPv4经两层，有还原 /restored | 127.0.0.1:63852 | 127.0.0.1 | 127.0.0.1:63853 | 127.0.0.1 |

Java的local始终 `127.0.0.1:18081`。当次IPv6还原请求的三段连接：

```text
客户端 [::1]:63848 -> 外层 [::1]:18080
外层 127.0.0.1:63849 -> 内层 127.0.0.1:18082
内层 127.0.0.1:63850 -> Java 127.0.0.1:18081
```

内层日志同时保留实际连接对端与有效地址：

```text
listener=[127.0.0.1]:18082 connectionPeer=[127.0.0.1]:63849 effectiveIp=::1 effectivePort="" route=/restored upstream=127.0.0.1:18081 status=200
```

`connectionPeer` 使用 `$realip_remote_addr:$realip_remote_port`，`effectiveIp/effectivePort` 使用 `$remote_addr/$remote_port`。XFF只有IP，不包含原客户端源端口，因此还原后的有效端口为空；仍能用realip_remote_port诊断真正的代理连接源端口。不能用它冒充已知的原客户端端口。动态端口重跑会变化。

## 解释

没有还原时，内层remote_addr是外层代理的连接地址。内层再次用它覆盖XFF，会失去外层传来的客户端IP。

有还原时，内层确认实际对端属于本实验可信来源，再按XFF更新请求层的有效地址；实际socket没有变为客户端直连，原始对端也仍保留。随后proxy_set_header将有效地址继续传给Java。

Java仍从内层代理接收连接，原始peer没有自动变为::1。本轮验证Nginx请求地址还原与头的传递，尚未实现Java可信clientIp解析。

## 保留的部署问题

“公网业务请求必须经Nginx”是需要落实的部署约束，不是反向代理自带的保证。后端直连限制已作为独立待办写入 [实验计划](../../../docs/RATE_LIMIT_AND_CLIENT_IP_EXPERIMENTS.md)：单机评估回环监听，分机/容器评估私有网络与来源限制，并验证外部直连失败、入口请求成功、伪造头不能改变可信clientIp。同机进程隔离需另外定义，不承诺用回环地址或源端口实现。

下一步IP-6改变信任名单与链中的输入，观察哪些地址可被选为有效客户端IP。

## 参考

- [Nginx realip模块](https://nginx.org/en/docs/http/ngx_http_realip_module.html)
