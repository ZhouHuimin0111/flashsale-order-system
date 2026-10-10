package io.github.zhouhuimin.flashsale.auth;

import io.github.zhouhuimin.flashsale.api.ApiError;
import io.github.zhouhuimin.flashsale.config.RedisConfig;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class AuthService {
    private final JdbcTemplate db;
    private final StringRedisTemplate redis;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);
    private final SecureRandom random = new SecureRandom();
    private final String adminPassword;
    public AuthService(JdbcTemplate db, StringRedisTemplate redis, @Value("${app.bootstrap-admin-password:}") String adminPassword) {
        this.db = db; this.redis = redis; this.adminPassword = adminPassword;
    }
    @EventListener(ApplicationReadyEvent.class) public void bootstrap() {
        if (!adminPassword.isBlank()) {
            if (adminPassword.length() < 12) throw new IllegalArgumentException("BOOTSTRAP_ADMIN_PASSWORD must be at least 12 characters");
            validatePassword(adminPassword);
            db.update("INSERT IGNORE INTO app_user(username,password_hash,role) VALUES ('admin',?,'ADMIN')", encoder.encode(adminPassword));
        }
    }
    public Map<String, Object> register(String username, String password) {
        validatePassword(password);
        if ("admin".equalsIgnoreCase(username)) throw new ApiError(HttpStatus.CONFLICT, "USERNAME_TAKEN", "用户名已保留");
        rateLimit("register", username.toLowerCase());
        try { db.update("INSERT INTO app_user(username,password_hash) VALUES (?,?)", username, encoder.encode(password)); }
        catch (DuplicateKeyException e) { throw new ApiError(HttpStatus.CONFLICT, "USERNAME_TAKEN", "用户名已存在"); }
        return login(username, password);
    }
    public Map<String, Object> login(String username, String password) {
        validatePassword(password);
        rateLimit("login", username.toLowerCase());
        var users = db.query("SELECT id,username,password_hash,role FROM app_user WHERE username=?",
            (rs, n) -> Map.<String, Object>of("id", rs.getLong(1), "username", rs.getString(2), "hash", rs.getString(3), "role", rs.getString(4)), username);
        if (users.isEmpty() || !encoder.matches(password, (String) users.getFirst().get("hash")))
            throw new ApiError(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "用户名或密码错误");
        var user = users.getFirst();
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redis.execute(RedisConfig.script("session-create.lua", Long.class), List.of("session:" + token),
            user.get("id").toString(), user.get("username"), user.get("role"), "1800");
        return Map.of("token", token, "expiresIn", 1800, "user", Map.of("id", user.get("id"), "username", user.get("username"), "role", user.get("role")));
    }
    public CurrentUser resolve(String token) {
        List<?> fields = redis.execute(RedisConfig.script("session-refresh.lua", List.class), List.of("session:" + token), "1800");
        if (fields == null || fields.isEmpty()) return null;
        return new CurrentUser(Long.parseLong(fields.get(0).toString()), fields.get(1).toString(), fields.get(2).toString());
    }
    public void logout(String token) { redis.delete("session:" + token); }
    private void validatePassword(String password) {
        if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            throw new ApiError(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "密码的 UTF-8 长度不得超过 72 字节");
    }
    private void rateLimit(String action, String username) {
        Long count = redis.execute(RedisConfig.script("rate-limit.lua", Long.class), List.of("auth:limit:" + action + ":" + username), "60");
        if (count != null && count > 10) throw new ApiError(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "尝试过于频繁，请稍后再试");
    }
}
