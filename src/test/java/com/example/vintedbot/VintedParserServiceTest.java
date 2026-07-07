package com.example.vintedbot;

import com.example.vintedbot.config.VintedParserProperties;
import com.example.vintedbot.config.WebDriverFactory;
import com.example.vintedbot.dto.VintedItem;
import com.example.vintedbot.service.ImageCacheService;
import com.example.vintedbot.service.VintedParseException;
import com.example.vintedbot.service.VintedParserService;
import com.example.vintedbot.util.UserAgentRotator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VintedParserServiceTest {

    private VintedParserService parser;

    @BeforeEach
    void setUp() {
        VintedParserProperties props = new VintedParserProperties();
        props.setMinDelayMs(0);
        props.setMaxDelayMs(0);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        // These pure-parsing tests never hit the browser or image cache, so real
        // collaborators aren't needed here (avoids a mocking framework entirely).
        ImageCacheService imageCache = new ImageCacheService(props);
        parser = new VintedParserService(
                props,
                new WebDriverFactory(props, new WebDriverFactory.UserAgentRotatorHolder(new UserAgentRotator())),
                new UserAgentRotator(),
                imageCache,
                mapper);
    }

    @Test
    void validateUrl_stripsQueryParams() {
        String result = parser.validateUrl("https://www.vinted.com/items/123456789-nike-hoodie?referrer=abc");
        assertThat(result).isEqualTo("https://www.vinted.com/items/123456789-nike-hoodie");
    }

    @Test
    void validateUrl_rejectsNonVinted() {
        assertThatThrownBy(() -> parser.validateUrl("https://example.com/items/1"))
                .isInstanceOf(VintedParseException.class);
    }

    @Test
    void extractPrice_parsesCurrencyAndAmount() {
        assertThat(parser.extractPrice("€35.00")).isEqualTo(35.0);
        assertThat(parser.extractPrice("35,50 €")).isEqualTo(35.5);
        assertThat(parser.extractPrice("$12")).isEqualTo(12.0);
        assertThat(parser.extractPrice("no price here")).isNull();
    }

    @Test
    void extractItem_readsJsonLd() {
        String html = """
                <html><head>
                <script type="application/ld+json">
                {"@type":"Product","name":"Nike Vintage Hoodie",
                 "description":"Rare vintage hoodie",
                 "brand":{"name":"Nike"},
                 "offers":{"price":"35.00","priceCurrency":"EUR"}}
                </script>
                <meta property="og:image" content="https://images.vinted.net/photo.jpg"/>
                </head><body><h1>Nike Vintage Hoodie</h1></body></html>
                """;
        Document doc = Jsoup.parse(html, "https://www.vinted.com/items/1");
        VintedItem item = parser.extractItem(doc, "https://www.vinted.com/items/1");

        assertThat(item.getTitle()).isEqualTo("Nike Vintage Hoodie");
        assertThat(item.getBrand()).isEqualTo("Nike");
        assertThat(item.getPrice()).isEqualTo(35.0);
        assertThat(item.getCurrency()).isEqualTo("EUR");
        assertThat(item.getImageUrls()).contains("https://images.vinted.net/photo.jpg");
    }

    @Test
    void extractItem_fallsBackToOgTags() {
        String html = """
                <html><head>
                <meta property="og:title" content="Adidas Jacket"/>
                <meta property="og:description" content="Nice jacket"/>
                </head><body><h1>Adidas Jacket</h1></body></html>
                """;
        Document doc = Jsoup.parse(html, "https://www.vinted.com/items/2");
        VintedItem item = parser.extractItem(doc, "https://www.vinted.com/items/2");

        assertThat(item.getTitle()).isEqualTo("Adidas Jacket");
        assertThat(item.getDescription()).isEqualTo("Nice jacket");
    }

    @Test
    void extractItem_readsSizeConditionColorFromItemprops() {
        // Mirrors Vinted's real details-list markup (microdata itemprops).
        String html = """
                <html><head>
                <script type="application/ld+json">
                {"@type":"Product","name":"Nike Air Force","offers":{"price":"60","priceCurrency":"USD"}}
                </script></head>
                <body><h1>Nike Air Force</h1>
                <div class="details-list__item-value" itemProp="size"><span>11</span></div>
                <div class="details-list__item-value" itemProp="status"><span>New with tags</span></div>
                <div class="details-list__item-value" itemProp="color"><span>White</span></div>
                </body></html>
                """;
        Document doc = Jsoup.parse(html, "https://www.vinted.com/items/1");
        VintedItem item = parser.extractItem(doc, "https://www.vinted.com/items/1");

        assertThat(item.getSize()).isEqualTo("11");
        assertThat(item.getCondition()).isEqualTo("New with tags");
        assertThat(item.getColor()).isEqualTo("White");
    }

    @Test
    void isCatalogUrl_detectsSearchLinks() {
        assertThat(parser.isCatalogUrl(
                "https://www.vinted.de/catalog?search_text=swear&order=newest_first&page=1&time=1783208420"))
                .isTrue();
        assertThat(parser.isCatalogUrl("https://www.vinted.com/catalog")).isTrue();
        assertThat(parser.isCatalogUrl("https://www.vinted.com/items/123-x")).isFalse();
        assertThat(parser.isCatalogUrl("https://example.com/catalog?x=1")).isFalse();
    }

    @Test
    void normalizeCatalogUrl_dropsVolatileParams() {
        assertThat(VintedParserService.normalizeCatalogUrl(
                "https://www.vinted.de/catalog?search_text=swear&order=newest_first&page=1&time=1783208420"))
                .isEqualTo("https://www.vinted.de/catalog?search_text=swear&order=newest_first");
        assertThat(VintedParserService.normalizeCatalogUrl("https://www.vinted.de/catalog"))
                .isEqualTo("https://www.vinted.de/catalog");
    }

    @Test
    void extractCatalogItemUrls_returnsPageOrderDeduped() {
        String html = """
                <html><body>
                <a href="https://www.vinted.de/items/9320433616-legging?referrer=catalog">1</a>
                <a href="https://www.vinted.de/items/9319981967-top?referrer=catalog">2</a>
                <a href="https://www.vinted.de/items/9320433616-legging?referrer=catalog">dup</a>
                <a href="https://www.vinted.de/member/12345">not an item</a>
                </body></html>
                """;
        Document doc = Jsoup.parse(html, "https://www.vinted.de/catalog?search_text=x");
        assertThat(parser.extractCatalogItemUrls(doc)).containsExactly(
                "https://www.vinted.de/items/9320433616-legging",
                "https://www.vinted.de/items/9319981967-top");
    }

    @Test
    void extractItemId_pullsNumericId() {
        assertThat(VintedParserService.extractItemId("https://www.vinted.de/items/9320433616-legging"))
                .isEqualTo("9320433616");
        assertThat(VintedParserService.extractItemId("https://www.vinted.de/catalog?x=1")).isNull();
    }

    @Test
    void extractImages_collectsVintedImages() {
        String html = """
                <html><body>
                <img src="https://images.vinted.net/a.jpg"/>
                <img src="https://cdn.other.com/b.png"/>
                <img data-src="https://images.vinted.net/c.webp"/>
                </body></html>
                """;
        Document doc = Jsoup.parse(html, "https://www.vinted.com/items/3");
        List<String> images = parser.extractImages(doc);

        assertThat(images).contains("https://images.vinted.net/a.jpg",
                "https://images.vinted.net/c.webp");
        assertThat(images).doesNotContain("https://cdn.other.com/b.png");
    }
}
