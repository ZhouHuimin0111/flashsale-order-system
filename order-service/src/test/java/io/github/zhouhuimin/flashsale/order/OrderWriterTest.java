package io.github.zhouhuimin.flashsale.order;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderWriterTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final OrderWriter writer=new OrderWriter(db);
    private final OrderEvent event=new OrderEvent(UUID.randomUUID().toString(),1,1);
    @Test void duplicateDeliveryDoesNotDeductAgain() {
        when(db.queryForList("SELECT id FROM coupon_order WHERE user_id=? AND coupon_id=?",1L,1L)).thenReturn(List.of(Map.of("id",event.orderId())));
        assertEquals("CONFIRMED",writer.persist(event));
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void conflictingReservationCannotBecomeAnotherOrder() {
        when(db.queryForList("SELECT id FROM coupon_order WHERE user_id=? AND coupon_id=?",1L,1L)).thenReturn(List.of(Map.of("id","different-order")));
        assertThrows(IllegalStateException.class,()->writer.persist(event));
    }
    @Test void contentionRetriesWithFreshVersion() {
        when(db.queryForList("SELECT stock,version FROM coupon WHERE id=?",1L)).thenReturn(List.of(Map.of("stock",2,"version",0)),List.of(Map.of("stock",1,"version",1)));
        when(db.update("UPDATE coupon SET stock=stock-1,version=version+1 WHERE id=? AND version=? AND stock>0",1L,0)).thenReturn(0);
        when(db.update("UPDATE coupon SET stock=stock-1,version=version+1 WHERE id=? AND version=? AND stock>0",1L,1)).thenReturn(1);
        assertEquals("CONFIRMED",writer.persist(event));
        verify(db).update("INSERT INTO coupon_order(id,user_id,coupon_id) VALUES (?,?,?)",event.orderId(),1L,1L);
    }
    @Test void soldOutIsDurablyRejected() {
        when(db.queryForList("SELECT stock,version FROM coupon WHERE id=?",1L)).thenReturn(List.of(Map.of("stock",0,"version",5)));
        assertEquals("REJECTED",writer.persist(event));
        verify(db).update("INSERT INTO order_rejection(id,user_id,coupon_id,reason) VALUES (?,?,?,'DB_SOLD_OUT')",event.orderId(),1L,1L);
    }
    @Test void exhaustedCasIsRetriedByBrokerInsteadOfAcknowledged() {
        when(db.queryForList("SELECT stock,version FROM coupon WHERE id=?",1L)).thenReturn(List.of(Map.of("stock",1,"version",0)));
        assertThrows(IllegalStateException.class,()->writer.persist(event));
        verify(db,times(8)).update("UPDATE coupon SET stock=stock-1,version=version+1 WHERE id=? AND version=? AND stock>0",1L,0);
    }
}
