package io.github.zhouhuimin.flashsale.auth;

import jakarta.servlet.http.HttpServletRequest;
import io.github.zhouhuimin.flashsale.api.ApiError;
import org.springframework.http.HttpStatus;

public record CurrentUser(long id, String username, String role) {
    public static CurrentUser from(HttpServletRequest request) {
        var user = (CurrentUser) request.getAttribute("currentUser");
        if (user == null) throw new ApiError(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "请先登录");
        return user;
    }
    public void requireAdmin() {
        if (!"ADMIN".equals(role)) throw new ApiError(HttpStatus.FORBIDDEN, "FORBIDDEN", "需要管理员权限");
    }
}
