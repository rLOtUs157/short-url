package com.example.shorturl.common;

import com.example.shorturl.util.JwtUtil;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * JWT 登录校验拦截器：
 * 1. 校验 Authorization: Bearer <token> 的签名与有效期；
 * 2. 再查一次黑名单，确认这个 token 没被登出撤销；
 * 3. 都通过才把 userId 放进 request，交给后续接口使用。
 *
 * 第 2 步是"登出立即生效"的关键：JWT 本身无状态，服务端只能靠黑名单知道它已被撤销。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final TokenBlacklist tokenBlacklist;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // 放行 CORS 预检请求
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return reject(response, "未登录");
        }

        String token = auth.substring(7);
        Long userId;
        String jti;
        try {
            userId = jwtUtil.getUserId(token);
            jti = jwtUtil.getJti(token);
        } catch (JwtException | IllegalArgumentException e) {
            return reject(response, "token 无效或已过期");
        }

        // 签名有效不代表还有效：可能用户已经登出，这个 jti 在黑名单里
        boolean revoked;
        try {
            revoked = tokenBlacklist.isRevoked(jti);
        } catch (Exception e) {
            // 设计上 fail closed：查不到黑名单状态就拒绝，避免已登出的 token 被放行
            log.error("查询 token 黑名单失败，保守拒绝该请求。jti={}", jti, e);
            return reject(response, "服务暂时不可用，请稍后重试");
        }
        if (revoked) {
            return reject(response, "登录已失效，请重新登录");
        }

        request.setAttribute("userId", userId);
        return true;
    }

    private boolean reject(HttpServletResponse response, String msg) throws Exception {
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"msg\":\"" + msg + "\"}");
        return false;
    }
}
