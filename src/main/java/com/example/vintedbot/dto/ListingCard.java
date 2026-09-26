package com.example.vintedbot.dto;

import java.time.Instant;

/**
 * Compact data for a listing card: image + the few facts needed to decide
 * "buy or skip" in a glance (price, size, brand, how fresh it is) + link.
 * Used both for user-triggered parsing replies and monitor push messages.
 */
public record ListingCard(String photoUrl, String title, Double price, String currency, String url,
                          String size, String brand, Instant uploadedAt) {

    public static ListingCard of(VintedItem item) {
        String photo = (item.getImageUrls() == null || item.getImageUrls().isEmpty())
                ? null : item.getImageUrls().get(0);
        return new ListingCard(photo, item.getTitle(), item.getPrice(), item.getCurrency(), item.getUrl(),
                item.getSize(), item.getBrand(), null);
    }

    public static ListingCard of(CatalogItemSummary s) {
        return new ListingCard(s.getPhotoUrl(), s.getTitle(), s.getPrice(), s.getCurrency(), s.getUrl(),
                s.getSize(), s.getBrand(), s.getUploadedAt());
    }
}
