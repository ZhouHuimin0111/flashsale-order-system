package io.github.zhouhuimin.flashsale.order;

import io.github.zhouhuimin.flashsale.api.ApiError;
import io.github.zhouhuimin.flashsale.config.RedisConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OrderService {
    private final StringRedisTemplate redis;
    private final JdbcTemplate db;
    private final MeterRegistry metrics;
    public OrderService(StringRedisTemplate redis, JdbcTemplate db, MeterRegistry metrics) { this.redis=redis; this.db=db; this.metrics=metrics; }
    public record Reservation(String orderId, long couponId, String status, boolean duplicate) {}
    public Reservation reserve(long couponId, long userId) {
        String orderId = UUID.randomUUID().toString();
        List<?> result = redis.execute(RedisConfig.script("reserve.lua", List.class), List.of(OrderKeys.inventory(couponId), OrderKeys.buyers(couponId),
            OrderKeys.status(couponId), OrderKeys.owners(couponId), OrderKeys.outbox(couponId)), String.valueOf(userId), orderId, String.valueOf(couponId));
        if (result == null || result.isEmpty()) throw new IllegalStateException("No reservation result");
        String code = result.getFirst().toString();
        metrics.counter("flashsale.admission", "result", code).increment();
        return switch (code) {
            case "ACCEPTED" -> new Reservation(orderId, couponId, "PENDING", false);
            case "DUPLICATE" -> new Reservation(result.get(1).toString(), couponId, result.get(2).toString(), true);
            case "SOLD_OUT" -> throw new ApiError(HttpStatus.CONFLICT, code, "优惠券已抢完");
            case "NOT_STARTED" -> throw new ApiError(HttpStatus.CONFLICT, code, "活动尚未开始");
            case "ENDED" -> throw new ApiError(HttpStatus.CONFLICT, code, "活动已结束");
            default -> throw new ApiError(HttpStatus.SERVICE_UNAVAILABLE, "NOT_READY", "活动暂不可用，请稍后再试");
        };
    }
    public Map<String,Object> status(long couponId, String orderId, long userId) {
        var rows = db.queryForList("SELECT id, coupon_id AS couponId, created_at AS createdAt FROM coupon_order WHERE id=? AND user_id=? AND coupon_id=?", orderId, userId, couponId);
        if (!rows.isEmpty()) return Map.of("orderId", orderId, "couponId", couponId, "status", "CONFIRMED");
        var rejected = db.queryForList("SELECT reason FROM order_rejection WHERE id=? AND user_id=? AND coupon_id=?", orderId, userId, couponId);
        if (!rejected.isEmpty()) return Map.of("orderId", orderId, "couponId", couponId, "status", "REJECTED", "reason", rejected.getFirst().get("reason"));
        Object owner = redis.opsForHash().get(OrderKeys.owners(couponId), orderId);
        if (owner == null || !owner.toString().equals(String.valueOf(userId))) throw new ApiError(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "订单不存在");
        Object status = redis.opsForHash().get(OrderKeys.status(couponId), orderId);
        return Map.of("orderId", orderId, "couponId", couponId, "status", status == null ? "PENDING" : status.toString());
    }
    public List<Map<String,Object>> mine(long userId) {
        return db.queryForList("SELECT o.id AS orderId,o.coupon_id AS couponId,c.title,o.created_at AS createdAt,'CONFIRMED' AS status FROM coupon_order o JOIN coupon c ON c.id=o.coupon_id WHERE o.user_id=? UNION ALL SELECT r.id AS orderId,r.coupon_id AS couponId,c.title,r.created_at AS createdAt,'REJECTED' AS status FROM order_rejection r JOIN coupon c ON c.id=r.coupon_id WHERE r.user_id=? ORDER BY createdAt DESC LIMIT 100", userId, userId);
    }
    public List<Map<String,Object>> pending(long userId) {
        // Recovery lookup after HTTP response loss; catalogue is bounded in this demo.
        return db.queryForList("SELECT id FROM coupon LIMIT 1000").stream().map(row -> ((Number) row.get("id")).longValue())
            .map(id -> new java.util.AbstractMap.SimpleEntry<>(id, redis.opsForHash().get(OrderKeys.buyers(id), String.valueOf(userId))))
            .filter(entry -> entry.getValue() != null)
            .map(entry -> status(entry.getKey(), entry.getValue().toString(), userId)).toList();
    }
}
