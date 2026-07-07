package com.example.vintedbot.repository;

import com.example.vintedbot.model.SearchSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SearchSubscriptionRepository extends JpaRepository<SearchSubscription, Long> {

    List<SearchSubscription> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<SearchSubscription> findByActiveTrue();

    Optional<SearchSubscription> findByIdAndUserId(Long id, Long userId);

    boolean existsByUserIdAndCatalogUrl(Long userId, String catalogUrl);

    long countByUserId(Long userId);
}
