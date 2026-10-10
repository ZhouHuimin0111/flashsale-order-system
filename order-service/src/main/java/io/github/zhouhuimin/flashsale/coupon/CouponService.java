package io.github.zhouhuimin.flashsale.coupon;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.zhouhuimin.flashsale.api.ApiError;
import io.github.zhouhuimin.flashsale.config.RedisConfig;
import io.github.zhouhuimin.flashsale.order.OrderKeys;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Service
public class CouponService {
    private static final Logger log = LoggerFactory.getLogger(CouponService.class);
    private final JdbcTemplate db;
    private final StringRedisTemplate redis;
    private final RedissonClient locks;
    private final ObjectMapper json;
    public CouponService(JdbcTemplate db, StringRedisTemplate redis, RedissonClient locks, ObjectMapper json) { this.db=db; this.redis=redis; this.locks=locks; this.json=json; }
    public static Coupon map(ResultSet rs, int n) throws SQLException {
        return new Coupon(rs.getLong("id"), rs.getString("title"), rs.getInt("price_cents"), rs.getInt("initial_stock"), rs.getInt("stock"), rs.getInt("version"), rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant());
    }
    public List<Long> ids() { return db.queryForList("SELECT id FROM coupon ORDER BY id LIMIT 1000", Long.class); }
    public Coupon get(long id) {
        String key = "cache:coupon:" + id;
        String cached = redis.opsForValue().get(key);
        if (cached != null) return decode(cached);
        var lock = locks.getLock("lock:cache:coupon:" + id);
        try {
            if (!lock.tryLock(1, TimeUnit.SECONDS)) throw new ApiError(HttpStatus.SERVICE_UNAVAILABLE, "CACHE_BUSY", "查询繁忙，请稍后再试");
            cached = redis.opsForValue().get(key);
            if (cached != null) return decode(cached);
            var rows = db.query("SELECT * FROM coupon WHERE id=?", CouponService::map, id);
            if (rows.isEmpty()) {
                redis.opsForValue().set(key, "null", Duration.ofSeconds(30));
                throw new ApiError(HttpStatus.NOT_FOUND, "COUPON_NOT_FOUND", "优惠券不存在");
            }
            var coupon = rows.getFirst();
            redis.opsForValue().set(key, json.writeValueAsString(coupon), Duration.ofSeconds(120 + ThreadLocalRandom.current().nextInt(60)));
            return coupon;
        } catch (ApiError e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Cache lock interrupted", e); }
        catch (Exception e) { throw new IllegalStateException("Cache read failed", e); }
        finally { if (lock.isHeldByCurrentThread()) lock.unlock(); }
    }
    private Coupon decode(String value) {
        if ("null".equals(value)) throw new ApiError(HttpStatus.NOT_FOUND, "COUPON_NOT_FOUND", "优惠券不存在");
        try { return json.readValue(value, Coupon.class); } catch (Exception e) { throw new IllegalStateException("Invalid cache", e); }
    }
    public Map<String, Object> view(long id) {
        var c = get(id);
        Object available = redis.opsForHash().get(OrderKeys.inventory(id), "stock");
        return Map.of("id", c.id(), "title", c.title(), "priceCents", c.priceCents(), "initialStock", c.initialStock(),
            "availableStock", available == null ? -1 : Integer.parseInt(available.toString()), "startsAt", c.startsAt(), "endsAt", c.endsAt());
    }
    @Transactional public void rename(long id, String title) {
        if (db.update("UPDATE coupon SET title=? WHERE id=?", title, id) != 1) throw new ApiError(HttpStatus.NOT_FOUND, "COUPON_NOT_FOUND", "优惠券不存在");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { redis.delete("cache:coupon:" + id); } catch (Exception e) { log.error("Cache invalidation failed for coupon {}; TTL will bound staleness", id, e); }
            }
        });
    }
    // Never rebuild active admission state from a possibly lagging DB snapshot.
    // Missing state with any committed/rejected order is deliberately fail-closed.
    public void publishIfNew(long id) {
        if (Boolean.TRUE.equals(redis.hasKey(OrderKeys.inventory(id)))) return;
        var lock = locks.getLock("lock:publish:" + id);
        if (!lock.tryLock()) return;
        try {
            if (Boolean.TRUE.equals(redis.hasKey(OrderKeys.inventory(id)))) return;
            Long count = db.queryForObject("SELECT (SELECT COUNT(*) FROM coupon_order WHERE coupon_id=?) + (SELECT COUNT(*) FROM order_rejection WHERE coupon_id=?)", Long.class, id, id);
            if (count != null && count > 0) { log.error("Missing Redis inventory for used coupon {}. Manual recovery required", id); return; }
            var c = db.query("SELECT * FROM coupon WHERE id=?", CouponService::map, id).getFirst();
            redis.execute(RedisConfig.script("publish.lua", Long.class), List.of(OrderKeys.inventory(id), OrderKeys.outbox(id)),
                String.valueOf(c.stock()), String.valueOf(c.startsAt().toEpochMilli()), String.valueOf(c.endsAt().toEpochMilli()));
        } finally { if (lock.isHeldByCurrentThread()) lock.unlock(); }
    }
}
