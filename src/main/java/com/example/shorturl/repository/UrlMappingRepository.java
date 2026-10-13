package com.example.shorturl.repository;

import com.example.shorturl.entity.UrlMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface UrlMappingRepository extends JpaRepository<UrlMapping, Long> {

    Optional<UrlMapping> findByShortCode(String shortCode);

    /** 按用户查它自己的短链，新建的排前面 */
    List<UrlMapping> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * 把未落库的点击增量原子地累加到数据库，避免"先读后写"导致并发丢更新。
     *
     * clearAutomatically = true：UPDATE 是绕过 JPA 一级缓存直接改库的，
     * 不清缓存的话，同一事务/持久化上下文里拿到的还是加之前的旧 clickCount。
     *
     * @return 受影响行数（1 = 更新成功，0 = 短码不存在）
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update UrlMapping m set m.clickCount = m.clickCount + :delta where m.shortCode = :code")
    int incrementClickCount(@Param("code") String code, @Param("delta") long delta);
}
