package io.github.zhouhuimin.flashsale.auth;

import io.github.zhouhuimin.flashsale.api.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AuthWebConfig implements WebMvcConfigurer {
    private final AuthService auth;
    public AuthWebConfig(AuthService auth) { this.auth = auth; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
                res.setHeader("X-Service-Instance", System.getenv().getOrDefault("HOSTNAME", "local-" + ProcessHandle.current().pid()));
                String path = req.getRequestURI();
                String authorization = req.getHeader("Authorization");
                CurrentUser user = null;
                if (authorization != null && authorization.startsWith("Bearer ")) {
                    String token = authorization.substring(7);
                    if (token.matches("[A-Za-z0-9_-]{43}")) user = auth.resolve(token);
                }
                if (user != null) req.setAttribute("currentUser", user);
                boolean publicPath = path.equals("/api/auth/login") || path.equals("/api/auth/register")
                    || ("GET".equals(req.getMethod()) && (path.equals("/api/coupons") || path.matches("/api/coupons/\\d+") || path.startsWith("/api/social/posts")));
                if (!publicPath && user == null) throw new ApiError(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "登录已过期，请重新登录");
                return true;
            }
        }).addPathPatterns("/api/**");
    }
}
