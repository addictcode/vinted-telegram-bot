package com.example.vintedbot.service;

import com.example.vintedbot.config.MonitorProperties;
import com.example.vintedbot.dto.CatalogItemSummary;
import com.example.vintedbot.dto.ListingCard;
import com.example.vintedbot.dto.SendTarget;
import com.example.vintedbot.dto.VintedItem;
import com.example.vintedbot.model.SearchSubscription;
import com.example.vintedbot.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Near-real-time monitor for saved catalog searches. Primary path polls
 * Vinted's lightweight JSON API (seconds-level latency, cards built directly
 * from API data); if the API is unavailable it falls back to the HTML page +
 * full item parse. Subscriptions are checked concurrently (bounded overall
 * and per Vinted host, to avoid turning a spaced-out request pattern into a
 * burst). After an anti-bot block, polling pauses only for that host's
 * subscriptions so a block on one domain doesn't silence every other search.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchMonitorService {

    /** Telegram delivery seam, registered by the bot on construction. */
    @FunctionalInterface
    public interface Notifier {
        void sendCard(SendTarget target, String header, com.example.vintedbot.dto.ListingCard card);
    }

    private final SearchSubscriptionService subscriptions;
    private final VintedApiClient apiClient;
    private final VintedParserService parser;
    private final HistoryService historyService;
    private final UserRepository userRepository;
    private final MonitorProperties props;

    private volatile Notifier notifier;
    /** Per-host pause-until timestamp after an anti-bot block on that host. */
    private final ConcurrentHashMap<String, Instant> pausedUntilByHost = new ConcurrentHashMap<>();
    /** Per-host concurrency cap, created on demand. */
    private final ConcurrentHashMap<String, Semaphore> hostSemaphores = new ConcurrentHashMap<>();
    /** Lazily built so plain `new SearchMonitorService(...)` (tests, bypassing Spring) still works. */
    private volatile ExecutorService checkExecutor;
    /** Next due time (epoch ms) per subscription id; absent = due right now. */
    private final ConcurrentHashMap<Long, Long> nextDueAt = new ConcurrentHashMap<>();

    public void setNotifier(Notifier notifier) {
        this.notifier = notifier;
    }

    @Scheduled(fixedDelayString = "${vinted.monitor.tick-ms:1000}",
            initialDelayString = "${vinted.monitor.initial-delay-ms:15000}")
    public void scheduledCheck() {
        if (!props.isEnabled() || notifier == null) return;
        checkDue();
    }

    /**
     * Checks only the subscriptions whose own interval has elapsed, so snipe-mode
     * ones come around every few seconds while normal ones keep the default pace.
     */
    public int checkDue() {
        long now = System.currentTimeMillis();
        List<SearchSubscription> active = subscriptions.allActive();
        nextDueAt.keySet().retainAll(active.stream().map(SearchSubscription::getId).toList());
        List<SearchSubscription> due = active.stream()
                .filter(s -> nextDueAt.getOrDefault(s.getId(), 0L) <= now)
                .toList();
        if (due.isEmpty()) return 0;

        long fastSubs = active.stream().filter(SearchSubscription::isFast).count();
        long normalSubs = active.size() - fastSubs;
        for (SearchSubscription s : due) {
            long interval = s.isFast()
                    ? snipeIntervalMs(fastSubs, normalSubs, apiClient.endpointCount())
                    : props.getIntervalMs();
            nextDueAt.put(s.getId(), now + interval);
        }
        return checkSubs(due);
    }

    /**
     * Poll interval for snipe-mode subscriptions. Faster = earlier alerts, but
     * every request spends the per-IP budget Vinted tolerates before blocking.
     */
    long snipeIntervalMs(long fastSubs, long normalSubs, int ipCount) {
        if (fastSubs <= 0) return props.getIntervalMs();
        double budgetPerMin = (double) props.getRequestsPerMinutePerIp() * Math.max(1, ipCount);
        double normalPerMin = normalSubs * 60_000.0 / props.getIntervalMs();
        double sparePerMin = budgetPerMin - normalPerMin;
        // No spare budget: polling faster would only get the IPs banned, so no snipe advantage.
        if (sparePerMin <= 0) return props.getIntervalMs();
        long interval = (long) Math.ceil(fastSubs * 60_000.0 / sparePerMin);
        return Math.max(props.getSnipeMinIntervalMs(), Math.min(interval, props.getIntervalMs()));
    }

    /** Proactively refreshes soon-to-expire sessions off the hot delivery path. */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void warmSessions() {
        if (!props.isEnabled()) return;
        subscriptions.allActive().stream()
                .map(s -> hostOf(s.getCatalogUrl()))
                .filter(Objects::nonNull)
                .distinct()
                .forEach(apiClient::warmSession);
    }

    /**
     * Result of the network-only fetch step for one subscription. Carries no
     * side effects — DB writes and Telegram delivery happen afterward, back
     * on the calling thread (see {@link #applyFetchResult}).
     */
    private record FetchResult(SearchSubscription sub, List<CatalogItemSummary> summaries,
                                boolean htmlFallback, boolean blocked, boolean skipped) {
    }

    /**
     * Checks every active subscription. The slow part — the network fetch to
     * Vinted — runs concurrently (bounded overall and per host); the DB
     * writes and Telegram delivery for each result are applied afterward,
     * sequentially, on the calling thread. Returns total pushed listings.
     */
    public int checkAll() {
        return checkSubs(subscriptions.allActive());
    }

    private int checkSubs(List<SearchSubscription> subs) {
        List<CompletableFuture<FetchResult>> futures = subs.stream()
                .map(sub -> CompletableFuture.supplyAsync(() -> fetchOnly(sub), executor()))
                .toList();
        int pushed = 0;
        for (CompletableFuture<FetchResult> f : futures) {
            pushed += applyFetchResult(f.join());
        }
        return pushed;
    }

    /**
     * Network-only step, safe to run off the calling thread: no DB or
     * notifier access here. Respects the host's anti-bot backoff and a
     * per-host concurrency cap (so parallel checks don't turn the previous
     * spaced-out request pattern into a burst against the same domain).
     */
    private FetchResult fetchOnly(SearchSubscription sub) {
        String host = hostOf(sub.getCatalogUrl());
        Instant pausedUntil = host == null ? Instant.EPOCH : pausedUntilByHost.getOrDefault(host, Instant.EPOCH);
        if (Instant.now().isBefore(pausedUntil)) {
            log.debug("Skipping sub {} — host {} paused until {} (anti-bot backoff)", sub.getId(), host, pausedUntil);
            return new FetchResult(sub, null, false, false, true);
        }

        Semaphore permit = host == null ? null : hostSemaphores.computeIfAbsent(
                host, h -> new Semaphore(Math.max(1, props.getMaxConcurrentPerHost())));
        if (permit != null) {
            try {
                permit.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new FetchResult(sub, null, false, false, true);
            }
        }
        try {
            int perPage = sub.isFast() ? props.getSnipePerPage() : props.getPerPage();
            List<CatalogItemSummary> summaries = apiClient.fetchCatalog(sub.getCatalogUrl(), perPage);
            return new FetchResult(sub, summaries, false, false, false);
        } catch (VintedParseException e) {
            if (e.getReason() == VintedParseException.Reason.BLOCKED) {
                if (host != null) {
                    pausedUntilByHost.put(host, Instant.now().plusMillis(props.getBackoffMs()));
                }
                log.warn("Anti-bot block on host {} (sub {}) — pausing that host for {} ms",
                        host, sub.getId(), props.getBackoffMs());
                return new FetchResult(sub, null, false, true, false);
            }
            log.info("API path failed for sub {} ({}), falling back to HTML", sub.getId(), e.getMessage());
            return new FetchResult(sub, null, true, false, false);
        } catch (Exception e) {
            log.warn("Monitor check failed for sub {} ({}): {}", sub.getId(), sub.getCatalogUrl(), e.getMessage());
            return new FetchResult(sub, null, false, false, true);
        } finally {
            if (permit != null) permit.release();
        }
    }

    /** DB writes + Telegram delivery for one fetch result — always runs on the calling thread. */
    private int applyFetchResult(FetchResult r) {
        if (r.skipped() || r.blocked()) return 0;
        try {
            return r.htmlFallback() ? checkOneViaHtml(r.sub()) : applyApiSummaries(r.sub(), r.summaries());
        } catch (Exception e) {
            log.warn("Monitor check failed for sub {} ({}): {}",
                    r.sub().getId(), r.sub().getCatalogUrl(), e.getMessage());
            return 0;
        }
    }

    private ExecutorService executor() {
        ExecutorService ex = checkExecutor;
        if (ex == null) {
            synchronized (this) {
                ex = checkExecutor;
                if (ex == null) {
                    int size = Math.max(1, props.getMaxConcurrentChecks());
                    ex = Executors.newFixedThreadPool(size, r -> {
                        Thread t = new Thread(r, "monitor-check");
                        t.setDaemon(true);
                        return t;
                    });
                    checkExecutor = ex;
                }
            }
        }
        return ex;
    }

    private String hostOf(String catalogUrl) {
        try {
            return URI.create(catalogUrl.trim()).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Checks one subscription. API-first: fetch summaries, diff ids against the
     * seen set, push compact cards for new ones. HTML fallback on API failure.
     */
    public int checkOne(SearchSubscription sub) {
        List<CatalogItemSummary> summaries;
        try {
            summaries = apiClient.fetchCatalog(sub.getCatalogUrl(), props.getPerPage());
        } catch (VintedParseException e) {
            if (e.getReason() == VintedParseException.Reason.BLOCKED) throw e;
            log.info("API path failed for sub {} ({}), falling back to HTML", sub.getId(), e.getMessage());
            return checkOneViaHtml(sub);
        }
        return applyApiSummaries(sub, summaries);
    }

    /** Diffs freshly-fetched API summaries against the seen set and delivers new ones. */
    private int applyApiSummaries(SearchSubscription sub, List<CatalogItemSummary> summaries) {
        Set<String> seen = subscriptions.seenIds(sub);
        if (seen == null) {   // never checked: seed quietly, don't flood
            subscriptions.markCheckedIds(sub, idsOf(summaries));
            return 0;
        }

        List<CatalogItemSummary> fresh = new ArrayList<>();
        for (CatalogItemSummary s : summaries) {
            if (s.getId() != null && !seen.contains(s.getId())) fresh.add(s);
        }

        int sent = 0;
        SendTarget target = targetOf(sub);
        if (target != null && notifier != null) {
            for (CatalogItemSummary s : fresh) {
                if (sent >= props.getMaxNewPerCycle()) break;
                long sendStart = System.currentTimeMillis();
                notifier.sendCard(target, null, ListingCard.of(s));
                long telegramMs = System.currentTimeMillis() - sendStart;
                if (s.getUploadedAt() != null) {
                    // The real speed metric: time from Vinted upload to our Telegram push.
                    log.info("Sub {}: item {} pushed {} s after upload (telegram {} ms)", sub.getId(), s.getId(),
                            Duration.between(s.getUploadedAt(), Instant.now()).toSeconds(), telegramMs);
                } else {
                    log.info("Sub {}: item {} pushed (telegram {} ms)", sub.getId(), s.getId(), telegramMs);
                }
                saveToHistory(sub.getUserId(), s);
                sent++;
            }
        }
        subscriptions.markCheckedIds(sub, idsOf(summaries));
        if (!fresh.isEmpty()) {
            log.info("Sub {}: {} new listing(s), pushed {} (api)", sub.getId(), fresh.size(), sent);
        }
        return sent;
    }

    /** Fallback: HTML catalog page + full item-page parse for each new listing. */
    private int checkOneViaHtml(SearchSubscription sub) {
        List<String> current = parser.fetchCatalogItemUrls(sub.getCatalogUrl());
        Set<String> seen = subscriptions.seenIds(sub);
        if (seen == null) {
            subscriptions.markChecked(sub, current);
            return 0;
        }
        List<String> fresh = new ArrayList<>();
        for (String url : current) {
            String id = VintedParserService.extractItemId(url);
            if (id != null && !seen.contains(id)) fresh.add(url);
        }
        int sent = 0;
        SendTarget target = targetOf(sub);
        if (target != null && notifier != null) {
            for (String url : fresh) {
                if (sent >= props.getMaxNewPerCycle()) break;
                try {
                    VintedItem item = parser.parseVintedUrl(url);
                    historyService.save(sub.getUserId(), item);
                    notifier.sendCard(target, null, ListingCard.of(item));
                    sent++;
                } catch (Exception e) {
                    log.warn("Failed to parse/push new listing {}: {}", url, e.getMessage());
                }
            }
        }
        subscriptions.markChecked(sub, current);
        if (!fresh.isEmpty()) {
            log.info("Sub {}: {} new listing(s), pushed {} (html)", sub.getId(), fresh.size(), sent);
        }
        return sent;
    }

    // ----------------------------------------------------------------- utils

    private void saveToHistory(Long userId, CatalogItemSummary s) {
        try {
            historyService.save(userId, VintedItem.builder()
                    .url(s.getUrl()).title(s.getTitle())
                    .price(s.getPrice()).currency(s.getCurrency())
                    .brand(s.getBrand()).size(s.getSize())
                    .condition(s.getCondition())
                    .imageUrls(s.getPhotoUrl() == null ? List.of() : List.of(s.getPhotoUrl()))
                    .build());
        } catch (Exception e) {
            log.debug("History save failed for pushed listing {}: {}", s.getUrl(), e.getMessage());
        }
    }

    private List<String> idsOf(List<CatalogItemSummary> summaries) {
        List<String> ids = new ArrayList<>();
        for (CatalogItemSummary s : summaries) {
            if (s.getId() != null) ids.add(s.getId());
        }
        return ids;
    }

    /** Delivery target: the subscription's chat/topic, or the owner's private chat. */
    private SendTarget targetOf(SearchSubscription sub) {
        Long chatId = sub.getChatId();
        if (chatId == null) {
            chatId = userRepository.findById(sub.getUserId()).map(u -> u.getChatId()).orElse(null);
        }
        return chatId == null ? null : new SendTarget(chatId, sub.getMessageThreadId());
    }

}
