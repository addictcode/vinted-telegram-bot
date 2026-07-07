package com.example.vintedbot.dto;

import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain result object produced by the parser. Not a persistent entity —
 * it is mapped into {@link com.example.vintedbot.model.ParsedItem} before saving.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class VintedItem {

    private String url;
    private String title;
    private Double price;
    private String currency;
    private String brand;
    private String size;
    private String description;
    private String condition;
    private String color;
    @Builder.Default
    private List<String> imageUrls = new ArrayList<>();
    /** Raw scraped payload (JSON-LD / og-tags) kept for debugging. */
    private String rawJson;

    public boolean isEmpty() {
        return (title == null || title.isBlank()) && price == null
                && (brand == null || brand.isBlank());
    }
}
