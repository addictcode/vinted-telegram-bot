package com.example.vintedbot.service;

import com.example.vintedbot.config.RateLimitProperties;
import com.example.vintedbot.model.SubscriptionLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window rate limiter. FREE users are capped at N requests/hour;
 * PREMIUM users are unlimited. In-memory — for multi-instance deploys back this
 * with Redis instead.
 */
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final RateLimitProperties props;
    private final Map<Long, Deque<Instant>> hits = new ConcurrentHashMap<>();

    public boolean tryAcquire(Long userId, SubscriptionLevel level) {
        if (level == SubscriptionLevel.PREMIUM) {
            return true;
        }
        Instant now = Instant.now();
        Instant windowStart = now.minus(Duration.ofHours(1));
        Deque<Instant> deque = hits.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && deque.peekFirst().isBefore(windowStart)) {
                deque.pollFirst();
            }
            if (deque.size() >= props.getFreeRequestsPerHour()) {
                return false;
            }
            deque.addLast(now);
            return true;
        }
    }

    public int remaining(Long userId, SubscriptionLevel level) {
        if (level == SubscriptionLevel.PREMIUM) {
            return Integer.MAX_VALUE;
        }
        Deque<Instant> deque = hits.get(userId);
        if (deque == null) return props.getFreeRequestsPerHour();
        Instant windowStart = Instant.now().minus(Duration.ofHours(1));
        synchronized (deque) {
            while (!deque.isEmpty() && deque.peekFirst().isBefore(windowStart)) {
                deque.pollFirst();
            }
            return Math.max(0, props.getFreeRequestsPerHour() - deque.size());
        }
    }
}
