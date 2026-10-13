package com.example.shorturl.controller;

import com.example.shorturl.common.TokenBlacklist;
import com.example.shorturl.entity.User;
import com.example.shorturl.repository.UserRepository;
import com.example.shorturl.util.JwtUtil;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.Map;
import java.util.Optional;

/**
 * 注册 / 登录 / 登出。登录成功签发 JWT，登出把该 token 的 jti 加入黑名单。
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final TokenBlacklist tokenBlacklist;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /** 注册：POST /api/auth/register，body {"username":"xxx","password":"xxx"} */
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        if (username == null || password == null || username.isBlank() || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("code", 1, "msg", "用户名或密码不能为空"));
        }
        if (userRepository.findByUsername(username).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("code", 1, "msg", "用户名已存在"));
        }
        User u = new User();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode(password)); // BCrypt 加密存储
        u.setCreatedAt(LocalDateTime.now());
        userRepository.save(u);
        return ResponseEntity.ok(Map.of("code", 0, "msg", "注册成功"));
    }

    /** 登录：POST /api/auth/login，body {"username":"xxx","password":"xxx"}，返回 token */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        Optional<User> opt = userRepository.findByUsername(username);
        if (opt.isEmpty() || !passwordEncoder.matches(password, opt.get().getPassword())) {
            return ResponseEntity.status(401).body(Map.of("code", 401, "msg", "用户名或密码错误"));
        }
        User u = opt.get();
        String token = jwtUtil.generateToken(u.getId(), u.getUsername());
        return ResponseEntity.ok(Map.of("code", 0, "token", token, "userId", u.getId()));
    }

    /**
     * 登出：POST /api/auth/logout，需带 Authorization: Bearer <token>。
     * 服务端把该 token 的 jti 记进 Redis 黑名单（TTL = 剩余有效期），
     * 于是这个 token 立刻失效，而不是像以前那样还能再用 24 小时。
     *
     * 设计为幂等：token 已过期 / 已撤销也返回成功，前端不必区分。
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) {
            return ResponseEntity.badRequest().body(Map.of("code", 1, "msg", "缺少 token"));
        }
        String token = auth.substring(7);

        // 第 1 步：解析 token。解析失败（签名不对 / 已过期）说明它本来就无效，
        // 等价于"已经退出"，静默成功即可。
        String jti;
        Date expiration;
        try {
            jti = jwtUtil.getJti(token);
            expiration = jwtUtil.getExpiration(token);
        } catch (JwtException | IllegalArgumentException e) {
            return ResponseEntity.ok(Map.of("code", 0, "msg", "已退出登录"));
        }

        // 第 2 步：写黑名单。这一步失败必须如实上报 —— 否则用户以为登出了，
        // token 其实还有效，正是这次要修掉的问题。
        try {
            tokenBlacklist.revoke(jti, expiration);
        } catch (Exception e) {
            log.error("登出写黑名单失败，该 token 仍然有效！jti={}", jti, e);
            return ResponseEntity.status(503).body(Map.of("code", 1, "msg", "登出失败，请重试"));
        }
        return ResponseEntity.ok(Map.of("code", 0, "msg", "已退出登录"));
    }
}
