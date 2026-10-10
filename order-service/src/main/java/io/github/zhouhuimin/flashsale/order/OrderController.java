package io.github.zhouhuimin.flashsale.order;

import io.github.zhouhuimin.flashsale.auth.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class OrderController {
    private final OrderService orders;
    public OrderController(OrderService orders) { this.orders=orders; }
    @PostMapping("/coupons/{id}/orders") public ResponseEntity<OrderService.Reservation> reserve(@PathVariable long id, HttpServletRequest req) {
        return ResponseEntity.accepted().body(orders.reserve(id, CurrentUser.from(req).id()));
    }
    @GetMapping("/coupons/{id}/orders/{orderId}") public Map<String,Object> status(@PathVariable long id, @PathVariable String orderId, HttpServletRequest req) {
        return orders.status(id, orderId, CurrentUser.from(req).id());
    }
    @GetMapping("/orders/mine") public List<Map<String,Object>> mine(HttpServletRequest req) { return orders.mine(CurrentUser.from(req).id()); }
    @GetMapping("/orders/reservations") public List<Map<String,Object>> pending(HttpServletRequest req) { return orders.pending(CurrentUser.from(req).id()); }
}
