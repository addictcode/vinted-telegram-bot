package com.example.vintedbot.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "parsed_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ParsedItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "vinted_url", nullable = false, columnDefinition = "text")
    private String vintedUrl;

    @Column(name = "title", length = 512)
    private String title;

    @Column(name = "price")
    private Double price;

    @Column(name = "currency", length = 8)
    private String currency;

    @Column(name = "brand")
    private String brand;

    @Column(name = "size", length = 128)
    private String size;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "condition", length = 128)
    private String condition;

    @Column(name = "color", length = 128)
    private String color;

    /** JSON array of image URLs. */
    @Column(name = "image_urls", columnDefinition = "text")
    private String imageUrls;

    /** Raw scraped payload for debugging. */
    @Column(name = "raw_json", columnDefinition = "text")
    private String rawJson;

    @Column(name = "parsed_at", nullable = false)
    private OffsetDateTime parsedAt;

    @PrePersist
    void prePersist() {
        if (parsedAt == null) {
            parsedAt = OffsetDateTime.now();
        }
    }
}
