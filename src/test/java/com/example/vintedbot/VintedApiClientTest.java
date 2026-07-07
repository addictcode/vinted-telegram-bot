package com.example.vintedbot;

import com.example.vintedbot.config.VintedParserProperties;
import com.example.vintedbot.dto.CatalogItemSummary;
import com.example.vintedbot.service.VintedApiClient;
import com.example.vintedbot.util.UserAgentRotator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VintedApiClientTest {

    private final VintedApiClient client = new VintedApiClient(
            new VintedParserProperties(), new UserAgentRotator(), new ObjectMapper());

    @Test
    void apiQuery_passesFiltersDropsVolatileParams() {
        String q = VintedApiClient.apiQuery(
                "search_text=swear&order=newest_first&page=3&time=1783208420&per_page=96", 24);
        assertThat(q).isEqualTo("search_text=swear&order=newest_first&page=1&per_page=24");
    }

    @Test
    void apiQuery_handlesEmptyQuery() {
        assertThat(VintedApiClient.apiQuery(null, 24)).isEqualTo("page=1&per_page=24");
        assertThat(VintedApiClient.apiQuery("", 24)).isEqualTo("page=1&per_page=24");
    }

    @Test
    void parseItems_readsRealApiShape() throws Exception {
        // Mirrors the real /api/v2/catalog/items response shape.
        String json = """
                {"items":[
                  {"id":9320433616,"title":"Legging - Low ankle",
                   "price":{"amount":"8.0","currency_code":"EUR"},
                   "brand_title":"SWEAR","size_title":"XXS / 32 / 4","status":"Sehr gut",
                   "path":"/items/9320433616-legging-low-ankle",
                   "url":"https://www.vinted.de/items/9320433616-legging-low-ankle",
                   "photo":{"url":"https://images1.vinted.net/photo.jpg"}},
                  {"id":123,"title":"No price item","price":null,"path":"/items/123-x"}
                ]}
                """;
        List<CatalogItemSummary> items = client.parseItems(json, "www.vinted.de");

        assertThat(items).hasSize(2);
        CatalogItemSummary first = items.get(0);
        assertThat(first.getId()).isEqualTo("9320433616");
        assertThat(first.getTitle()).isEqualTo("Legging - Low ankle");
        assertThat(first.getPrice()).isEqualTo(8.0);
        assertThat(first.getCurrency()).isEqualTo("EUR");
        assertThat(first.getBrand()).isEqualTo("SWEAR");
        assertThat(first.getSize()).isEqualTo("XXS / 32 / 4");
        assertThat(first.getCondition()).isEqualTo("Sehr gut");
        assertThat(first.getPhotoUrl()).contains("images1.vinted.net");
        // Second item: url derived from path, price absent.
        assertThat(items.get(1).getUrl()).isEqualTo("https://www.vinted.de/items/123-x");
        assertThat(items.get(1).getPrice()).isNull();
    }
}
