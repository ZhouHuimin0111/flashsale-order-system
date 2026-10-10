package io.github.zhouhuimin.flashsale.order;

import io.github.zhouhuimin.flashsale.auth.CurrentUser;
import io.github.zhouhuimin.flashsale.api.ApiError;
import io.github.zhouhuimin.flashsale.config.RedisConfig;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/admin")
public class RecoveryController {
    private static final Logger log=LoggerFactory.getLogger(RecoveryController.class);
    private final StringRedisTemplate redis;
    private final JdbcTemplate db;
    public RecoveryController(StringRedisTemplate redis,JdbcTemplate db) { this.redis=redis;this.db=db; }
    @PostMapping("/coupons/{id}/orders/{orderId}/redrive") public Map<String,Boolean> redrive(@PathVariable long id,@PathVariable String orderId,HttpServletRequest req) {
        var admin=CurrentUser.from(req);admin.requireAdmin();
        Long result=redis.execute(RedisConfig.script("redrive.lua",Long.class),List.of(OrderKeys.owners(id),OrderKeys.status(id),OrderKeys.outbox(id)),orderId,String.valueOf(id));
        if(result==null||result!=1) throw new ApiError(HttpStatus.CONFLICT,"NOT_PENDING","只有仍在等待确认的已预留订单可以重投");
        log.info("Admin {} redrove coupon {} order {}",admin.id(),id,orderId);
        return Map.of("ok",true);
    }
    @GetMapping("/coupons/{id}/diagnostics") public Map<String,Object> diagnostics(@PathVariable long id,HttpServletRequest req) {
        CurrentUser.from(req).requireAdmin();
        var rows=db.queryForList("SELECT stock,initial_stock FROM coupon WHERE id=?",id);
        if(rows.isEmpty()) throw new ApiError(HttpStatus.NOT_FOUND,"COUPON_NOT_FOUND","优惠券不存在");
        return Map.of("database",rows.getFirst(),"orderCount",db.queryForObject("SELECT COUNT(*) FROM coupon_order WHERE coupon_id=?",Long.class,id),
            "redis",redis.opsForHash().entries(OrderKeys.inventory(id)),"outboxLength",java.util.Objects.requireNonNullElse(redis.opsForStream().size(OrderKeys.outbox(id)),0L));
    }
}
