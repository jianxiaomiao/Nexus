# IP-7：转发头边界输入、IPv6 与日志转义

日期：2026-10-14。一次直接Java对照和九个Nginx请求完成；脚本对HTTP状态、Java收到的有效地址、Nginx原始对端/有效地址、IPv6端口和日志物理行数执行了断言。实验只使用18080/18081回环端口，不连接数据库，不修改日常入口或业务后端。

## 固定信任与上限

本实验只信任：

```nginx
set_real_ip_from 127.0.0.1;
set_real_ip_from 198.51.100.20;
real_ip_header X-Forwarded-For;
real_ip_recursive on;
```

`127.0.0.1`是实验请求与Nginx建立连接的实际对端；`198.51.100.20`模拟XFF中离当前Nginx最近的一层可信代理。两者都是实验身份，不能直接复制成生产可信名单。

请求头缓冲区在实验配置中显式设为：

```nginx
client_header_buffer_size 1k;
large_client_header_buffers 2 8k;
```

因此一个请求头字段必须放入单个8 KiB缓冲区。超过该边界时Nginx返回400，不连接Java。

## 实际结果

| 场景 | 收到的XFF要点 | Nginx有效IP | 结果 |
| --- | --- | --- | --- |
| 缺失 | 没有XFF | `127.0.0.1` | 保留真实连接对端，200 |
| 两行重复XFF | 两行被组合为逗号链 | `192.0.2.10` | 越过可信的`198.51.100.20`后停止，200 |
| 最右侧畸形 | `203.0.113.7, not-an-ip` | `127.0.0.1` | 无法从右侧开始解析，保留真实对端，200 |
| 左侧畸形 | `not-an-ip, 198.51.100.20` | `198.51.100.20` | 先解析了右侧地址，再遇到畸形值，保留已解析地址，200 |
| IPv6 | `2001:db8::7, 198.51.100.20` | `2001:db8::7` | IPv6地址被还原，200 |
| IPv6和端口 | `[2001:db8::8]:4321, 198.51.100.20` | IP `2001:db8::8`，端口`4321` | 从可信头采用地址与端口，200 |
| 制表符 | 左侧地址后带TAB，右侧为可信代理 | `198.51.100.20` | 畸形部分未成为有效IP；日志将TAB写成字面量`\t`，200 |
| 4 KiB畸形值 | 仍在单字段8 KiB限制内 | `127.0.0.1` | HTTP层接受，realip解析失败后回退，200 |
| 9 KiB单字段 | 超过单字段8 KiB限制 | `127.0.0.1` | Nginx返回400，`upstream`为空，Java未被调用 |

重复XFF不会因为“重复”而自动拒绝。在本次Nginx 1.31.6实验中，两行被组合为：

```text
203.0.113.7, 192.0.2.10, 198.51.100.20
```

然后由同一套从右向左的可信代理规则处理。

畸形头也不是只有一种回退结果。最右端首先解析失败时，Nginx保留实际连接对端；已经从右侧解析出地址后才遇到畸形内容时，它保留已经解析到的地址。本实验没有把`not-an-ip`或带TAB的字符串当作有效IP，但结果可能停留在可信代理地址，因此不能根据“请求返回200”推断整条XFF格式正确。

## Java看到什么

实验入口始终执行：

```nginx
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header X-Forwarded-For $remote_addr;
```

因此Java只收到Nginx最终采用的有效地址，不收到原始重复、畸形或超长XFF。即使客户端未提供XFF，Java也收到Nginx观察到的`127.0.0.1`。Java的socket peer仍是Nginx建立的`127.0.0.1:动态端口`。

带端口IPv6必须使用RFC 3986形式`[2001:db8::8]:4321`。Nginx将头里声明的地址放入`$remote_addr`、声明的端口放入`$remote_port`；转发给Java的两个IP头当前只写地址，所以响应中不带4321，Nginx日志能看到采用后的端口。这表示可信代理提供了有效请求元数据，不会改变实际TCP连接，也不能由本实验独立证明4321确实是最初客户端的源端口；实际直接对端及端口仍由`$realip_remote_addr:$realip_remote_port`保留。

## 日志结论

实验日志使用：

```nginx
log_format ip_observation escape=json ...;
```

九个请求各自产生且只产生一条物理日志行；制表符被转义为`\t`，不能切开或伪造新日志行。超出8 KiB的单字段请求记录为400，并且原始XFF未进入日志变量。

4 KiB合法长度的畸形字段仍生成了4294字符的日志行。这说明转义解决控制字符问题，却不解决日志体积问题。生产环境只需要追踪地址时，应优先记录`$realip_remote_addr`和解析后的`$remote_addr`，不要常态记录完整原始XFF；确需诊断原始值时还要设置采样、长度或日志管道限制。

## 运行

从`D:/Nexus`的PowerShell执行：

```powershell
& .\tools\experiments\ip-7\run.ps1
```

脚本每次先清理自己的旧日志，再复用IP-1的启动和进程清理流程。它不会修改日常Nginx配置。第一次编写时，PowerShell函数的说明输出被错误收进结果数组，HTTP请求本身已完成但核对阶段失败；修正为独立结果集合后完整重跑通过，未隐藏初次脚本问题。

## 参考

- [Nginx realip模块](https://nginx.org/en/docs/http/ngx_http_realip_module.html)
- [Nginx日志转义](https://nginx.org/en/docs/http/ngx_http_log_module.html#log_format)
- [Nginx大请求头缓冲区](https://nginx.org/en/docs/http/ngx_http_core_module.html#large_client_header_buffers)
