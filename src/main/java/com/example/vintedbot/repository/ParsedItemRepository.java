package com.example.vintedbot.repository;

import com.example.vintedbot.model.ParsedItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

public interface ParsedItemRepository extends JpaRepository<ParsedItem, Long> {

    Page<ParsedItem> findByUserIdOrderByParsedAtDesc(Long userId, Pageable pageable);

    java.util.Optional<ParsedItem> findByIdAndUserId(Long id, Long userId);

    long countByUserId(Long userId);

    @Modifying
    @Transactional
    long deleteByUserId(Long userId);

    @Query("select min(p.parsedAt) from ParsedItem p where p.userId = :userId")
    OffsetDateTime findEarliestParsedAt(@Param("userId") Long userId);
}
