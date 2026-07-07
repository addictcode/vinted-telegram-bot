package com.example.vintedbot.service;

import com.example.vintedbot.config.VintedParserProperties;
import com.example.vintedbot.config.WebDriverFactory;
import com.example.vintedbot.dto.VintedItem;
import com.example.vintedbot.util.UserAgentRotator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a single Vinted listing page.
 *
 * Strategy:
 *   1. Render the page with headless Chrome (handles JS-driven content).
 *   2. If Selenium fails/times out, fall back to a plain Jsoup fetch.
 *   3. Extract data from JSON-LD, then Open Graph meta tags, then DOM selectors.
 *
 * NOTE: This scrapes public HTML for personal use. Respect Vinted's Terms of
 * Service and keep request volume low.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VintedParserService {

    private static final Pattern VINTED_URL =
            Pattern.compile("^https?://(www\\.)?vinted\\.[a-z.]+/(items|.*?/items)/(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CATALOG_URL =
            Pattern.compile("^https?://(www\\.)?vinted\\.[a-z.]+/catalog([/?#].*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ITEM_ID_IN_URL = Pattern.compile("/items/(\\d+)");
    private static final Pattern PRICE_NUMBER = Pattern.compile("([0-9]+(?:[.,][0-9]{1,2})?)");
    private static final Pattern CURRENCY = Pattern.compile("([€$£]|EUR|USD|GBP|PLN|CZK|SEK)", Pattern.CASE_INSENSITIVE);

    private final VintedParserProperties props;
    private final WebDriverFactory webDriverFactory;
    private final UserAgentRotator userAgentRotator;
    private final ImageCacheService imageCacheService;
    private final ObjectMapper objectMapper;

    /**
     * Entry point: validate, throttle, fetch (with retries + fallback), parse.
     */
    public VintedItem parseVintedUrl(String url) {
        String normalized = validateUrl(url);
        throttle();

        Document doc = fetchWithRetries(normalized);
        VintedItem item = extractItem(doc, normalized);

        if (item.isEmpty()) {
            throw new VintedParseException(VintedParseException.Reason.NOT_FOUND,
                    "Could not extract item details from page");
        }

        // Cache images best-effort; never fail the whole parse over caching.
        try {
            imageCacheService.cacheAll(item.getImageUrls());
        } catch (Exception e) {
            log.warn("Image caching failed for {}: {}", normalized, e.getMessage());
        }
        return item;
    }

    // ------------------------------------------------------------------ URL

    public String validateUrl(String url) {
        if (url == null) {
            throw new VintedParseException(VintedParseException.Reason.INVALID_URL, "URL is null");
        }
        String trimmed = url.trim();
        // Must be a Vinted domain AND an /items/<id> path.
        if (!VINTED_URL.matcher(trimmed).find()) {
            throw new VintedParseException(VintedParseException.Reason.INVALID_URL,
                    "Not a valid Vinted item URL: " + trimmed);
        }
        // strip tracking query params
        int q = trimmed.indexOf('?');
        return q > 0 ? trimmed.substring(0, q) : trimmed;
    }

    // ------------------------------------------------------------- catalog

    /** True for Vinted catalog/search URLs (filters, search_text, sorting…). */
    public boolean isCatalogUrl(String url) {
        return url != null && CATALOG_URL.matcher(url.trim()).matches();
    }

    /** Numeric item id from an item URL, or null. */
    public static String extractItemId(String url) {
        if (url == null) return null;
        Matcher m = ITEM_ID_IN_URL.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Drops volatile query params ({@code time}, {@code page}) so the same
     * filter always maps to the same subscription key.
     */
    public static String normalizeCatalogUrl(String url) {
        String trimmed = url.trim();
        int q = trimmed.indexOf('?');
        if (q < 0) return trimmed;
        String base = trimmed.substring(0, q);
        List<String> keep = new ArrayList<>();
        for (String p : trimmed.substring(q + 1).split("&")) {
            String key = p.split("=", 2)[0];
            if (!key.equals("time") && !key.equals("page")) keep.add(p);
        }
        return keep.isEmpty() ? base : base + "?" + String.join("&", keep);
    }

    /**
     * Fetches the first page of a catalog search and returns item URLs in page
     * order (for {@code order=newest_first} that is newest → oldest), deduped
     * and stripped of tracking params.
     */
    public List<String> fetchCatalogItemUrls(String catalogUrl) {
        if (!isCatalogUrl(catalogUrl)) {
            throw new VintedParseException(VintedParseException.Reason.INVALID_URL,
                    "Not a Vinted catalog URL: " + catalogUrl);
        }
        throttle();
        Document doc = fetchWithRetries(catalogUrl);
        return extractCatalogItemUrls(doc);
    }

    /** Extraction split out for tests (no network). */
    public List<String> extractCatalogItemUrls(Document doc) {
        Set<String> out = new LinkedHashSet<>();
        for (Element a : doc.select("a[href*=/items/]")) {
            String href = a.absUrl("href");
            if (href.isEmpty()) href = a.attr("href");
            if (!VINTED_URL.matcher(href).find()) continue;
            int q = href.indexOf('?');
            out.add(q > 0 ? href.substring(0, q) : href);
        }
        return new ArrayList<>(out);
    }

    // -------------------------------------------------------------- fetching

    /**
     * Fetch strategy: try the lightweight HTTP (Jsoup) path first — it's fast,
     * needs no browser, and works for most Vinted item pages. Only if that keeps
     * hitting a genuine anti-bot challenge do we spin up headless Chrome (and
     * only when {@code seleniumEnabled}). This keeps the runtime tiny so the bot
     * can live on minimal free hosts.
     */
    private Document fetchWithRetries(String url) {
        VintedParseException last = null;

        // 1) Primary: plain HTTP.
        for (int attempt = 1; attempt <= props.getMaxRetries(); attempt++) {
            try {
                return fetchWithJsoup(url);
            } catch (VintedParseException e) {
                last = e;
                if (e.getReason() == VintedParseException.Reason.INVALID_URL
                        || e.getReason() == VintedParseException.Reason.NOT_FOUND) {
                    throw e;
                }
                log.warn("HTTP attempt {}/{} for {} failed: {}",
                        attempt, props.getMaxRetries(), url, e.getMessage());
                if (attempt < props.getMaxRetries()) throttle();
            } catch (Exception e) {
                last = new VintedParseException(VintedParseException.Reason.UNKNOWN, e.getMessage(), e);
                log.warn("HTTP attempt {}/{} for {} errored: {}",
                        attempt, props.getMaxRetries(), url, e.getMessage());
                if (attempt < props.getMaxRetries()) throttle();
            }
        }

        // 2) Fallback: headless Chrome (heavier), only if enabled.
        if (props.isSeleniumEnabled()) {
            log.info("Falling back to headless Chrome for {}", url);
            try {
                return fetchWithSelenium(url);
            } catch (VintedParseException e) {
                last = e;
            } catch (Exception e) {
                last = new VintedParseException(VintedParseException.Reason.UNKNOWN, e.getMessage(), e);
            }
        }

        throw last != null ? last
                : new VintedParseException(VintedParseException.Reason.UNKNOWN, "Failed to fetch " + url);
    }

    private Document fetchWithSelenium(String url) {
        WebDriver driver = null;
        try {
            driver = webDriverFactory.create();
            driver.get(url);

            new WebDriverWait(driver, Duration.ofSeconds(props.getPageLoadTimeoutSeconds()))
                    .until(ExpectedConditions.or(
                            ExpectedConditions.presenceOfElementLocated(By.tagName("h1")),
                            ExpectedConditions.presenceOfElementLocated(
                                    By.cssSelector("script[type='application/ld+json']"))));

            String html = driver.getPageSource();
            Document doc = Jsoup.parse(html, url);
            detectBlock(doc);
            return doc;
        } catch (org.openqa.selenium.TimeoutException e) {
            throw new VintedParseException(VintedParseException.Reason.TIMEOUT,
                    "Selenium timed out loading " + url, e);
        } finally {
            if (driver != null) {
                try {
                    driver.quit();
                } catch (Exception ignore) {
                    // ignore driver teardown errors
                }
            }
        }
    }

    private Document fetchWithJsoup(String url) throws IOException {
        Document doc = Jsoup.connect(url)
                .userAgent(userAgentRotator.random())
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", props.getAcceptLanguage())
                .referrer(props.getReferer())
                .timeout(props.getPageLoadTimeoutSeconds() * 1000)
                // Vinted item pages exceed Jsoup's default 2 MB cap; without this
                // the tail (the details-list with size/colour/condition) is
                // silently truncated. 0 = unlimited.
                .maxBodySize(0)
                .ignoreHttpErrors(true)
                .get();
        detectBlock(doc);
        return doc;
    }

    /**
     * Distinguishes a real anti-bot challenge from a normal page that merely
     * embeds DataDome/Cloudflare monitoring JS (which every legit Vinted page
     * does). We flag a block only when the page <title> is a known challenge
     * title AND the page carries no real listing content.
     */
    private void detectBlock(Document doc) {
        String title = doc.title() == null ? "" : doc.title().toLowerCase();
        boolean challengeTitle = title.contains("just a moment")
                || title.contains("attention required")
                || title.contains("access denied")
                || title.contains("access to this page has been denied")
                || title.contains("you have been blocked")
                || title.contains("checking if the site connection is secure");

        boolean hasRealContent =
                doc.selectFirst("meta[property=og:title]") != null
                        || !doc.select("script[type=application/ld+json]").isEmpty()
                        || doc.selectFirst("h1") != null;

        // DataDome interstitial: full-page captcha with no product content.
        boolean dataDomeCaptcha =
                !doc.select("iframe[src*=captcha-delivery.com]").isEmpty() && !hasRealContent;

        if ((challengeTitle && !hasRealContent) || dataDomeCaptcha) {
            throw new VintedParseException(VintedParseException.Reason.BLOCKED,
                    "Anti-bot challenge page detected (title='" + doc.title() + "')");
        }
    }

    // --------------------------------------------------------------- parsing

    public VintedItem extractItem(Document doc, String url) {
        VintedItem.VintedItemBuilder b = VintedItem.builder().url(url);

        // 1) JSON-LD (most reliable when present)
        JsonNode ld = findProductJsonLd(doc);
        if (ld != null) {
            b.title(text(ld.path("name")));
            b.description(text(ld.path("description")));
            b.brand(text(ld.path("brand").path("name")));
            JsonNode offers = ld.path("offers");
            if (offers.has("price")) {
                b.price(parsePrice(offers.path("price").asText()).price);
                b.currency(text(offers.path("priceCurrency")));
            }
            b.rawJson(ld.toString());
        }

        // 2) Open Graph fallbacks
        b.title(coalesce(peek(b, VintedItem::getTitle), metaContent(doc, "og:title")));
        b.description(coalesce(peek(b, VintedItem::getDescription), metaContent(doc, "og:description")));

        // 3) DOM selectors (title / price / attributes)
        VintedItem partial = b.build();
        if (isBlank(partial.getTitle())) {
            Element h1 = doc.selectFirst("h1");
            if (h1 != null) b.title(h1.text().trim());
        }
        if (partial.getPrice() == null) {
            Element priceEl = doc.selectFirst("[data-testid*=price], .item-price, [class*=price]");
            if (priceEl != null) {
                ParsedPrice pp = parsePrice(priceEl.text());
                b.price(pp.price);
                if (pp.currency != null) b.currency(pp.currency);
            }
        }

        // Attribute table (size / brand / condition)
        Attributes attrs = extractAttributes(doc);
        if (isBlank(partial.getBrand())) b.brand(attrs.brand);
        b.size(attrs.size);
        b.condition(attrs.condition);
        b.color(attrs.color);

        // Images
        b.imageUrls(extractImages(doc));

        return b.build();
    }

    /** Finds a schema.org Product node inside any ld+json script. */
    private JsonNode findProductJsonLd(Document doc) {
        for (Element script : doc.select("script[type=application/ld+json]")) {
            try {
                JsonNode node = objectMapper.readTree(script.data());
                JsonNode product = findProduct(node);
                if (product != null) return product;
            } catch (Exception e) {
                log.debug("Skipping malformed ld+json block: {}", e.getMessage());
            }
        }
        return null;
    }

    private JsonNode findProduct(JsonNode node) {
        if (node == null) return null;
        if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode p = findProduct(child);
                if (p != null) return p;
            }
            return null;
        }
        JsonNode type = node.path("@type");
        if (type.isTextual() && type.asText().equalsIgnoreCase("Product")) {
            return node;
        }
        if (node.has("@graph")) {
            return findProduct(node.path("@graph"));
        }
        return null;
    }

    /** Public per the spec: extract image URLs from the document. */
    public List<String> extractImages(Document doc) {
        Set<String> images = new LinkedHashSet<>();

        for (Element og : doc.select("meta[property=og:image]")) {
            addIfImage(images, og.attr("content"));
        }
        for (Element img : doc.select("img")) {
            String src = img.hasAttr("src") ? img.attr("src") : img.attr("data-src");
            if (src.contains("vinted") || src.contains("vteimg")) {
                addIfImage(images, src);
            }
        }
        return new ArrayList<>(images);
    }

    private void addIfImage(Set<String> set, String url) {
        if (url != null && !url.isBlank()
                && (url.startsWith("http"))
                && (url.matches("(?i).*\\.(jpg|jpeg|png|webp).*") || url.contains("vteimg"))) {
            set.add(url);
        }
    }

    private Attributes extractAttributes(Document doc) {
        Attributes a = new Attributes();

        // Primary: Vinted's microdata itemprops on the details list.
        a.size = firstText(doc, "[itemprop=size]");
        a.condition = firstText(doc, "[itemprop=status]");
        a.brand = firstText(doc, "[itemprop=brand]");
        a.color = firstText(doc, "[itemprop=color]");

        // Brand fallback: the brand link in the attributes / breadcrumb.
        if (a.brand == null) {
            a.brand = firstText(doc,
                    "[data-testid=item-attributes-brand] a span, a[href*=/brand/] span[itemprop=name]");
        }

        // Legacy fallback: older label/value row markup.
        if (a.size == null || a.condition == null || a.brand == null) {
            for (Element row : doc.select("[data-testid*=item-attributes] div, .details-list__item, dl div")) {
                String text = row.text().toLowerCase();
                if (a.brand == null && text.startsWith("brand")) a.brand = valueAfterLabel(row);
                if (a.size == null && text.startsWith("size")) a.size = valueAfterLabel(row);
                if (a.condition == null && text.startsWith("condition")) a.condition = valueAfterLabel(row);
            }
        }
        return a;
    }

    /** Text of the first element matching {@code css}, or null if none/blank. */
    private String firstText(Document doc, String css) {
        Element el = doc.selectFirst(css);
        if (el == null) return null;
        String t = el.text().trim();
        return t.isEmpty() ? null : t;
    }

    private String valueAfterLabel(Element row) {
        Elements children = row.children();
        if (children.size() >= 2) {
            return children.get(children.size() - 1).text().trim();
        }
        // "Brand Nike" -> "Nike"
        String[] parts = row.text().split("\\s+", 2);
        return parts.length == 2 ? parts[1].trim() : null;
    }

    // ----------------------------------------------------------------- price

    /** Public per the spec: parse a price string into a Double. */
    public Double extractPrice(String priceText) {
        return parsePrice(priceText).price;
    }

    ParsedPrice parsePrice(String priceText) {
        ParsedPrice pp = new ParsedPrice();
        if (priceText == null || priceText.isBlank()) return pp;

        Matcher cm = CURRENCY.matcher(priceText);
        if (cm.find()) {
            pp.currency = normalizeCurrency(cm.group(1));
        }
        Matcher pm = PRICE_NUMBER.matcher(priceText.replace(",", "."));
        if (pm.find()) {
            try {
                pp.price = Double.parseDouble(pm.group(1));
            } catch (NumberFormatException ignore) {
                // leave price null
            }
        }
        return pp;
    }

    private String normalizeCurrency(String raw) {
        return switch (raw) {
            case "€" -> "EUR";
            case "$" -> "USD";
            case "£" -> "GBP";
            default -> raw.toUpperCase();
        };
    }

    // ----------------------------------------------------------------- utils

    private static class Attributes {
        String brand;
        String size;
        String condition;
        String color;
    }

    static class ParsedPrice {
        Double price;
        String currency;
    }

    private void throttle() {
        long min = Math.min(props.getMinDelayMs(), props.getMaxDelayMs());
        long max = Math.max(props.getMinDelayMs(), props.getMaxDelayMs());
        long delay = min == max ? min : ThreadLocalRandom.current().nextLong(min, max + 1);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String metaContent(Document doc, String property) {
        Element el = doc.selectFirst("meta[property=" + property + "]");
        return el != null ? el.attr("content").trim() : null;
    }

    private String text(JsonNode node) {
        return node != null && node.isValueNode() && !node.isNull() ? node.asText() : null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String coalesce(String a, String b) {
        return !isBlank(a) ? a : b;
    }

    private <R> R peek(VintedItem.VintedItemBuilder b, java.util.function.Function<VintedItem, R> getter) {
        return getter.apply(b.build());
    }
}
