package com.example.vintedbot;

import com.example.vintedbot.config.RateLimitProperties;
import com.example.vintedbot.model.SubscriptionLevel;
import com.example.vintedbot.service.RateLimitService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitServiceTest {

    private RateLimitService newService(int perHour) {
        RateLimitProperties props = new RateLimitProperties();
        props.setFreeRequestsPerHour(perHour);
        return new RateLimitService(props);
    }

    @Test
    void freeUser_isCappedPerHour() {
        RateLimitService service = newService(3);
        Long userId = 1L;
        assertThat(service.tryAcquire(userId, SubscriptionLevel.FREE)).isTrue();
        assertThat(service.tryAcquire(userId, SubscriptionLevel.FREE)).isTrue();
        assertThat(service.tryAcquire(userId, SubscriptionLevel.FREE)).isTrue();
        assertThat(service.tryAcquire(userId, SubscriptionLevel.FREE)).isFalse();
        assertThat(service.remaining(userId, SubscriptionLevel.FREE)).isZero();
    }

    @Test
    void premiumUser_isUnlimited() {
        RateLimitService service = newService(1);
        Long userId = 2L;
        for (int i = 0; i < 100; i++) {
            assertThat(service.tryAcquire(userId, SubscriptionLevel.PREMIUM)).isTrue();
        }
    }
}
