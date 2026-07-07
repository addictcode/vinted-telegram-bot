package com.example.vintedbot.service;

import com.example.vintedbot.dto.VintedItem;
import com.example.vintedbot.model.ParsedItem;
import com.example.vintedbot.repository.ParsedItemRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class HistoryService {

    public static final int PAGE_SIZE = 10;

    private final ParsedItemRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional
    public ParsedItem save(Long userId, VintedItem item) {
        ParsedItem entity = ParsedItem.builder()
                .userId(userId)
                .vintedUrl(item.getUrl())
                .title(item.getTitle())
                .price(item.getPrice())
                .currency(item.getCurrency())
                .brand(item.getBrand())
                .size(item.getSize())
                .description(item.getDescription())
                .condition(item.getCondition())
                .color(item.getColor())
                .imageUrls(writeJson(item.getImageUrls()))
                .rawJson(item.getRawJson())
                .parsedAt(OffsetDateTime.now())
                .build();
        return repository.save(entity);
    }

    @Transactional(readOnly = true)
    public java.util.Optional<ParsedItem> get(Long userId, Long itemId) {
        return repository.findByIdAndUserId(itemId, userId);
    }

    @Transactional(readOnly = true)
    public Page<ParsedItem> history(Long userId, int page) {
        return repository.findByUserIdOrderByParsedAtDesc(
                userId, PageRequest.of(page, PAGE_SIZE));
    }

    @Transactional
    public long clear(Long userId) {
        return repository.deleteByUserId(userId);
    }

    /** Deletes a single history entry that belongs to the given user. */
    @Transactional
    public boolean deleteOne(Long userId, Long itemId) {
        return repository.findByIdAndUserId(itemId, userId).map(item -> {
            repository.delete(item);
            return true;
        }).orElse(false);
    }

    @Transactional(readOnly = true)
    public long totalCount(Long userId) {
        return repository.countByUserId(userId);
    }

    @Transactional(readOnly = true)
    public OffsetDateTime earliest(Long userId) {
        return repository.findEarliestParsedAt(userId);
    }

    private String writeJson(List<String> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize image urls: {}", e.getMessage());
            return "[]";
        }
    }
}
