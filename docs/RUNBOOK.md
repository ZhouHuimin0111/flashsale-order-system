# 运维与故障恢复

## 日常检查

```bash
docker compose ps -a
docker compose logs --tail=100 order-1 order-2 broker
docker compose exec redis redis-cli XPENDING 'sale:{1}:outbox' rocketmq-relay
docker compose exec redis redis-cli XLEN 'sale:{1}:outbox'
```

outbox 长度和 PEL 长期增长表示发送异常。Relay 每 200ms 尝试消费，pending 超过 30 秒可被其他实例 claim。日志包含订单 ID 与重试次数，不打印 Token 或密码。

## Broker / 应用短暂故障

保持 Redis 数据与 MySQL 数据。恢复 Broker/应用后，relay 从 stream 消费或 claim pending；消费者重试未 ACK 的 MQ 消息。用户可以使用 `/orders/reservations` 找回请求。业务状态不会因为 HTTP 超时自动回滚，因为消息发送成功与否可能不确定。

GitHub CI 在隔离环境执行 `scripts/failure-recovery.mjs`：停 Broker → 接收预留 → 验证 PENDING/outbox → 重启两个应用 → 启 Broker → 等待 CONFIRMED → 重复请求仍是同一 ID。

## 消费重试耗尽 / 死信

消费错误会返回 RECONSUME_LATER；最多 16 次后进入 RocketMQ 死信 Topic `%DLQ%flashsale-order-writers`。监控此 Topic 与应用错误日志。不要自动清除用户资格或恢复库存，旧消息可能仍会执行。

1. 找到订单 ID、优惠券、错误原因；确认 MySQL 已存在的同一订单、拒绝记录和 Redis 状态。
2. 修复 DB、数据或消息内容异常。已提交的订单查询会优先返回数据库结果，不会再次扣库存。
3. 对仍为 PENDING 的有效预留，用 ADMIN 调用 `POST /api/admin/coupons/{id}/orders/{orderId}/redrive`。
4. 重投的是同一 orderId，经过相同幂等检查。确认 DB 与用户状态后，再按 RocketMQ 运维流程处理死信记录。

重投不会创造新的用户资格或新 orderId。管理员仍需确认真实故障已修复；无限重投不能修复逻辑错误。频繁的死信应报警，不应静默当下单成功。

## DB 库存与 Redis 不符

若合法预留到达 DB 后 stock 已为 0，事务写入 `order_rejection`，状态 REJECTED，并将活动 Redis 库存关闭为 0。不会盲目增加 Redis 库存制造更多无效预留。通过 `/admin/coupons/{id}/diagnostics` 和 SQL 对账。

```sql
SELECT c.id,c.initial_stock,c.stock,COUNT(o.id) AS confirmed
FROM coupon c LEFT JOIN coupon_order o ON o.coupon_id=c.id
GROUP BY c.id,c.initial_stock,c.stock;
SELECT user_id,coupon_id,COUNT(*) FROM coupon_order
GROUP BY user_id,coupon_id HAVING COUNT(*)>1;
```

正常预留全部确认后 `stock + confirmed = initial_stock`。同一用户每券成功订单数最多 1。

## Redis 丢失

不能在开放中的活动上执行 FLUSHDB、删库存/资格/stream 或按数据库剩余库存重新初始化。数据库可能尚未看到 Redis 已接收的预留。启动代码检测到已有 DB 订单或拒绝记录时，不会自动重建缺失库存；若 Redis 丢失而预留尚未落 DB，则必须由操作者隔离活动，程序无法自行识别所有历史预留。

先停止新流量，保留 MQ 与 MySQL，恢复 Redis AOF/备份，再审计缺失记录和未确认消息。若无可靠备份，需要关闭旧活动并人工核对，创建新活动；此仓库没有自动零丢失灾难恢复承诺。

## 配置与密钥

默认 `.env.example` 仅本地演示。`.env` 已忽略；不要提交访问 Token、真实密码、连接串、Maven 本地缓存。管理员密码只初始化一次，修改配置不会重置。公网部署前做 TLS、秘密管理与完整身份策略。

Redis 和 Broker 默认持久化于命名 volume；`docker compose down` 保留。`docker compose down -v` 会永久删除当前项目的数据库、Redis 与 MQ 数据，仅在明确要销毁的隔离测试环境使用。
