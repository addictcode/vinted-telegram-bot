package com.example.vintedbot.service;

import com.example.vintedbot.model.SubscriptionLevel;
import com.example.vintedbot.model.User;
import com.example.vintedbot.model.UserSubscription;
import com.example.vintedbot.repository.UserRepository;
import com.example.vintedbot.repository.UserSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserSubscriptionRepository subscriptionRepository;

    /** Registers the user on first contact (used by /start). Idempotent. */
    @Transactional
    public User registerOrGet(Long chatId, String username) {
        return userRepository.findByChatId(chatId).orElseGet(() -> {
            User user = userRepository.save(User.builder()
                    .chatId(chatId)
                    .username(username)
                    .active(true)
                    .createdAt(OffsetDateTime.now())
                    .build());
            subscriptionRepository.save(UserSubscription.builder()
                    .userId(user.getId())
                    .subscriptionLevel(SubscriptionLevel.FREE)
                    .build());
            log.info("Registered new user chatId={} username={}", chatId, username);
            return user;
        });
    }

    @Transactional(readOnly = true)
    public User getByChatId(Long chatId) {
        return userRepository.findByChatId(chatId)
                .orElseThrow(() -> new IllegalStateException("Unknown user: " + chatId));
    }

    @Transactional(readOnly = true)
    public SubscriptionLevel subscriptionLevel(Long userId) {
        return subscriptionRepository.findByUserId(userId)
                .filter(sub -> sub.getExpiresAt() == null || sub.getExpiresAt().isAfter(OffsetDateTime.now()))
                .map(UserSubscription::getSubscriptionLevel)
                .orElse(SubscriptionLevel.FREE);
    }
}
