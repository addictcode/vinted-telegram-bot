package com.example.vintedbot.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/** A saved catalog search the bot polls for newly appeared listings. */
@Entity
@Table(name = "search_subscriptions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SearchSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Normalized catalog URL (volatile params like time/page stripped). */
    @Column(name = "catalog_url", nullable = false, columnDefinition = "text")
    private String catalogUrl;

    /** User-editable label; defaults to the search text. */
    @Column(name = "label")
    private String label;

    /** Delivery target chat (private chat or group). Falls back to owner chat if null. */
    @Column(name = "chat_id")
    private Long chatId;

    /** Forum topic thread id inside a group (null = not a topic / private chat). */
    @Column(name = "message_thread_id")
    private Long messageThreadId;

    /** Group title, for display in /subs. */
    @Column(name = "chat_title")
    private String chatTitle;

    /**
     * JSON array of recently seen item ids. {@code null} means "never checked"
     * (seed quietly); an empty array means "checked, nothing existed yet".
     */
    @Column(name = "seen_item_ids", columnDefinition = "text")
    private String seenItemIds;

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
