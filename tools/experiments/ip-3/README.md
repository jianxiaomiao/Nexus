# IP-3：伪造请求头，比较覆盖与追加

日期：2026-10-14。仅使用隔离Nginx和Java观察程序，不连接数据库，不修改日常入口或生产后端的可信IP策略。

## 请求与预测

使用curl从IPv6回环地址连接Nginx，主动发送以下诊断测试头：

```text
X-Real-IP: 192.0.2.7
X-Forwarded-For: 203.0.113.7, 198.51.100.9
Forwarded: for=192.0.2.7
```

这些地址只是客户端声明的测试值，不是真实连接来源，也不代表请求真的经过那些代理。

两个实验入口使用相同后端、相同请求头与相同X-Real-IP策略，只改变XFF处理：

```nginx
# /overwrite
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header X-Forwarded-For $remote_addr;

# /append
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
```

两个入口均使用 `proxy_pass http://127.0.0.1:18081/peer;` 将实验路径交给同一个Java观察处理器。这里明确指定后端URI，属于实验路径映射；不修改业务API转发规则。两种配置均故意不处理Forwarded，以观察独立请求头默认传递的行为。

## 运行

从 `D:/Nexus` 的PowerShell执行：

```powershell
& .\tools\experiments\ip-3\run.ps1
```

启动、端口检查和清理复用IP-1脚本；本轮自定义四组带预期值核对的请求。另有启动时无假头的直接Java对照，总计五个GET请求。使用18080/18081实验端口，runtime日志已忽略。

## 2026-10-14 实际结果

配置检查通过，五个请求均成功，四组比较的实际端点和头值均符合预测；IP-2的三个对照请求重跑也通过。

| 请求 | Java peer | X-Real-IP | X-Forwarded-For | Forwarded |
| --- | --- | --- | --- | --- |
| 直接Java，无假头 | 127.0.0.1:63308 | 不存在 | 不存在 | 不存在 |
| 直接Java，带假头 | 127.0.0.1:63314 | 192.0.2.7 | 203.0.113.7, 198.51.100.9 | for=192.0.2.7 |
| Nginx覆盖，带假头 | 127.0.0.1:63316 | ::1 | ::1 | for=192.0.2.7 |
| Nginx追加，带假头 | 127.0.0.1:63318 | ::1 | 203.0.113.7, 198.51.100.9, ::1 | for=192.0.2.7 |
| Nginx追加，无假头 | 127.0.0.1:63320 | ::1 | ::1 | 不存在 |

Java的local均为 `127.0.0.1:18081`。peer的源端口是当次分配值，重跑会变化。

追加并带假头这一组完整链路为：

```text
客户端 [::1]:63317 -> Nginx [::1]:18080
Nginx 127.0.0.1:63318 -> Java 127.0.0.1:18081
```

对应Nginx日志：

```text
peer=[::1]:63315 listener=[::1]:18080 route=/overwrite upstream=127.0.0.1:18081 status=200
peer=[::1]:63317 listener=[::1]:18080 route=/append upstream=127.0.0.1:18081 status=200
peer=[::1]:63319 listener=[::1]:18080 route=/append upstream=127.0.0.1:18081 status=200
```

## 学习结论

- 追加只保留收到的字符串再添加当前对端，不验证整条链。不能直接把最左IP当身份，也不能从它推断真实代理跳数。
- 当前直连入口覆盖XFF会去掉客户端自行填写的链；覆盖X-Real-IP也去掉相应假值。
- 修改这两个头不会同时清理Forwarded。后续可信IP设计必须明确采用哪些头，对其他头选择忽略、清除或规范化，不能无条件择一回退。
- Java的原始peer始终由实际连接决定，不被伪造HTTP头改变。本轮只观察值，没有实现可信clientIp解析。
- peer/local输出已改成完整IP与端口；IPv6端点用方括号。转发头仍按其语义记录IP，不擅自添加连接源端口。

下一步IP-4验证后端直连的信任边界；直接读取头会收到任意客户端自填值，这一点本轮对照已经观察到，但生产端口隔离与可信来源识别尚未实现。
