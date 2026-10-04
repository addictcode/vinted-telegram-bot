package com.example.vintedbot.service;

import com.example.vintedbot.config.MonitorProperties;
import com.example.vintedbot.dto.CatalogItemSummary;
import com.example.vintedbot.dto.ListingCard;
import com.example.vintedbot.dto.SendTarget;
import com.example.vintedbot.dto.VintedItem;
import com.example.vintedbot.model.SearchSubscription;
import com.example.vintedbot.model.User;
import com.example.vintedbot.repository.UserRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Near-real-time monitor for saved catalog searches. Primary path polls
 * Vinted's lightweight JSON API (seconds-level latency, cards built directly
 * from API data); if the API is unavailable it falls back to the HTML page +
 * full item parse.
 *
 * Network fetches run on a worker pool (bounded overall and per Vinted host);
 * their results are applied — DB writes, Telegram delivery — on the scheduler
 * thread as they complete, so one slow fetch never stalls the other searches.
 * A listing only counts as seen once it was actually delivered, so a Telegram
 * hiccup or the per-cycle cap delays it instead of losing it. An anti-bot block
 * pauses only that host, and the owner is alerted if a block lasts too long.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchMonitorService {

    /** Telegram delivery seam, registered by the bot on construction. */
    @FunctionalInterface
    public interface Notifier {
        /** @return true if Telegram accepted the message; false means retry on a later cycle. */
        boolean sendCard(SendTarget target, String header, ListingCard card);
    }

    /** Plain-text channel for operational alerts to the owner. */
    @FunctionalInterface
    public interface Alerter {
        void alert(Long chatId, String html);
    }

    private final SearchSubscriptionService subscriptions;
    private final VintedApiClient apiClient;
    private final VintedParserService parser;
    private final HistoryService historyService;
    private final UserRepository userRepository;
    private final MonitorProperties props;

    private volatile Notifier notifier;
    private volatile Alerter alerter;
    /** Per-host pause-until timestamp after an anti-bot block on that host. */
    private final ConcurrentHashMap<String, Instant> pausedUntilByHost = new ConcurrentHashMap<>();
    /** Per-host concurrency cap, created on demand. */
    private final ConcurrentHashMap<String, Semaphore> hostSemaphores = new ConcurrentHashMap<>();
    /** Lazily built so plain `new SearchMonitorService(...)` (tests, bypassing Spring) still works. */
    private volatile ExecutorService checkExecutor;
    /** Next due time (epoch ms) per subscription id; absent = due right now. */
    private final ConcurrentHashMap<Long, Long> nextDueAt = new ConcurrentHashMap<>();
    /** Fetches submitted by {@link #checkDue} whose results are not applied yet (scheduler thread only). */
    private final List<InFlight> pending = new ArrayList<>();
    /** Subscriptions with a fetch outstanding — never poll the same search twice at once. */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    /** When each currently blocked host first got blocked, and which ones the owner was told about. */
    private final ConcurrentHashMap<String, Instant> blockedSince = new ConcurrentHashMap<>();
    private final Set<String> alertedHosts = ConcurrentHashMap.newKeySet();
    /** Failed Telegram deliveries per "subId:itemKey"; after a few we stop retrying (dead chat). */
    private final ConcurrentHashMap<String, Integer> sendFailures = new ConcurrentHashMap<>();
    private static final int MAX_SEND_ATTEMPTS = 5;

    private record InFlight(Long subId, CompletableFuture<FetchResult> future) {
    }

    /**
     * Result of the network-only fetch step for one subscription. Carries no
     * side effects — DB writes and Telegram delivery happen afterward, on the
     * applying thread (see {@link #applyFetchResult}).
     */
    private record FetchResult(SearchSubscription sub, String host, List<CatalogItemSummary> summaries,
                               boolean htmlFallback, boolean blocked, boolean skipped) {
    }

    public void setNotifier(Notifier notifier) {
        this.notifier = notifier;
    }

    public void setAlerter(Alerter alerter) {
        this.alerter = alerter;
    }

    @Scheduled(fixedDelayString = "${vinted.monitor.tick-ms:250}",
            initialDelayString = "${vinted.monitor.initial-delay-ms:15000}")
    public void scheduledCheck() {
        if (!props.isEnabled() || notifier == null) return;
        checkDue();
    }

    /**
     * One scheduler tick: submits fetches for the subscriptions whose own interval
     * has elapsed (snipe-mode ones come around every second or so), then applies
     * whatever results are ready within one tick. Stragglers are applied on a later
     * tick instead of blocking everything else. Returns listings pushed this tick.
     */
    public synchronized int checkDue() {
        long now = System.currentTimeMillis();
        List<SearchSubscription> active = subscriptions.allActive();
        nextDueAt.keySet().retainAll(active.stream().map(SearchSubscription::getId).toList());

        long fastSubs = active.stream().filter(SearchSubscription::isFast).count();
        long normalSubs = active.size() - fastSubs;
        for (SearchSubscription s : active) {
            if (inFlight.contains(s.getId()) || nextDueAt.getOrDefault(s.getId(), 0L) > now) continue;
            long interval = s.isFast()
                    ? snipeIntervalMs(fastSubs, normalSubs, apiClient.endpointCount())
                    : props.getIntervalMs();
            nextDueAt.put(s.getId(), now + interval);
            inFlight.add(s.getId());
            pending.add(new InFlight(s.getId(), CompletableFuture.supplyAsync(() -> fetchOnly(s), executor())));
        }
        // Short wait: fast results land this tick; slower ones are applied on the next tick
        // instead of delaying the next round of due subscriptions.
        int pushed = drainPending(Math.max(10, props.getTickMs() / 5));
        checkBlockAlerts();
        return pushed;
    }

    /** Waits up to {@code waitMs} for outstanding fetches, then applies every finished one. */
    private int drainPending(long waitMs) {
        if (pending.isEmpty()) return 0;
        try {
            CompletableFuture.allOf(pending.stream().map(InFlight::future).toArray(CompletableFuture[]::new))
                    .get(waitMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Slow fetches stay pending and are applied on a later tick.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Individual failures are handled per future below.
        }
        int pushed = 0;
        for (Iterator<InFlight> it = pending.iterator(); it.hasNext(); ) {
            InFlight f = it.next();
            if (!f.future().isDone()) continue;
            it.remove();
            try {
                pushed += applyFetchResult(f.future().join());
            } catch (Exception e) {
                log.warn("Monitor fetch for sub {} failed unexpectedly: {}", f.subId(), e.getMessage());
            } finally {
                inFlight.remove(f.subId());
            }
        }
        return pushed;
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
     * Checks every active subscription and waits for all of them (manual/test
     * path). The scheduler uses the non-blocking {@link #checkDue} instead.
     */
    public int checkAll() {
        List<CompletableFuture<FetchResult>> futures = subscriptions.allActive().stream()
                .map(sub -> CompletableFuture.supplyAsync(() -> fetchOnly(sub), executor()))
                .toList();
        int pushed = 0;
        for (CompletableFuture<FetchResult> f : futures) {
            try {
                pushed += applyFetchResult(f.join());
            } catch (Exception e) {
                log.warn("Monitor fetch failed unexpectedly: {}", e.getMessage());
            }
        }
        return pushed;
    }

    /**
     * Network-only step, safe to run off the calling thread: no DB or
     * notifier access here. Respects the host's anti-bot backoff and a
     * per-host concurrency cap (so parallel checks don't turn a spaced-out
     * request pattern into a burst against the same domain).
     */
    private FetchResult fetchOnly(SearchSubscription sub) {
        String host = hostOf(sub.getCatalogUrl());
        Instant pausedUntil = host == null ? Instant.EPOCH : pausedUntilByHost.getOrDefault(host, Instant.EPOCH);
        if (Instant.now().isBefore(pausedUntil)) {
            log.debug("Skipping sub {} — host {} paused until {} (anti-bot backoff)", sub.getId(), host, pausedUntil);
            return new FetchResult(sub, host, null, false, false, true);
        }

        Semaphore permit = host == null ? null : hostSemaphores.computeIfAbsent(
                host, h -> new Semaphore(Math.max(1, props.getMaxConcurrentPerHost())));
        if (permit != null) {
            try {
                permit.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new FetchResult(sub, host, null, false, false, true);
            }
        }
        try {
            int perPage = sub.isFast() ? props.getSnipePerPage() : props.getPerPage();
            List<CatalogItemSummary> summaries = apiClient.fetchCatalog(sub.getCatalogUrl(), perPage);
            return new FetchResult(sub, host, summaries, false, false, false);
        } catch (VintedParseException e) {
            if (e.getReason() == VintedParseException.Reason.BLOCKED) {
                if (host != null) {
                    pausedUntilByHost.put(host, Instant.now().plusMillis(props.getBackoffMs()));
                }
                log.warn("Anti-bot block on host {} (sub {}) — pausing that host for {} ms",
                        host, sub.getId(), props.getBackoffMs());
                return new FetchResult(sub, host, null, false, true, false);
            }
            log.info("API path failed for sub {} ({}), falling back to HTML", sub.getId(), e.getMessage());
            return new FetchResult(sub, host, null, true, false, false);
        } catch (Exception e) {
            log.warn("Monitor check failed for sub {} ({}): {}", sub.getId(), sub.getCatalogUrl(), e.getMessage());
            return new FetchResult(sub, host, null, false, false, true);
        } finally {
            if (permit != null) permit.release();
        }
    }

    /** DB writes + Telegram delivery for one fetch result — runs on the applying thread. */
    private int applyFetchResult(FetchResult r) {
        if (r.blocked()) {
            if (r.host() != null) blockedSince.putIfAbsent(r.host(), Instant.now());
            return 0;
        }
        if (r.skipped()) return 0;
        if (r.host() != null) markHostHealthy(r.host());   // not blocked: API or HTML path is working
        try {
            return r.htmlFallback() ? checkOneViaHtml(r.sub()) : applyApiSummaries(r.sub(), r.summaries());
        } catch (Exception e) {
            log.warn("Monitor check failed for sub {} ({}): {}",
                    r.sub().getId(), r.sub().getCatalogUrl(), e.getMessage());
            return 0;
        }
    }

    // ----------------------------------------------------------- block alerts

    /** Tells the owner once when a host has been blocked for too long, so the bot never goes quietly blind. */
    private void checkBlockAlerts() {
        if (blockedSince.isEmpty() || alerter == null) return;
        Instant threshold = Instant.now().minusMillis(props.getBlockAlertAfterMs());
        blockedSince.forEach((host, since) -> {
            if (since.isBefore(threshold) && alertedHosts.add(host)) {
                long minutes = Duration.between(since, Instant.now()).toMinutes();
                alertOwner("🛡️ <b>Vinted блокирует бота</b> на " + host + " уже " + minutes + " мин.\n"
                        + "Новые объявления с этого сайта сейчас не приходят. "
                        + "Нужны прокси (VINTED_PROXIES) или больше IP.");
            }
        });
    }

    private void markHostHealthy(String host) {
        if (blockedSince.remove(host) != null && alertedHosts.remove(host)) {
            alertOwner("✅ Доступ к " + host + " восстановлен — объявления снова приходят.");
        }
    }

    private void alertOwner(String html) {
        Long chatId = props.getAlertChatId();
        if (chatId == null) {
            chatId = userRepository.findFirstByOrderByIdAsc().map(User::getChatId).orElse(null);
        }
        if (chatId == null) {
            log.warn("No owner chat to alert: {}", html);
            return;
        }
        try {
            alerter.alert(chatId, html);
        } catch (Exception e) {
            log.warn("Owner alert failed: {}", e.getMessage());
        }
    }

    // ----------------------------------------------------------- lifecycle

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

    @PreDestroy
    void shutdown() {
        ExecutorService ex = checkExecutor;
        if (ex != null) ex.shutdownNow();
    }

    private String hostOf(String catalogUrl) {
        try {
            return URI.create(catalogUrl.trim()).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    // ----------------------------------------------------------- delivery

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

    /**
     * Diffs freshly-fetched API summaries against the seen set and delivers new ones.
     * Only delivered listings join the seen set; anything held back by the per-cycle
     * cap or a failed Telegram send stays "new" and is retried next cycle.
     */
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

        Set<String> undelivered = new HashSet<>();
        int sent = 0;
        if (!fresh.isEmpty()) {
            SendTarget target = targetOf(sub);
            boolean canSend = target != null && notifier != null;
            for (CatalogItemSummary s : fresh) {
                if (!canSend || sent >= props.getMaxNewPerCycle()) {
                    undelivered.add(s.getId());
                    continue;
                }
                long sendStart = System.currentTimeMillis();
                if (!notifier.sendCard(target, null, ListingCard.of(s))) {
                    // Likely a Telegram rate limit: stop for this cycle, retry the rest next time.
                    canSend = false;
                    if (shouldRetry(sub, s.getId())) undelivered.add(s.getId());
                    continue;
                }
                sendFailures.remove(sub.getId() + ":" + s.getId());
                long telegramMs = System.currentTimeMillis() - sendStart;
                log.info("Sub {}: item {} pushed (telegram {} ms)", sub.getId(), s.getId(), telegramMs);
                saveToHistory(sub.getUserId(), s);
                sent++;
            }
            log.info("Sub {}: {} new listing(s), pushed {}, deferred {} (catalog)",
                    sub.getId(), fresh.size(), sent, undelivered.size());
        }
        subscriptions.markCheckedIds(sub, idsOf(summaries).stream().filter(id -> !undelivered.contains(id)).toList());
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
        Set<String> undelivered = new HashSet<>();
        int sent = 0;
        if (!fresh.isEmpty()) {
            SendTarget target = targetOf(sub);
            boolean canSend = target != null && notifier != null;
            for (String url : fresh) {
                if (!canSend || sent >= props.getMaxNewPerCycle()) {
                    undelivered.add(url);
                    continue;
                }
                try {
                    VintedItem item = parser.parseVintedUrl(url);
                    if (!notifier.sendCard(target, null, ListingCard.of(item))) {
                        canSend = false;
                        if (shouldRetry(sub, url)) undelivered.add(url);
                        continue;
                    }
                    sendFailures.remove(sub.getId() + ":" + url);
                    historyService.save(sub.getUserId(), item);
                    sent++;
                } catch (Exception e) {
                    log.warn("Failed to parse/push new listing {}: {}", url, e.getMessage());
                    if (shouldRetry(sub, url)) undelivered.add(url);
                }
            }
            log.info("Sub {}: {} new listing(s), pushed {}, deferred {} (html)",
                    sub.getId(), fresh.size(), sent, undelivered.size());
        }
        subscriptions.markChecked(sub, current.stream().filter(u -> !undelivered.contains(u)).toList());
        return sent;
    }

    // ----------------------------------------------------------------- utils

    /** Counts a failed delivery; false once it failed often enough that the chat is likely dead. */
    private boolean shouldRetry(SearchSubscription sub, String itemKey) {
        String key = sub.getId() + ":" + itemKey;
        int failures = sendFailures.merge(key, 1, Integer::sum);
        if (failures < MAX_SEND_ATTEMPTS) return true;
        sendFailures.remove(key);
        log.warn("Sub {}: giving up on {} after {} failed deliveries (chat unreachable?)",
                sub.getId(), itemKey, failures);
        return false;
    }

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
            chatId = userRepository.findById(sub.getUserId()).map(User::getChatId).orElse(null);
        }
        return chatId == null ? null : new SendTarget(chatId, sub.getMessageThreadId());
    }
}
