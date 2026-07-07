package com.example.vintedbot.repository;

import com.example.vintedbot.model.WatchedItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WatchedItemRepository extends JpaRepository<WatchedItem, Long> {

    List<WatchedItem> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<WatchedItem> findByIdAndUserId(Long id, Long userId);

    Optional<WatchedItem> findByUserIdAndVintedUrl(Long userId, String vintedUrl);

    boolean existsByUserIdAndVintedUrl(Long userId, String vintedUrl);

    long countByUserId(Long userId);
}
