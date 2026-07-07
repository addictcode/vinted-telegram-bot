package com.example.vintedbot.dto;

import lombok.*;

/**
 * Compact listing data straight from Vinted's catalog JSON API — enough to
 * build a push card instantly without fetching the item page.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class CatalogItemSummary {

    private String id;
    private String url;
    private String title;
    private Double price;
    private String currency;
    private String brand;
    private String size;
    private String condition;
    private String photoUrl;
}
