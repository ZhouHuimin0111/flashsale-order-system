package io.github.zhouhuimin.flashsale.mq;

import io.github.zhouhuimin.flashsale.coupon.CouponService;
import io.github.zhouhuimin.flashsale.order.OrderEvent;
import io.github.zhouhuimin.flashsale.order.OrderKeys;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    static final String GROUP = "rocketmq-relay";
    private final String consumer = UUID.randomUUID().toString();
    private final Set<Long> initialized = ConcurrentHashMap.newKeySet();
    private volatile List<Long> couponIds = List.of();
    private final CouponService coupons;
    private final StringRedisTemplate redis;
    private final RocketMqLifecycle mq;
    private final MeterRegistry metrics;
    public OutboxRelay(CouponService coupons, StringRedisTemplate redis, RocketMqLifecycle mq, MeterRegistry metrics) { this.coupons=coupons; this.redis=redis; this.mq=mq; this.metrics=metrics; }
    @Scheduled(fixedDelay=30000, initialDelay=1000) public void refreshCatalogue() {
        try {
            couponIds=coupons.ids();
            for (long id : couponIds) {
                coupons.publishIfNew(id);
                if (!Boolean.TRUE.equals(redis.hasKey(OrderKeys.outbox(id)))) continue;
                if (!initialized.contains(id)) {
                    try { redis.opsForStream().createGroup(OrderKeys.outbox(id), ReadOffset.from("0-0"), GROUP); }
                    catch (Exception e) { if (!containsBusyGroup(e)) throw e; }
                    initialized.add(id);
                }
            }
        } catch (Exception e) { log.error("Catalogue/outbox initialization failed", e); }
    }
    private boolean containsBusyGroup(Throwable e) {
        for (Throwable cause=e; cause!=null; cause=cause.getCause()) if (String.valueOf(cause.getMessage()).contains("BUSYGROUP")) return true;
        return false;
    }
    @Scheduled(fixedDelay=200, initialDelay=2000) public void relay() {
        if (!mq.isRunning()) return;
        for (long id : couponIds) {
            if (!initialized.contains(id)) continue;
            String key = OrderKeys.outbox(id);
            try {
                var pending = redis.opsForStream().pending(key, GROUP, Range.unbounded(), 50);
                if (pending != null) {
                    var stale = pending.stream().filter(p -> p.getElapsedTimeSinceLastDelivery().compareTo(Duration.ofSeconds(30)) >= 0).map(PendingMessage::getId).toArray(RecordId[]::new);
                    if (stale.length > 0) dispatch(key, redis.opsForStream().claim(key, GROUP, consumer, Duration.ofSeconds(30), stale));
                }
                var batch = redis.opsForStream().read(Consumer.from(GROUP, consumer), StreamReadOptions.empty().count(50), StreamOffset.create(key, ReadOffset.lastConsumed()));
                dispatch(key, batch);
            } catch (Exception e) { metrics.counter("flashsale.outbox.failures").increment(); log.warn("Outbox relay failed for coupon {}; pending records will be retried", id, e); }
        }
    }
    private void dispatch(String key, List<MapRecord<String,Object,Object>> records) throws Exception {
        if (records == null) return;
        for (var record : records) {
            var fields=record.getValue();
            if (fields.containsKey("orderId")) mq.send(new OrderEvent(fields.get("orderId").toString(), Long.parseLong(fields.get("userId").toString()), Long.parseLong(fields.get("couponId").toString())));
            // ACK only after SEND_OK. A crash before ACK may duplicate delivery, never
            // lose it. XDEL runs only after ACK, with no MAXLEN trimming of pending data.
            redis.opsForStream().acknowledge(key, GROUP, record.getId());
            redis.opsForStream().delete(key, record.getId());
            metrics.counter("flashsale.outbox.published").increment();
        }
    }
}
