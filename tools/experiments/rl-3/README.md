# 实验10 / RL-3：同一IP上的不同Application互相隔离

日期：2026-10-14。隔离Java实验通过。两个不同Application使用同一个观察IP，通过只改变限流桶键，对比按IP和按认证后Application ID分桶的行为。不使用真实凭证、网络、线程、Spring或数据库。

## 业界究竟按什么限流

没有统一规定只能按Application或API Key。系统通常根据要保护和分配的资源，同时设置多个层级：

| 维度 | 表示什么 | 主要解决的问题 |
| --- | --- | --- |
| IP/连接 | 网络来源或入口连接 | 未认证洪泛、连接占用、粗粒度入口保护 |
| API Key/Token | 一把凭证或客户端标识 | 单凭证异常、泄漏止损、给独立客户端单独套餐 |
| Application/Project/Account | 租户、资源与费用归属 | 多凭证共享额度、防换Key绕过、租户公平 |
| API/端点 | 被调用的能力 | 保护截图等高成本接口，与便宜接口区别定价 |
| 全局服务 | 整个实例或集群容量 | 防止所有租户合计压垮线程、连接池或依赖服务 |

这些限制可以同时存在。API Key通常回答“你拿什么凭证证明身份”，Application回答“这次消耗归属于谁”。只有产品把每把Key定义成独立购买、独立资源所有者或独立客户端额度时，Key才适合作为主要业务限流桶；即使如此，系统仍可能再设置Application或账户总上限，防止创建多把Key无限放大额度。

公开产品也采用不同边界：

- Google Cloud多数配额按Project，Project内部跨应用和IP共享；部分API另有每用户限制。
- AWS API Gateway usage plan可以按单个API Key聚合限速和配额，同时还有API、方法、账户与区域层限制。
- GitHub会按认证用户、GitHub App installation以及不同资源类别采用不同限制。

这说明设计核心不是照搬“业界都按某个字段”，而是让桶键匹配资源所有权、计费归属、公平边界和故障隔离边界。

## Nexus为什么选择Application

Nexus当前产品规则是：

- 一个Application可以有多把Key；
- Key可以新增、禁用、删除和轮换；
- Application拥有共享Quota和机器API使用归属；
- 更换Key不应产生新的速率额度；
- IP只用于观察，不代表租户，一个NAT、公司出口或代理后可能有多个Application。

因此Application是当前业务资源所有者，认证结果中的`applicationId`是限流桶键。Key ID仍适合日志、安全调查和未来可选的单Key附加上限，但不能替代Application总上限。

## 实验身份

```text
Application A: applicationId=9001, observedIp=203.0.113.9
Application B: applicationId=9002, observedIp=203.0.113.9
```

两个请求均在虚拟`10000ms`到达，放行间隔为100ms。`203.0.113.9`是文档保留地址，只用于实验。

## 对照A：错误地按IP作为业务桶

```text
Application A → IP桶203.0.113.9 → 10000ms，等待0ms
Application B → IP桶203.0.113.9 → 10100ms，等待100ms
bucketCount=1
```

Application B没有消耗Application A的业务额度，却因为共享网络出口受到牵连。现实中同一公司、校园、家庭、VPN或NAT后的多个调用者都可能出现这种误伤。

## 对照B：按认证后的Application ID分桶

```text
Application A第一次 → 桶9001 → 10000ms，等待0ms
Application B第一次 → 桶9002 → 10000ms，等待0ms
Application A第二次 → 桶9001 → 10100ms，等待100ms
Application B第二次 → 桶9002 → 10100ms，等待100ms
bucketCount=2
```

两个Application各自推进自己的`nextPermitAt`，即使观察IP相同也互不消耗业务速率。IP仍可进入日志或独立的Nginx入口保护，但不进入本轮Application业务桶键。

## 本轮边界

本实验仍使用顺序调用的`HashMap`和虚拟时间，只验证隔离身份。它没有证明并发安全、IP入口限制、10个等待上限、1秒期限、状态回收或真实HTTP Filter顺序，不能直接作为生产实现。

## 运行

从`D:/Nexus`执行：

```powershell
& .\tools\experiments\rl-3\run.ps1
```

最终输出：

```text
RL-3 PASS: two Applications on one IP used independent schedules.
```

## 参考

- [Google Cloud Quotas overview](https://docs.cloud.google.com/docs/quotas/overview)
- [Google Cloud quota project与每用户配额](https://docs.cloud.google.com/docs/quotas/set-quota-project)
- [AWS API Gateway usage plans与API Keys](https://docs.aws.amazon.com/apigateway/latest/developerguide/api-gateway-api-usage-plans.html)
- [AWS API Gateway限流层次](https://docs.aws.amazon.com/apigateway/latest/developerguide/api-gateway-request-throttling.html)
- [GitHub REST API rate limits](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api)
