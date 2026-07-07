package com.example.vintedbot.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "vinted.monitor")
public class MonitorProperties {

    private boolean enabled = true;
    /** How often to poll subscriptions (near-real-time via the JSON API). */
    private long intervalMs = 20_000;
    private long initialDelayMs = 15_000;
    /** Max new listings pushed per subscription per cycle (anti-flood). */
    private int maxNewPerCycle = 5;
    /** Listings requested from the catalog API per poll. */
    private int perPage = 24;
    /** Pause after an anti-bot block before polling resumes. */
    private long backoffMs = 180_000;
}
