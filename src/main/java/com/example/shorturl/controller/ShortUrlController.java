package com.example.shorturl.controller;

import com.example.shorturl.entity.UrlMapping;
import com.example.shorturl.service.ShortUrlService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequiredArgsConstructor
public class ShortUrlController {

    /** 长链接最大长度，防止超长串塞满 TEXT 字段 */
    private static final int MAX_URL_LENGTH = 2048;

    private final ShortUrlService shortUrlService;

    @Value("${short-url.base-url}")
    private String baseUrl;

    /**
     * 创建短链：POST /api/short-url，body {"longUrl": "https://..."}
     * 返回：{"shortUrl": "http://localhost:8081/s/Ab3xY9", "shortCode": "Ab3xY9"}
     */
    @PostMapping("/api/short-url")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, String> body,
                                                       @RequestAttribute("userId") Long userId) {
        String longUrl = body.get("longUrl");
        if (longUrl == null || longUrl.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("code", 1, "msg", "longUrl 不能为空"));
        }
        longUrl = longUrl.trim();
        // 限长：别让超长串进数据库
        if (longUrl.length() > MAX_URL_LENGTH) {
            return ResponseEntity.badRequest().body(Map.of("code", 1, "msg", "链接过长，最多 " + MAX_URL_LENGTH + " 个字符"));
        }
        // 校验协议：否则短链服务会变成开放重定向（例如指向内网地址做钓鱼跳板）
        if (!isHttpUrl(longUrl)) {
            return ResponseEntity.badRequest().body(Map.of("code", 1, "msg", "只支持 http:// 或 https:// 开头的链接"));
        }
        // userId 来自 JWT 拦截器解析出的登录用户
        UrlMapping m = shortUrlService.create(longUrl, userId, 5);
        return ResponseEntity.ok(Map.of(
                "code", 0,
                "shortUrl", baseUrl + m.getShortCode(),
                "shortCode", m.getShortCode()
        ));
    }

    /**
     * 跳转：GET /s/{code}，302 重定向到长链接
     */
    @GetMapping("/s/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        Optional<String> longUrl = shortUrlService.resolve(code);
        if (longUrl.isEmpty()) {
            ResponseEntity<Void> notFound = ResponseEntity.notFound().build();
            return notFound;
        }
        ResponseEntity<Void> found = ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(longUrl.get()))
                .build();
        return found;
    }

    /**
     * 查询点击次数：GET /api/click/{code}
     */
    @GetMapping("/api/click/{code}")
    public ResponseEntity<Map<String, Object>> clickCount(@PathVariable String code) {
        long count = shortUrlService.getClickCount(code);
        return ResponseEntity.ok(Map.of("code", 0, "shortCode", code, "clickCount", count));
    }

    /**
     * 我的短链列表：GET /api/my-urls（需登录）
     * 只返回当前登录用户自己创建的数据（数据隔离）。
     *
     * 点击次数 = 数据库里已落库的 + Redis 里还没落库的增量，
     * 增量用一次 multiGet 批量取回，避免每个短码查一次 Redis。
     */
    @GetMapping("/api/my-urls")
    public ResponseEntity<Map<String, Object>> myUrls(@RequestAttribute("userId") Long userId) {
        List<UrlMapping> mappings = shortUrlService.listByUser(userId);
        List<String> codes = mappings.stream().map(UrlMapping::getShortCode).toList();
        Map<String, Long> pending = shortUrlService.getPendingDeltas(codes);

        List<Map<String, Object>> items = new ArrayList<>(mappings.size());
        for (UrlMapping m : mappings) {
            long persisted = m.getClickCount() == null ? 0L : m.getClickCount();
            long total = persisted + pending.getOrDefault(m.getShortCode(), 0L);
            items.add(Map.of(
                    "shortCode", m.getShortCode(),
                    "shortUrl", baseUrl + m.getShortCode(),
                    "longUrl", m.getLongUrl(),
                    "clickCount", total,
                    "createdAt", m.getCreatedAt() == null ? "" : m.getCreatedAt().toString()
            ));
        }
        return ResponseEntity.ok(Map.of("code", 0, "data", items));
    }

    /** 校验是不是真正的 http/https 绝对地址（能解析出 host 才算） */
    private boolean isHttpUrl(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (scheme == null) {
                return false;
            }
            if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
                return false;
            }
            return uri.getHost() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
