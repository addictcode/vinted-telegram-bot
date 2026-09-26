package com.example.vintedbot.service;

import com.example.vintedbot.model.SearchSubscription;
import com.example.vintedbot.repository.SearchSubscriptionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

/** Manages saved catalog searches (the bot polls them for new listings). */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchSubscriptionService {

    /** Cap of remembered item ids per subscription (newest first). */
    public static final int MAX_SEEN_IDS = 300;

    private final SearchSubscriptionRepository repository;
    private final ObjectMapper objectMapper;

    public enum AddResult { ADDED, ALREADY_EXISTS }

    /** Immutable delivery + seed context for creating a subscription. */
    public record NewSubscription(Long userId, String normalizedUrl, String label,
                                  Long chatId, Long messageThreadId, String chatTitle,
                                  List<String> initialIds) {
    }

    @Transactional
    public AddResult subscribe(Long userId, String normalizedUrl, String label, List<String> initialItemUrls) {
        return create(new NewSubscription(userId, normalizedUrl, label, null, null, null,
                idsOf(initialItemUrls)));
    }

    @Transactional
    public AddResult create(NewSubscription ns) {
        if (repository.existsByUserIdAndCatalogUrl(ns.userId(), ns.normalizedUrl())) {
            return AddResult.ALREADY_EXISTS;
        }
        repository.save(SearchSubscription.builder()
                .userId(ns.userId())
                .catalogUrl(ns.normalizedUrl())
                .label(ns.label())
                .chatId(ns.chatId())
                .messageThreadId(ns.messageThreadId())
                .chatTitle(ns.chatTitle())
                .seenItemIds(toJson(ns.initialIds() == null ? List.of() : ns.initialIds()))
                .active(true)
                .createdAt(OffsetDateTime.now())
                .lastCheckedAt(OffsetDateTime.now())
                .build());
        return AddResult.ADDED;
    }

    /** Pause/resume delivery. Returns the new active state, or null if not found. */
    @Transactional
    public Boolean toggleActive(Long userId, Long id) {
        return repository.findByIdAndUserId(id, userId).map(s -> {
            s.setActive(!s.isActive());
            repository.save(s);
            return s.isActive();
        }).orElse(null);
    }

    /** Toggle snipe mode. Returns the new fast state, or null if not found. */
    @Transactional
    public Boolean toggleFast(Long userId, Long id) {
        return repository.findByIdAndUserId(id, userId).map(s -> {
            s.setFast(!s.isFast());
            repository.save(s);
            return s.isFast();
        }).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<SearchSubscription> list(Long userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public List<SearchSubscription> allActive() {
        return repository.findByActiveTrue();
    }

    @Transactional(readOnly = true)
    public Optional<SearchSubscription> get(Long userId, Long id) {
        return repository.findByIdAndUserId(id, userId);
    }

    @Transactional(readOnly = true)
    public long count(Long userId) {
        return repository.countByUserId(userId);
    }

    @Transactional
    public boolean updateLabel(Long userId, Long id, String label) {
        return repository.findByIdAndUserId(id, userId).map(s -> {
            s.setLabel(label);
            repository.save(s);
            return true;
        }).orElse(false);
    }

    @Transactional
    public boolean delete(Long userId, Long id) {
        return repository.findByIdAndUserId(id, userId).map(s -> {
            repository.delete(s);
            return true;
        }).orElse(false);
    }

    /** Decoded set of already-seen item ids ({@code null} = never checked). */
    public Set<String> seenIds(SearchSubscription sub) {
        if (sub.getSeenItemIds() == null) return null;
        try {
            List<String> ids = objectMapper.readValue(sub.getSeenItemIds(), new TypeReference<>() {});
            return new LinkedHashSet<>(ids);
        } catch (Exception e) {
            log.warn("Corrupt seen_item_ids for sub {}: {}", sub.getId(), e.getMessage());
            return new LinkedHashSet<>();
        }
    }

    /** Merges current page ids into the seen set (newest first, capped) and stamps the check time. */
    @Transactional
    public void markChecked(SearchSubscription sub, List<String> currentItemUrls) {
        markCheckedIds(sub, idsOf(currentItemUrls));
    }

    /** Same as {@link #markChecked} but with already-extracted item ids. */
    @Transactional
    public void markCheckedIds(SearchSubscription sub, List<String> currentIds) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(currentIds == null ? List.of() : currentIds);
        Set<String> prev = seenIds(sub);
        if (prev != null) merged.addAll(prev);
        List<String> capped = merged.stream().limit(MAX_SEEN_IDS).toList();
        sub.setSeenItemIds(toJson(capped));
        sub.setLastCheckedAt(OffsetDateTime.now());
        repository.save(sub);
    }

    private List<String> idsOf(List<String> itemUrls) {
        List<String> ids = new ArrayList<>();
        if (itemUrls == null) return ids;
        for (String u : itemUrls) {
            String id = VintedParserService.extractItemId(u);
            if (id != null && !ids.contains(id)) ids.add(id);
        }
        return ids;
    }

    private String toJson(List<String> ids) {
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (Exception e) {
            return "[]";
        }
    }
}
