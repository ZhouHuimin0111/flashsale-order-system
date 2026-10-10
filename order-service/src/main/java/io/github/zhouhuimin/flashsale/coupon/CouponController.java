package io.github.zhouhuimin.flashsale.coupon;

import io.github.zhouhuimin.flashsale.auth.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/coupons")
public class CouponController {
    private final CouponService coupons;
    public CouponController(CouponService coupons) { this.coupons=coupons; }
    @GetMapping public List<Map<String,Object>> list() { return coupons.ids().stream().map(coupons::view).toList(); }
    @GetMapping("/{id}") public Map<String,Object> detail(@PathVariable long id) { return coupons.view(id); }
    public record Rename(@NotBlank @Size(max=120) String title) {}
    @PatchMapping("/{id}") public Map<String,Boolean> rename(@PathVariable long id, @Valid @RequestBody Rename body, HttpServletRequest req) {
        CurrentUser.from(req).requireAdmin(); coupons.rename(id, body.title()); return Map.of("ok", true);
    }
}
