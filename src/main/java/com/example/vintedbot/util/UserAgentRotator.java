package com.example.vintedbot.util;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Rotates through a small pool of realistic desktop User-Agent strings so
 * consecutive requests do not all share the same fingerprint.
 */
@Component
public class UserAgentRotator {

    private static final List<String> USER_AGENTS = List.of(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36 Edg/123.0.0.0",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15"
    );

    private final java.util.concurrent.atomic.AtomicInteger index =
            new java.util.concurrent.atomic.AtomicInteger(0);

    public String next() {
        int i = Math.floorMod(index.getAndIncrement(), USER_AGENTS.size());
        return USER_AGENTS.get(i);
    }

    public String random() {
        return USER_AGENTS.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(USER_AGENTS.size()));
    }
}
