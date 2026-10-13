package com.example.shorturl.util;

import org.springframework.stereotype.Component;

import java.util.BitSet;

/**
 * 手写布隆过滤器（Bloom Filter），用于防缓存穿透的第一层拦截。
 *
 * 原理：
 *   - 一个位数组 + 多个哈希函数
 *   - 插入短码：用多个哈希算出多个位置，全部置 1
 *   - 查询短码：只要有一个位置是 0 → 一定不存在（直接挡掉）；全 1 → 可能存在（需进一步查库）
 *
 * 特性：
 *   - 判断"不存在"是 100% 准确的（无假阴性）
 *   - 判断"存在"可能误报（有假阳性），所以命中后要再查库确认
 *   - 极省内存：几亿个元素也只占很小的位数组
 *   - 不能删除元素
 *
 * 生产环境通常直接用 Guava 的 BloomFilter，这里手写是为了能讲清原理。
 */
@Component
public class HashBloomFilter {

    private final BitSet bits;
    private final int size;
    private final int[] seeds;   // 每个哈希函数的种子

    public HashBloomFilter() {
        this(1_000_000, 3); // 默认 100 万位、3 个哈希函数
    }

    public HashBloomFilter(int size, int hashCount) {
        this.size = size;
        this.bits = new BitSet(size);
        this.seeds = new int[hashCount];
        for (int i = 0; i < hashCount; i++) {
            seeds[i] = i * 131 + 17; // 不同种子派生不同哈希
        }
    }

    /** 插入：把 k 个位置置 1 */
    public void add(String value) {
        for (int seed : seeds) {
            bits.set(hash(value, seed));
        }
    }

    /** 查询：有一个位置是 0 → 一定不存在；全 1 → 可能存在 */
    public boolean mightContain(String value) {
        for (int seed : seeds) {
            if (!bits.get(hash(value, seed))) {
                return false;
            }
        }
        return true;
    }

    /** 一个简单的可复现哈希：基于字符串字符 + 种子 */
    private int hash(String value, int seed) {
        int h = 0;
        for (int i = 0; i < value.length(); i++) {
            h = 31 * h + value.charAt(i) + seed;
        }
        if (h < 0) {
            h = -h; // 保证非负，避免 BitSet 下标越界
        }
        return h % size;
    }
}
