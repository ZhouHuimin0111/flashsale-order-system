# HTTP API

外部入口 `http://localhost:8080/api`。JSON 编码 UTF-8。受保护接口传 `Authorization: Bearer <token>`；账号 ID 来自 Redis 会话，客户端不能指定下单用户。

## 登录

| 方法 | 路径 | 输入 / 结果 |
| --- | --- | --- |
| POST | `/auth/register` | `{"username":"alice","password":"AlicePass2026!"}` → `{token,expiresIn,user}` |
| POST | `/auth/login` | 同上。用户名 3–32 位字母数字下划线；密码 8–64 字符 |
| GET | `/auth/me` | 当前 `{id,username,role}` |
| POST | `/auth/logout` | 删除会话，其他实例同时失效 |

用户名大小写按 MySQL 默认不敏感排序规则处理。保留 `admin`；普通注册只产生 USER 角色。登录按用户名每分钟限 10 次，注册同样限流；这不是面向公网的全局注册防滥用系统。

## 优惠券与订单

| 方法 | 路径 | 登录 | 结果 |
| --- | --- | --- | --- |
| GET | `/coupons` | 否 | 优惠券列表；`availableStock=-1` 表示未初始化 |
| GET | `/coupons/{id}` | 否 | 详情；价格为分，UTC 时间 |
| PATCH | `/coupons/{id}` | ADMIN | `{"title":"新标题"}`，提交后删详情缓存 |
| POST | `/coupons/{id}/orders` | 是 | 202 `{orderId,couponId,status,duplicate}` |
| GET | `/coupons/{id}/orders/{orderId}` | 是 | 只查询当前用户订单，状态 PENDING/CONFIRMED/REJECTED |
| GET | `/orders/mine` | 是 | 最近 100 条已确认或已拒绝订单 |
| GET | `/orders/reservations` | 是 | 恢复用户的预留/已确认记录；用于 HTTP 响应丢失 |

每人每券只能有一个成功订单。重复抢购返回同一个 ID 和 `duplicate=true`，即使活动已经售罄。预检售罄返回 409，不会创建订单。预留后 DB 库存异常产生 REJECTED，附 `reason=DB_SOLD_OUT`，并关闭此活动的新预留。

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"AlicePass2026!"}'
curl -X POST http://localhost:8080/api/coupons/1/orders \
  -H 'Authorization: Bearer <token>'
curl http://localhost:8080/api/orders/reservations \
  -H 'Authorization: Bearer <token>'
```

## 社交

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/social/posts` | 3 篇演示文章，点赞数及当前用户点赞状态；公开 |
| POST | `/social/posts/{id}/like` | 原子切换点赞/取消；需登录，切换接口不要盲目重试 |
| GET | `/social/posts/{id}/likers` | 最早点赞的 5 位用户，公开 |
| GET | `/social/posts/ranking` | 按当前点赞数降序的文章排行，公开；同分按 Redis 字典序 |
| GET | `/social/users` | 最近 50 位其他用户及当前关注状态；需登录 |
| PUT | `/social/follow/{userId}` | 幂等关注 |
| DELETE | `/social/follow/{userId}` | 幂等取消关注 |
| GET | `/social/follow/{userId}/common` | 两人共同关注的用户，最多 100 位 |

社交演示数据存 Redis AOF，文章内容为代码内固定种子；没有实现用户发帖、MySQL 关系持久化和大规模分页。

## 管理与指标

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/admin/coupons/{id}/diagnostics` | ADMIN，DB 库存/订单数、Redis 库存、outbox 长度 |
| POST | `/admin/coupons/{id}/orders/{orderId}/redrive` | ADMIN，将仍为 PENDING 的同一订单重新写 outbox；修复失败原因后使用 |

容器内部订单服务 8081 提供 `/actuator/health`、`/actuator/health/readiness`、`/actuator/prometheus`。Nginx 不转发这些端点。

关键计数器：`flashsale_admission_total{result}`、`flashsale_delivery_total{status}`、`flashsale_outbox_failures_total`、`flashsale_outbox_published_total`。Delivery 计数包含重投，不能当唯一成交数；成交数以 DB 为准。

## 错误

统一错误 JSON `{ "code": "SOLD_OUT", "message": "优惠券已抢完" }`。

| HTTP | code | 处理 |
| --- | --- | --- |
| 400 | INVALID_INPUT | 校验字段格式 |
| 401 | UNAUTHORIZED / BAD_CREDENTIALS | 重新登录或检查凭据 |
| 403 | FORBIDDEN | 无管理员权限 |
| 404 | COUPON_NOT_FOUND / ORDER_NOT_FOUND / USER_NOT_FOUND | 检查资源；不能查询别人的订单 |
| 409 | SOLD_OUT / NOT_STARTED / ENDED / USERNAME_TAKEN / NOT_PENDING | 业务拒绝 |
| 429 | RATE_LIMITED | 等待一分钟 |
| 503 | NOT_READY / CACHE_BUSY / SERVICE_UNAVAILABLE | 稍后重试；提交结果不确定时先查询 reservations |
