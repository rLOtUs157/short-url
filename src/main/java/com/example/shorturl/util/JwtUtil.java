package com.example.shorturl.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 工具：签发 token、解析 token、取 userId / jti。
 *
 * 密钥和有效期都从配置读取（application.properties 里的 jwt.secret / jwt.expire-ms），
 * 不再硬编码在代码里，避免源码泄露导致 token 可被伪造。
 */
@Component
public class JwtUtil {

    /** 签名密钥：从配置注入，长度必须 >= 32 字节（HS256 要求） */
    @Value("${jwt.secret}")
    private String secret;

    /** 有效期（毫秒），默认 24 小时 */
    @Value("${jwt.expire-ms:86400000}")
    private long expireMs;

    private SecretKey key;

    @PostConstruct
    public void init() {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("jwt.secret 未配置或长度不足 32 字节，无法使用 HS256 签名");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 签发 token，载荷里带上 userId，并写入唯一的 jti。
     * jti 是"撤销 token"的抓手：登出时把这个 id 记进黑名单即可让 token 立即失效。
     */
    public String generateToken(Long userId, String username) {
        Date now = new Date();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())   // 标准声明 jti
                .subject(username)
                .claim("userId", userId)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireMs))
                .signWith(key)
                .compact();
    }

    /** 解析 token，返回载荷（Claims） */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** 从 token 取出 userId */
    public Long getUserId(String token) {
        return parse(token).get("userId", Long.class);
    }

    /** 从 token 取出 jti（没有则为 null） */
    public String getJti(String token) {
        return parse(token).getId();
    }

    /** 从 token 取出过期时间 */
    public Date getExpiration(String token) {
        return parse(token).getExpiration();
    }
}
