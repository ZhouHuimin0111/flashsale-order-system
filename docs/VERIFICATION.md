# 可复核验证记录

执行日期：2026-10-10（北京时间）。

被测业务代码提交：[`23c510c8551f9df62a3b018052ed8687e4e9e92c`](https://github.com/ZhouHuimin0111/flashsale-order-system/commit/23c510c8551f9df62a3b018052ed8687e4e9e92c)。

完整 CI：<https://github.com/ZhouHuimin0111/flashsale-order-system/actions/runs/38035311954>，结论 **success**。

运行环境：GitHub Actions Ubuntu 24.04、Java 21，单 MySQL 8.4、Redis 7.4、RocketMQ 5.3.2 Broker、两个订单实例、Spring Cloud 网关与 Nginx。RocketMQ Java 客户端 5.3.4。所有用户和数据都是隔离测试生成的样本。

## 后端测试

| 测试组 | 数量 | 失败 | 错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: |
| OrderWriterTest 单元测试 | 5 | 0 | 0 | 0 |
| CoreConcurrencyIT 真 Redis/MySQL | 9 | 0 | 0 | 0 |

覆盖重复消息、不一致资格、CAS 冲突、售罄持久化拒绝、200 请求原子资格预检、双 Redis 锁客户端消费、绕过预检的 DB 防超卖、插入失败回滚、活动时间限制、共享会话刷新/注销、响应丢失恢复、管理员同 ID 重投。

## 完整链路

原始数据：[e2e.json](verification/e2e.json)。

| 项目 | 实际结果 |
| --- | ---: |
| 用户 | 60 |
| 总请求 / 并发上限 | 120 / 30 |
| 初始库存 | 20 |
| 新预留 / 重复返回 / 售罄拒绝 | 20 / 20 / 80 |
| RocketMQ 消费后确认订单 | 20 |
| 通过同一 Token 访问的后端实例 | 2 |
| 独立 SQL：剩余库存 / 成交 / 重复买家 | 0 / 20 / 0 |

脚本还核验未登录拒绝、其他用户无法读取订单、普通用户无法修改优惠券、点赞切换和热度排行、Set 共同关注、注销后共享会话失效。

JSON 内 admissionRequestsPerSecond=137.66，p50/p95/p99=158/465/492ms，包含重复与拒绝响应。样本很小、环境共享、只执行一次，不构成稳定 QPS、容量上限或性能优化收益证据。

## 故障恢复

原始数据：[failure-recovery.json](verification/failure-recovery.json)。

Broker 停机后预留仍为 PENDING 且 outbox 中有待发送事件；两个应用进程均重启；Broker 与两个实例恢复就绪后，同一预留最终 CONFIRMED，重复请求返回同一 ID。

验证中发现并修复了 Broker 命名 volume 权限和容器重启后的网关 DNS 缓存问题。Redis 数据保留是此次恢复的前提；没有模拟永久丢失 AOF 或跨机房故障。

## 浏览器

Chromium 两个独立场景：1440×1000 桌面、390×844 手机。均通过注册 → 下单 → 订单确认 → 点赞 → 退出 → 未登录订单提示，并检查未出现 JavaScript pageerror 和最终页面横向溢出。

- [桌面首页](screenshots/desktop-home.png)
- [桌面订单](screenshots/desktop-order.png)
- [桌面社交](screenshots/desktop-community.png)
- [手机首页](screenshots/mobile-home.png)

截图来自实际服务和真实测试账户。#3 显示售罄、#2 显示 49/50 是前置 E2E 和故障恢复实际消耗后的状态。

CI 的 `verification-reports` artifact 提供 XML、完整容器日志、网络地址和浏览器报告，保留 14 天。此目录永久保存选定 JSON 与页面截图；文档与截图归档提交不改变被测业务代码。
