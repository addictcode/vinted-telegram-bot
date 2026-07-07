package com.example.vintedbot.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "watched_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WatchedItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "vinted_url", nullable = false, columnDefinition = "text")
    private String vintedUrl;

    /** User-editable label / note. */
    @Column(name = "label")
    private String label;

    @Column(name = "last_title", length = 512)
    private String lastTitle;

    @Column(name = "last_price")
    private Double lastPrice;

    @Column(name = "last_currency", length = 8)
    private String lastCurrency;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_checked_at")
    private OffsetDateTime lastCheckedAt;

    @Column(name = "active", nullable = false)
    private boolean active;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
