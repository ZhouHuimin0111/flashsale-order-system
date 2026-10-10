package io.github.zhouhuimin.flashsale.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public record Credentials(@NotBlank @Pattern(regexp="[A-Za-z0-9_]{3,32}") String username,
                              @NotBlank @Size(min=8, max=64) String password) {}
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }
    @PostMapping("/register") public Map<String, Object> register(@Valid @RequestBody Credentials body) { return auth.register(body.username(), body.password()); }
    @PostMapping("/login") public Map<String, Object> login(@Valid @RequestBody Credentials body) { return auth.login(body.username(), body.password()); }
    @GetMapping("/me") public CurrentUser me(HttpServletRequest request) { return CurrentUser.from(request); }
    @PostMapping("/logout") public Map<String, Boolean> logout(@RequestHeader("Authorization") String header) { auth.logout(header.substring(7)); return Map.of("ok", true); }
}
