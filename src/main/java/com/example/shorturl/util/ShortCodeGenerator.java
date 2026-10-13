package com.example.shorturl.util;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 短码生成器：随机生成 8 位 base62 编码（0-9 a-z A-Z），
 * 8 位组合空间为 62^8，足够大，冲突概率极低。
 */
public class ShortCodeGenerator {

    private static final String BASE62 =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private static final int LENGTH = 8;

    public static String random() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(BASE62.charAt(ThreadLocalRandom.current().nextInt(BASE62.length())));
        }
        return sb.toString();
    }
}
