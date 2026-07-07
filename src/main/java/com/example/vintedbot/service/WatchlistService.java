package com.example.vintedbot.service;

import com.example.vintedbot.dto.VintedItem;
import com.example.vintedbot.model.WatchedItem;
import com.example.vintedbot.repository.WatchedItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class WatchlistService {

    /** Safety cap so a single user can't store an unbounded number of links. */
    public static final int MAX_PER_USER = 100;

    private final WatchedItemRepository repository;

    @Transactional(readOnly = true)
    public List<WatchedItem> list(Long userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public long count(Long userId) {
        return repository.countByUserId(userId);
    }

    @Transactional(readOnly = true)
    public Optional<WatchedItem> get(Long userId, Long id) {
        return repository.findByIdAndUserId(id, userId);
    }

    public enum AddResult { ADDED, ALREADY_EXISTS, LIMIT_REACHED }

    @Transactional
    public AddResult add(Long userId, String url, String label, VintedItem snapshot) {
        if (repository.existsByUserIdAndVintedUrl(userId, url)) {
            return AddResult.ALREADY_EXISTS;
        }
        if (repository.countByUserId(userId) >= MAX_PER_USER) {
            return AddResult.LIMIT_REACHED;
        }
        WatchedItem.WatchedItemBuilder b = WatchedItem.builder()
                .userId(userId)
                .vintedUrl(url)
                .label(label)
                .active(true)
                .createdAt(OffsetDateTime.now());
        if (snapshot != null) {
            b.lastTitle(snapshot.getTitle())
                    .lastPrice(snapshot.getPrice())
                    .lastCurrency(snapshot.getCurrency())
                    .lastCheckedAt(OffsetDateTime.now());
            if (label == null || label.isBlank()) {
                b.label(snapshot.getTitle());
            }
        }
        repository.save(b.build());
        return AddResult.ADDED;
    }

    @Transactional
    public boolean updateLabel(Long userId, Long id, String label) {
        return repository.findByIdAndUserId(id, userId).map(item -> {
            item.setLabel(label);
            repository.save(item);
            return true;
        }).orElse(false);
    }

    /** Refreshes the stored snapshot (title/price) after a re-parse. */
    @Transactional
    public void applySnapshot(Long userId, Long id, VintedItem item) {
        repository.findByIdAndUserId(id, userId).ifPresent(w -> {
            w.setLastTitle(item.getTitle());
            w.setLastPrice(item.getPrice());
            w.setLastCurrency(item.getCurrency());
            w.setLastCheckedAt(OffsetDateTime.now());
            repository.save(w);
        });
    }

    @Transactional
    public boolean delete(Long userId, Long id) {
        return repository.findByIdAndUserId(id, userId).map(item -> {
            repository.delete(item);
            return true;
        }).orElse(false);
    }
}
