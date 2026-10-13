package com.example.shorturl.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "url_mapping")
public class UrlMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 短码，如 Ab3xY9 */
    @Column(nullable = false, unique = true, length = 12)
    private String shortCode;

    /** 原始长链接 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String longUrl;

    /** 归属用户 */
    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    /** 过期时间，null 表示永久有效 */
    private LocalDateTime expiredAt;

    /** 点击次数 */
    @Column(nullable = false)
    private Long clickCount = 0L;
}
