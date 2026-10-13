package com.example.shorturl.common;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * Token 黑名单：存放"已登出 / 已被撤销"的 jti。
 *
 * 关键点是 TTL：只需要记住到 token 自己的过期时间为止。
 * 过了 exp 之后 token 本来就会被签名校验拒掉，黑名单条目也就没有意义了，
 * 所以让 Redis 自动清理，黑名单不会无限增长。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenBlacklist {

    /** Redis key 前缀：short:jwt:blacklist:<jti> */
    private static final String JTI_KEY = "short:jwt:blacklist:";

    private final StringRedisTemplate redisTemplate;

    /**
     * 撤销一个 token。
     *
     * @param jti        token 的唯一 id
     * @param expiration token 的过期时间；为 null 或已过期时不写入（没意义）
     */
    public void revoke(String jti, Date expiration) {
        if (jti == null || jti.isBlank()) {
            return;
        }
        if (expiration == null) {
            // 理论上不会发生（签发时一定会写 exp），按"永久记住"兜底
            redisTemplate.opsForValue().set(JTI_KEY + jti, "1");
            log.warn("撤销 token 时未取到过期时间，已按永久黑名单处理，jti={}", jti);
            return;
        }
        long remainMs = expiration.getTime() - System.currentTimeMillis();
        if (remainMs <= 0) {
            return; // 已经过期，无需记黑名单
        }
        redisTemplate.opsForValue().set(JTI_KEY + jti, "1", remainMs, TimeUnit.MILLISECONDS);
        log.info("token 已撤销，jti={}，剩余有效期 {} 秒", jti, Duration.ofMillis(remainMs).toSeconds());
    }

    /**
     * 该 token 是否已被撤销。
     *
     * 注意这里选择了"fail closed"：Redis 不可用时抛异常，由拦截器转成 401，
     * 而不是放行。因为放行等于让所有已登出的 token 重新可用——对登出这个
     * 安全功能来说，宁可暂时不可用，也不能静默失效。
     */
    public boolean isRevoked(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey(JTI_KEY + jti));
    }
}
