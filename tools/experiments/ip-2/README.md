# IP-2：增加转发头，比较连接地址与头中地址

日期：2026-10-14。隔离实验使用与IP-1相同的Java观察程序及18080/18081端口，不连接数据库、不修改日常Nginx或Spring后端。

## 唯一的配置变化

相对IP-1，在实验 `location = /peer` 内增加：

```nginx
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header X-Forwarded-For $remote_addr;
```

Java观察程序额外显示这两个头的内容，限制为200字符并替换控制字符。它只是显示诊断输入，不把头当作已验证身份，也不改写原始peer。

## 预测

IPv6客户端用 `::1` 访问Nginx，Nginx仍用IPv4 `127.0.0.1` 连接Java。Java原始peer仍为127.0.0.1，而这两个头包含::1。

## 运行

从 `D:/Nexus` 的PowerShell执行：

```powershell
& .\tools\experiments\ip-2\run.ps1
```

默认Nginx与真实JDK可执行文件路径同IP-1，可用 `-NginxExe`、`-JavaExe` 覆盖。脚本复用IP-1的启动/观察/清理流程，只选择本实验配置和runtime目录。需要允许子进程/回环通信的环境。

## 实际观察

三个只读请求均200：

| 请求 | Java原始peer IP | X-Real-IP | X-Forwarded-For |
| --- | --- | --- | --- |
| 直接访问Java（对照） | 127.0.0.1 | 不存在 | 不存在 |
| IPv4客户端经过Nginx | 127.0.0.1 | 127.0.0.1 | 127.0.0.1 |
| IPv6客户端经过Nginx | 127.0.0.1 | ::1 | ::1 |

最后一个请求的Java输出：

```text
peerIp=127.0.0.1
peerPort=55629
localIp=127.0.0.1
localPort=18081
xRealIpPresent=true
xForwardedForPresent=true
forwardedPresent=false
xRealIp=::1
xForwardedFor=::1
```

对应Nginx日志：

```text
peer=::1:55628 listener=::1:18080 upstream=127.0.0.1:18081 status=200
```

端口是当次动态分配值；重跑不要求相同。

IP-3阶段当前Java输出已改为合并的 `peer=IP:端口`、`local=IP:端口`；上方保留IP-2当次原始字段展示。修改后IP-2重跑三个请求均200、头值符合原预期。

## 解释与边界

Nginx读取第一段连接的对端IP，写进第二段HTTP请求的两个头。Java的原始连接地址属于第二段TCP连接，不会因为增加头而变化。

当前只确认头的传递，尚未实现生产后端的可信代理识别与clientIp解析；Spring forwarded配置也未变更。读取头不等于信任头。不存在Forwarded是因为实验没有生成它，不能据此断言客户端永远不能携带它。

下一步IP-3注入假IP头，比较覆盖与追加，观察未验证的信息如何进入链路。
