package com.example.vintedbot;

import com.example.vintedbot.config.VintedParserProperties;
import com.example.vintedbot.config.WebDriverFactory;
import com.example.vintedbot.dto.CatalogItemSummary;
import com.example.vintedbot.dto.VintedItem;
import com.example.vintedbot.service.ImageCacheService;
import com.example.vintedbot.service.VintedApiClient;
import com.example.vintedbot.service.VintedParserService;
import com.example.vintedbot.util.UserAgentRotator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live catalog test against a REAL Vinted search URL. Disabled by default.
 * Enable with:
 *   VINTED_CATALOG_TEST_URL='https://www.vinted.de/catalog?search_text=nike&order=newest_first' mvn test
 */
@EnabledIfEnvironmentVariable(named = "VINTED_CATALOG_TEST_URL", matches = "https?://.*vinted.*catalog.*")
class CatalogLiveIntegrationTest {

    @Test
    void apiClient_fetchesFreshListingsFast() {
        VintedApiClient client = new VintedApiClient(
                new VintedParserProperties(), new UserAgentRotator(), new ObjectMapper());
        String url = VintedParserService.normalizeCatalogUrl(System.getenv("VINTED_CATALOG_TEST_URL"));

        long t0 = System.currentTimeMillis();
        List<CatalogItemSummary> items = client.fetchCatalog(url, 24);
        long elapsed = System.currentTimeMillis() - t0;

        System.out.println("API items: " + items.size() + " in " + elapsed + " ms");
        items.stream().limit(3).forEach(s -> System.out.println(
                "  " + s.getId() + " | " + s.getTitle() + " | " + s.getPrice() + " "
                        + s.getCurrency() + " | " + s.getBrand() + " | " + s.getSize()
                        + " | " + s.getCondition()));

        assertThat(items).isNotEmpty();
        assertThat(items.get(0).getId()).isNotBlank();
        assertThat(items.get(0).getUrl()).contains("/items/");

        // Second call reuses the cached session — should be fast.
        long t1 = System.currentTimeMillis();
        client.fetchCatalog(url, 24);
        System.out.println("Second call (cached session): " + (System.currentTimeMillis() - t1) + " ms");
    }

    @Test
    void fetchesCatalogAndParsesFreshestItem() {
        VintedParserProperties props = new VintedParserProperties();
        UserAgentRotator rotator = new UserAgentRotator();
        VintedParserService parser = new VintedParserService(
                props,
                new WebDriverFactory(props, new WebDriverFactory.UserAgentRotatorHolder(rotator)),
                rotator, new ImageCacheService(props), new ObjectMapper());

        String url = VintedParserService.normalizeCatalogUrl(System.getenv("VINTED_CATALOG_TEST_URL"));
        List<String> items = parser.fetchCatalogItemUrls(url);
        System.out.println("Catalog items found: " + items.size());
        items.stream().limit(3).forEach(u -> System.out.println("  " + u));

        assertThat(items).isNotEmpty();

        VintedItem first = parser.parseVintedUrl(items.get(0));
        System.out.println("Freshest parsed: " + first);
        assertThat(first.isEmpty()).isFalse();
    }
}
