package io.github.zhouhuimin.flashsale.order;

import io.github.zhouhuimin.flashsale.config.RedisConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class OrderConsumer {
    private final OrderWriter writer;
    private final RedissonClient locks;
    private final StringRedisTemplate redis;
    private final MeterRegistry metrics;
    public OrderConsumer(OrderWriter writer, RedissonClient locks, StringRedisTemplate redis, MeterRegistry metrics) { this.writer=writer; this.locks=locks; this.redis=redis; this.metrics=metrics; }
    public void consume(OrderEvent event) throws InterruptedException {
        if (event.orderId() == null || !event.orderId().matches("[0-9a-f-]{36}") || event.userId() <= 0 || event.couponId() <= 0) throw new IllegalArgumentException("Invalid order event");
        var lock = locks.getLock("lock:buyer:" + event.couponId() + ":" + event.userId());
        // No explicit leaseTime: Redisson watchdog extends the lock while the client lives.
        if (!lock.tryLock(2, TimeUnit.SECONDS)) throw new IllegalStateException("Buyer is being processed; retry");
        try {
            String status = writer.persist(event); // Returns only after the DB transaction commits.
            redis.execute(RedisConfig.script("complete.lua", Long.class), List.of(OrderKeys.inventory(event.couponId()), OrderKeys.buyers(event.couponId()),
                OrderKeys.status(event.couponId())), String.valueOf(event.userId()), event.orderId(), status);
            metrics.counter("flashsale.delivery", "status", status).increment();
        } finally { if (lock.isHeldByCurrentThread()) lock.unlock(); }
    }
}
