# 实验9 / RL-2：同一Application的多把Key共享限流桶

日期：2026-10-14。隔离Java实验通过。实验使用两个不同API Key ID，但它们的认证结果包含同一个Application ID；通过只改变限流桶键，比较按Key和按Application分桶的行为。不使用真实凭证、HTTP、线程、Spring或数据库。

## 为什么业务限流在Java

Nexus要求同一Application的所有Key共享额度。入口Nginx在API Key认证前只看到连接信息、路径和客户端提交的请求头，并不知道一把有效Key在数据库中属于哪个Application。

如果按`Authorization`或Key ID分桶：

```text
Key A → 桶A
Key B → 桶B
```

调用者更换同一Application下的Key就能获得另一份额度。若让Nginx自行查询或同步Key到Application的映射，就会把认证状态、禁用状态和数据库事实复制到代理层，给当前单体项目增加不必要的一致性与运维复杂度。

当前Nexus认证成功后已经产生：

```java
ApiKeyIdentity(Long apiKeyId, Long applicationId)
```

所以Application限流的可信边界应位于认证之后：

```text
客户端
  → Nginx入口保护
  → MachineApiKeyFilter验证完整Key
  → ApiKeyIdentity(applicationId)
  → Application限流
  → Controller / Quota / 业务
```

本实验没有把限流器接入Filter链；这里只验证限流身份必须取认证结果中的`applicationId`，不能取未经认证的客户端参数或原始Key字符串。

Nginx以后仍可增加按连接IP、连接数、路径或请求大小的粗粒度入口保护。它发生在认证前，可以减轻无效Key洪泛对Java和认证查询的压力；Java的Application限流处理认证成功后的业务身份滥用。两层可以同时存在。

## 实验身份

```text
Key A: apiKeyId=101, applicationId=9001
Key B: apiKeyId=102, applicationId=9001
```

这些只是ID，不包含API Key Secret。两个请求都在虚拟`10000ms`到达，放行间隔沿用RL-1的100ms。

## 对照A：错误地按apiKeyId分桶

```text
Key A → bucketId=101 → 10000ms，立即
Key B → bucketId=102 → 10000ms，立即
bucketCount=2
```

两把Key各自创建一张时隙表。换Key后得到第二个立即时隙，违背“同一Application共享”的业务规则。

## 对照B：按认证后的applicationId分桶

```text
Key A → bucketId=9001 → 10000ms，等待0ms
Key B → bucketId=9001 → 10100ms，等待100ms
Key A → bucketId=9001 → 10200ms，等待200ms
bucketCount=1
```

两把Key都推进Application 9001的同一个`nextPermitAt`。调用者更换Key不会得到新的时隙表。

## 本轮边界

实验使用顺序调用的`HashMap`和时隙表，只验证分桶身份。它还没有证明：

- 两个不同Application互不影响；
- 同一IP上的不同Application仍然独立；
- 并发创建同一Application桶时只创建一次；
- 10个等待容量、1秒期限和429；
- 空闲Application状态如何回收；
- 认证失败的401/403是否完全绕过Application队列。

这些变量分别留给后续RL和HTTP实验，当前类不能直接复制成生产实现。

## 运行

从`D:/Nexus`执行：

```powershell
& .\tools\experiments\rl-2\run.ps1
```

最终输出：

```text
RL-2 PASS: two Keys of one authenticated Application shared one schedule.
```
