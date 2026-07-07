package com.example.vintedbot.repository;

import com.example.vintedbot.model.UserSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserSubscriptionRepository extends JpaRepository<UserSubscription, Long> {

    Optional<UserSubscription> findByUserId(Long userId);
}
