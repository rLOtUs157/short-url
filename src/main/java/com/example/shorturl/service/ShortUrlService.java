package com.example.shorturl.service;

import com.example.shorturl.entity.UrlMapping;
import com.example.shorturl.repository.UrlMappingRepository;
import com.example.shorturl.util.HashBloomFilter;
import com.example.shorturl.util.ShortCodeGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShortUrlService {

    // Redis key 前缀
    private static final String URL_KEY = "short:url:";              // 短码 -> 长链接
    private static final String CLICK_DELTA_KEY = "short:click:delta"; // hash: 短码 -> 尚未落库的点击增量
    private static final String LOCK_KEY = "short:lock:";            // 重建锁（防击穿）

    /** 空值缓存占位符：表示"这个短码不存在"，防止穿透 */
    private static final String NULL_MARK = "NIL";

    private final UrlMappingRepository urlMappingRepository;
    private final StringRedisTemplate redisTemplate;
    private final HashBloomFilter bloomFilter;

    /**
     * 启动时把数据库里已有的短码全部加载进布隆过滤器。
     * 否则这些"真实存在"的短码会被布隆误判成"不存在"而挡掉。
     */
    @PostConstruct
    public void initBloomFilter() {
        List<String> codes = urlMappingRepository.findAll().stream()
                .map(UrlMapping::getShortCode)
                .toList();
        codes.forEach(bloomFilter::add);
        log.info("布隆过滤器初始化完成，已加载 {} 个短码", codes.size());
    }

    /**
     * 创建短链：生成不重复的短码并入库，同时加入布隆过滤器。
     */
    public UrlMapping create(String longUrl, Long userId, int maxRetry) {
        for (int i = 0; i < maxRetry; i++) {
            String code = ShortCodeGenerator.random();
            if (urlMappingRepository.findByShortCode(code).isEmpty()) {
                UrlMapping m = new UrlMapping();
                m.setShortCode(code);
                m.setLongUrl(longUrl);
                m.setUserId(userId);
                m.setCreatedAt(LocalDateTime.now());
                m.setClickCount(0L);
                UrlMapping saved = urlMappingRepository.save(m);
                bloomFilter.add(saved.getShortCode()); // 新短码同步进布隆，否则自己都查不到
                return saved;
            }
        }
        throw new RuntimeException("短码生成失败，请重试");
    }

    /**
     * 解析短码（三层拦截 + 防击穿）：
     * 第 1 层 布隆过滤器：说"不存在"就 100% 不存在，直接返回，连 Redis 都不查（防穿透最省）。
     * 第 2 层 Redis 缓存：命中直接返回；未命中尝试拿锁查库回填（防击穿）。
     * 第 3 层 数据库：查库也没找到时写"空值缓存"，挡住 2 分钟内的反复穿透。
     */
    public Optional<String> resolve(String code) {

        // ===== 第 1 层：布隆过滤器（防穿透的第一道闸）=====
        if (!bloomFilter.mightContain(code)) {
            return Optional.empty(); // 一定不存在，直接挡掉，不打 Redis、不打数据库
        }

        // ===== 第 2 层：查缓存 =====
        String cached = redisTemplate.opsForValue().get(URL_KEY + code);
        if (cached != null) {
            // 命中空值占位符：这个短码不存在，直接返回
            if (NULL_MARK.equals(cached)) {
                return Optional.empty();
            }
            recordClick(code);
            return Optional.of(cached);
        }

        // ===== 第 3 层：缓存未命中，尝试拿互斥锁去查库（防击穿）=====
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(LOCK_KEY + code, "1", Duration.ofSeconds(5));
        if (locked != null && locked) {
            try {
                Optional<String> fromDb = urlMappingRepository.findByShortCode(code)
                        .map(UrlMapping::getLongUrl);
                if (fromDb.isPresent()) {
                    redisTemplate.opsForValue().set(URL_KEY + code, fromDb.get(), Duration.ofDays(1));
                    recordClick(code);
                } else {
                    // 布隆误报"可能存在"，但库里没有：写空值缓存，2 分钟内不再打库
                    redisTemplate.opsForValue().set(URL_KEY + code, NULL_MARK, Duration.ofMinutes(2));
                }
                return fromDb;
            } finally {
                redisTemplate.delete(LOCK_KEY + code);
            }
        }

        // 没拿到锁，说明别人正在重建缓存，短暂等待后重查
        try {
            Thread.sleep(50);
        } catch (InterruptedException ignored) {
            // 被中断也继续
        }
        String afterWait = redisTemplate.opsForValue().get(URL_KEY + code);
        if (afterWait != null) {
            if (NULL_MARK.equals(afterWait)) {
                return Optional.empty();
            }
            recordClick(code);
            return Optional.of(afterWait);
        }
        return Optional.empty();
    }

    /**
     * 记录一次点击：只累加 Redis 里的"未落库增量"，不碰数据库。
     *
     * 之所以记增量而不是记总数：如果缓存里存的是总数，Redis 一重启总数归零，
     * 定时任务就会把数据库里的真实值覆盖成 0。记增量则最坏只丢"上次落库之后"
     * 的那一小段，数据库里已落库的部分永远安全。
     */
    private void recordClick(String code) {
        redisTemplate.opsForHash().increment(CLICK_DELTA_KEY, code, 1);
    }

    /**
     * 读取某个短链的点击总数 = 数据库里已落库的 + Redis 里还没落库的增量。
     *
     * 必须两边相加：只读数据库会看不到最近 30 秒的点击，只读 Redis 会在
     * Redis 重启后显示 0。
     */
    @Transactional(readOnly = true)
    public long getClickCount(String code) {
        long persisted = urlMappingRepository.findByShortCode(code)
                .map(UrlMapping::getClickCount)
                .orElse(0L);
        return persisted + pendingDelta(code);
    }

    /**
     * 批量取"未落库增量"：给"我的短链"列表用，一次 Redis 往返取回全部短码，
     * 避免每个短码查一次 Redis。
     */
    @Transactional(readOnly = true)
    public Map<String, Long> getPendingDeltas(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return Map.of();
        }
        List<Object> fields = codes.stream().map(c -> (Object) c).toList();
        List<Object> values = redisTemplate.opsForHash().multiGet(CLICK_DELTA_KEY, fields);
        Map<String, Long> result = new HashMap<>();
        for (int i = 0; i < codes.size(); i++) {
            Object v = values.get(i);
            long delta = v == null ? 0L : parseDelta(String.valueOf(v), codes.get(i));
            if (delta > 0) {
                result.put(codes.get(i), delta);
            }
        }
        return result;
    }

    /**
     * 定时把 Redis 里的点击增量落库。
     *
     * 相比"把 Redis 计数全量写回数据库"，这里只做增量累加：
     * Redis 丢数据不会把库里的历史计数抹掉，重复执行也不会多算。
     */
    @Scheduled(fixedDelay = 30_000) // 每 30 秒执行一次（上一轮跑完才计时，不会自重叠）
    public void syncClickCountToDb() {
        Map<Object, Object> deltas = redisTemplate.opsForHash().entries(CLICK_DELTA_KEY);
        if (deltas.isEmpty()) {
            return;
        }
        int flushed = 0;
        for (Map.Entry<Object, Object> e : deltas.entrySet()) {
            String code = String.valueOf(e.getKey());
            long delta = parseDelta(String.valueOf(e.getValue()), code);
            if (delta <= 0) {
                continue;
            }
            try {
                if (flushClickDelta(code, delta)) {
                    flushed++;
                }
            } catch (Exception ex) {
                // 单个短码失败不影响其它短码；增量还留在 Redis 里，下一轮重试即可
                log.warn("点击增量落库失败，将在下一轮重试。code={} delta={}", code, delta, ex);
            }
        }
        log.info("点击增量落库完成，处理了 {} 个短码", flushed);
    }

    /**
     * 把一个短码的增量落库，成功后从 Redis 扣掉这部分增量。
     *
     * 先落库、后扣减：反过来的话，一旦落库失败，这段增量就被白扣了。
     *
     * 这里不写 @Transactional：本方法由同一个类内部的定时任务直接调用，
     * 走的是 this 而不是 Spring 代理，注解不会生效，写上只会误导人。
     * 事务由 incrementClickCount 上的 @Transactional 提供：每个短码一次独立事务，
     * 提交后才去扣减 Redis，所以单个短码失败不会影响已经落库成功的数据。
     *
     * @return true 表示又处理掉了一个短码（更新成功或短码已不存在）
     */
    public boolean flushClickDelta(String code, long delta) {
        urlMappingRepository.incrementClickCount(code, delta);
        // 注意：扣减只取决于"更新是否执行成功"，不取决于受影响行数。
        // 短码不存在（受影响行数 0）时增量留着也永远落不了库，同样要扣掉。
        redisTemplate.opsForHash().increment(CLICK_DELTA_KEY, code, -delta);
        return true;
    }

    /** 单个短码的未落库增量，取不到按 0 算 */
    private long pendingDelta(String code) {
        Object v = redisTemplate.opsForHash().get(CLICK_DELTA_KEY, code);
        return v == null ? 0L : parseDelta(String.valueOf(v), code);
    }

    /** Redis 里的值理论上都是数字；万一被写脏了，宁可当 0 也不让定时任务整体崩掉 */
    private long parseDelta(String raw, String code) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("点击增量不是合法数字，按 0 处理。code={} value={}", code, raw);
            return 0L;
        }
    }

    /**
     * 查某个用户自己创建的所有短链（新建的在前）。
     */
    public List<UrlMapping> listByUser(Long userId) {
        return urlMappingRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }
}
