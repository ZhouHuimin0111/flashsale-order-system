# 简历描述与面试准备

项目名称：**高并发优惠券订单处理系统**

源码：https://github.com/ZhouHuimin0111/flashsale-order-system

技术栈：Java 21、Spring Boot、Spring Cloud Gateway/LoadBalancer、Redis、Redisson、RocketMQ、MySQL、Docker、JUnit、Testcontainers。

## 简历精简版

完成前后端分离的优惠券秒杀系统，支持双实例部署、用户登录、每人每券一单、异步订单确认、点赞排行与共同关注。

- 通过 Redis 共享 Token 会话，拦截器结合 Lua 完成登录校验与滑动过期刷新；采用 Cache Aside、空值缓存、随机 TTL 与分布式互斥重建降低热点回源。
- 使用 Redis Lua 原子校验活动时间、库存和用户资格，预扣时同步写入 Redis Stream outbox；经 RocketMQ 异步消费，并恢复发送失败及进程重启后的 pending 事件。
- 使用 Redisson watchdog 分布式锁、订单主键/用户券唯一索引、库存版本 CAS 与数据库事务，实现防超卖及消息重复投递下的幂等落单。
- 编写真实 Redis/MySQL 并发测试、双实例 RocketMQ 端到端测试与 Broker 故障恢复测试，接入 GitHub Actions 自动验证与报告归档。

不要在没有测量证据时添加 QPS、延迟下降百分比或“亿级用户”描述。详细的 CI 报告可支持测试条件下的功能与并发正确性结论，不代表线上流量经验。仅在自己运行、理解并能讲清代码后使用“设计/实现”等表述。

可引用的已验证结果：14 项后端测试通过；双实例下 60 用户、120 请求、30 并发争抢 20 份库存，最终生成 20 个订单、无重复买家；Broker 停机与两个应用重启后原预留恢复。具体环境与证据见[验证记录](VERIFICATION.md)。

## 面试必须能回答

1. **Lua 已经一人一单，为什么还需要 DB 唯一索引？** Lua 只保护 Redis 原子边界；重复消息、锁失效或错误的恢复流程不能突破数据库最后的唯一约束。
2. **为什么只预扣再 send MQ 不够？** 进程可能在预扣后发送前崩溃；本项目把 outbox 事件与资格/库存同写 Redis，relay 再可靠发送。
3. **能不能收到超时就把库存加回去？** 不能，发送结果不确定时消息可能仍执行；应查询同一预留，而非创建新 ID 或盲目回滚。
4. **分布式锁在哪释放？** `OrderWriter` 通过 Spring 代理提交数据库事务后，调用者才解锁；watchdog 续约不是 fencing token，唯一索引仍必要。
5. **乐观锁具体 SQL？** `UPDATE coupon SET stock=stock-1,version=version+1 WHERE id=? AND version=? AND stock>0`；冲突后在 READ_COMMITTED 读最新版本重试。
6. **为什么 MQ 至少一次不会重复扣库存？** 同一 orderId 重投先查已提交订单，返回成功；扣库存和插订单同一事务，插入失败会回滚。
7. **缓存能否强一致？** 不能。删除失败或旧读回填都可产生短暂旧值；TTL 给出详情缓存的过期边界。秒杀资格不用详情缓存库存。
8. **Redis Cluster 可以直接上线吗？** 脚本键已有同槽 hash tag，但当前 Redisson/Lettuce 配置是单节点，需要额外 Cluster 部署与客户端配置。热点优惠券仍集中一个槽。
9. **Redis 丢失怎么办？** AOF与备份是前提；永久数据丢失不能只按DB库存盲目恢复开放活动。本项目不承诺跨存储零丢失容灾。
10. **RocketMQ 与 RabbitMQ 区别？** 此项目使用 Topic/Tag/Consumer Group，消息失败重试和死信；没有交换机绑定。

## 个人复现建议

先运行 Compose，创建两个账号完成下单和关注。阅读 `reserve.lua`、`OutboxRelay`、`OrderConsumer`、`OrderWriter`，追踪一个 orderId 全链路。然后运行隔离 CI 故障测试，核对日志与数据库，最后将自己实际验证的测试环境和结果用于面试。
