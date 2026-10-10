package io.github.zhouhuimin.flashsale.order;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderWriter {
    private final JdbcTemplate db;
    public OrderWriter(JdbcTemplate db) { this.db=db; }
    // Invoked through a separate Spring bean, so @Transactional is never bypassed.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public String persist(OrderEvent event) {
        var existing = db.queryForList("SELECT id FROM coupon_order WHERE user_id=? AND coupon_id=?", event.userId(), event.couponId());
        if (!existing.isEmpty()) {
            if (!event.orderId().equals(existing.getFirst().get("id"))) throw new IllegalStateException("Conflicting reservation for same buyer; manual reconciliation required");
            return "CONFIRMED";
        }
        if (Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM order_rejection WHERE id=?)", Boolean.class, event.orderId()))) return "REJECTED";
        for (int attempt = 0; attempt < 8; attempt++) {
            var rows = db.queryForList("SELECT stock,version FROM coupon WHERE id=?", event.couponId());
            if (rows.isEmpty()) throw new IllegalStateException("Unknown coupon in message");
            int stock = ((Number)rows.getFirst().get("stock")).intValue();
            int version = ((Number)rows.getFirst().get("version")).intValue();
            if (stock <= 0) {
                db.update("INSERT INTO order_rejection(id,user_id,coupon_id,reason) VALUES (?,?,?,'DB_SOLD_OUT')", event.orderId(), event.userId(), event.couponId());
                return "REJECTED";
            }
            int changed = db.update("UPDATE coupon SET stock=stock-1,version=version+1 WHERE id=? AND version=? AND stock>0", event.couponId(), version);
            if (changed == 1) {
                // A unique/FK violation rolls back the inventory decrement in this same transaction.
                db.update("INSERT INTO coupon_order(id,user_id,coupon_id) VALUES (?,?,?)", event.orderId(), event.userId(), event.couponId());
                return "CONFIRMED";
            }
        }
        throw new IllegalStateException("Inventory CAS contention; retry message");
    }
}
