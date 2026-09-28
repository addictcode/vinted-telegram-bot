package com.example.vintedbot.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "vinted.monitor")
public class MonitorProperties {

    private boolean enabled = true;
    /** How often to poll normal subscriptions (near-real-time via the JSON API). */
    private long intervalMs = 20_000;
    /** Floor for snipe-mode polling; the real interval is derived from the request budget. */
    private long snipeMinIntervalMs = 1_000;
    /** Page size for snipe-mode polls: fewer bytes per request, still far above new-items-per-poll. */
    private int snipePerPage = 10;
    /** Safe request budget per outbound IP per minute; snipe interval is stretched to respect it. */
    private int requestsPerMinutePerIp = 20;
    /** Alert the owner when a Vinted host stays blocked this long. */
    private long blockAlertAfterMs = 600_000;
    /** Chat for operational alerts; defaults to the first registered user's chat. */
    private Long alertChatId;
    /** Scheduler tick: how often we look for subscriptions that are due. */
    private long tickMs = 250;
    private long initialDelayMs = 15_000;
    /** Max new listings pushed per subscription per cycle (anti-flood). */
    private int maxNewPerCycle = 5;
    /** Listings requested from the catalog API per poll. */
    private int perPage = 24;
    /** Pause after an anti-bot block before polling resumes. */
    private long backoffMs = 180_000;
    /** Total concurrent subscription checks across all hosts. */
    private int maxConcurrentChecks = 8;
    /** Concurrent checks allowed against the same Vinted host (anti-burst cap). */
    private int maxConcurrentPerHost = 2;
}
