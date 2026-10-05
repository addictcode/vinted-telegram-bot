package com.example.vintedbot.service;

import com.example.vintedbot.dto.CatalogItemSummary;
import com.example.vintedbot.dto.ListingCard;
import com.example.vintedbot.dto.VintedItem;
import com.example.vintedbot.model.ParsedItem;
import com.example.vintedbot.model.SearchSubscription;
import com.example.vintedbot.model.WatchedItem;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Builds Telegram messages using HTML parse mode (safer escaping than MarkdownV2).
 */
@Component
public class MessageFormatter {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm dd.MM.yyyy").withZone(ZoneId.systemDefault());

    private static final int DESC_LIMIT = 400;

    public String formatItem(VintedItem item) {
        StringBuilder sb = new StringBuilder();
        sb.append("👗 <b>").append(esc(orDash(item.getTitle()))).append("</b>\n\n");

        sb.append("💰 <b>").append(formatPrice(item.getPrice(), item.getCurrency())).append("</b>\n");
        sb.append("📦 Размер: ").append(esc(orDash(item.getSize()))).append("\n");
        sb.append("🏷️ Бренд: ").append(esc(orDash(item.getBrand()))).append("\n");
        sb.append("⭐ Состояние: ").append(esc(orDash(item.getCondition()))).append("\n");
        if (item.getColor() != null && !item.getColor().isBlank()) {
            sb.append("🎨 Цвет: ").append(esc(item.getColor())).append("\n");
        }

        if (item.getDescription() != null && !item.getDescription().isBlank()) {
            sb.append("\n📝 Описание: ").append(esc(truncate(item.getDescription()))).append("\n");
        }

        sb.append("\n🔗 <a href=\"").append(esc(item.getUrl())).append("\">Перейти на Vinted</a>\n");
        sb.append("⏰ Спарсено: ").append(TIME.format(OffsetDateTime.now()));
        return sb.toString();
    }

    public String formatHistoryEntry(ParsedItem item, int index) {
        return String.format("%d. <b>%s</b> — %s\n   🔗 <a href=\"%s\">ссылка</a> · ⏰ %s",
                index,
                esc(orDash(item.getTitle())),
                formatPrice(item.getPrice(), item.getCurrency()),
                esc(item.getVintedUrl()),
                TIME.format(item.getParsedAt()));
    }

    /**
     * Compact caption for a photo card: title, price · size · brand, and listing
     * age (+ optional header). Used as the photo caption, and as the text body
     * when no photo is available.
     */
    public String cardCaption(ListingCard card, String header) {
        StringBuilder sb = new StringBuilder();
        if (header != null && !header.isBlank()) {
            sb.append(header).append("\n");
        }
        sb.append("👗 <b>").append(esc(orDash(card.title()))).append("</b>\n");
        sb.append("💰 <b>").append(formatPrice(card.price(), card.currency())).append("</b>");
        if (card.size() != null && !card.size().isBlank()) sb.append(" · ").append(esc(card.size()));
        if (card.brand() != null && !card.brand().isBlank()) sb.append(" · ").append(esc(card.brand()));
        return sb.toString();
    }

    /** Compact card built from catalog-API data (no item-page fetch → instant). */
    public String formatSummary(CatalogItemSummary s) {
        StringBuilder sb = new StringBuilder();
        sb.append("👗 <b>").append(esc(orDash(s.getTitle()))).append("</b>\n");
        sb.append("💰 <b>").append(formatPrice(s.getPrice(), s.getCurrency())).append("</b>");
        if (s.getBrand() != null && !s.getBrand().isBlank()) {
            sb.append(" · 🏷️ ").append(esc(s.getBrand()));
        }
        if (s.getSize() != null && !s.getSize().isBlank()) {
            sb.append(" · 📦 ").append(esc(s.getSize()));
        }
        if (s.getCondition() != null && !s.getCondition().isBlank()) {
            sb.append(" · ⭐ ").append(esc(s.getCondition()));
        }
        sb.append("\n🔗 <a href=\"").append(esc(s.getUrl())).append("\">Перейти на Vinted</a>");
        return sb.toString();
    }

    /** One-line summary of a search subscription. */
    public String formatSubEntry(SearchSubscription s, int index) {
        String name = s.getLabel() != null && !s.getLabel().isBlank() ? s.getLabel() : "Vinted поиск";
        StringBuilder sb = new StringBuilder();
        sb.append("<b>").append(index).append(".</b> ")
                .append(s.isActive() ? (s.isFast() ? "⚡ " : "🔔 ") : "⏸ ").append(esc(name));
        if (!s.isActive()) sb.append(" <i>(на паузе)</i>");
        if (s.getChatTitle() != null && !s.getChatTitle().isBlank()) {
            sb.append(s.getMessageThreadId() != null ? " · 🧵 тема в «" : " · 👥 «")
                    .append(esc(s.getChatTitle())).append("»");
        }
        sb.append("\n   🔗 <a href=\"").append(esc(s.getCatalogUrl())).append("\">открыть фильтр</a>");
        if (s.getLastCheckedAt() != null) {
            sb.append(" · проверено ").append(TIME.format(s.getLastCheckedAt()));
        }
        return sb.toString();
    }

    /** One-line summary of a watchlist entry (index shown to the user). */
    public String formatWatchEntry(WatchedItem w, int index) {
        String name = w.getLabel() != null && !w.getLabel().isBlank()
                ? w.getLabel()
                : (w.getLastTitle() != null ? w.getLastTitle() : "без названия");
        StringBuilder sb = new StringBuilder();
        sb.append("<b>").append(index).append(".</b> ").append(esc(name));
        if (w.getLastPrice() != null) {
            sb.append(" — ").append(formatPrice(w.getLastPrice(), w.getLastCurrency()));
        }
        sb.append("\n   🔗 <a href=\"").append(esc(w.getVintedUrl())).append("\">ссылка</a>");
        return sb.toString();
    }

    public String formatPrice(Double price, String currency) {
        if (price == null) return "—";
        String symbol = switch (currency == null ? "" : currency) {
            case "EUR" -> "€";
            case "USD" -> "$";
            case "GBP" -> "£";
            default -> currency == null ? "" : currency + " ";
        };
        String amount = price == Math.floor(price)
                ? String.valueOf(price.intValue())
                : String.valueOf(price);
        return "EUR".equals(currency) || "USD".equals(currency) || "GBP".equals(currency)
                ? symbol + amount
                : (symbol + amount).trim();
    }

    private String truncate(String s) {
        if (s.length() <= DESC_LIMIT) return s;
        return s.substring(0, DESC_LIMIT).trim() + "…";
    }

    private String orDash(String s) {
        return (s == null || s.isBlank()) ? "—" : s;
    }

    /** Escapes the five HTML entities Telegram's HTML parse mode cares about. */
    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
