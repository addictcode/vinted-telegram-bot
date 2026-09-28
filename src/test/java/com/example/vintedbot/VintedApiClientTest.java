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
    void apiQuery_forcesNewestFirstSoNewListingsAlwaysReachPageOne() {
        assertThat(VintedApiClient.apiQuery("brand_ids[]=201700&brand_ids[]=304403", 10))
                .isEqualTo("brand_ids[]=201700&brand_ids[]=304403&order=newest_first&page=1&per_page=10");
        assertThat(VintedApiClient.apiQuery("search_text=x&order=price_low_to_high", 10))
                .isEqualTo("search_text=x&order=newest_first&page=1&per_page=10");
    }

    @Test
    void apiQuery_handlesEmptyQuery() {
        assertThat(VintedApiClient.apiQuery(null, 24)).isEqualTo("order=newest_first&page=1&per_page=24");
        assertThat(VintedApiClient.apiQuery("", 24)).isEqualTo("order=newest_first&page=1&per_page=24");
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
        assertThat(items.get(1).getUploadedAt()).isNull();
    }

    @Test
    void parseItems_readsUploadTimeFromPhotoTimestamp() throws Exception {
        String json = """
                {"items":[{"id":1,"title":"x","path":"/items/1-x",
                  "photo":{"url":"https://images1.vinted.net/p.jpg",
                           "high_resolution":{"id":"abc","timestamp":1788268518}}}]}
                """;
        CatalogItemSummary item = client.parseItems(json, "www.vinted.de").get(0);
        assertThat(item.getUploadedAt()).isEqualTo(java.time.Instant.ofEpochSecond(1788268518));
    }

    @Test
    void proxyPool_parsesValidEntriesAndSkipsGarbage() {
        VintedParserProperties p = new VintedParserProperties();
        p.setProxies("http://user:p%40ss@10.0.0.1:8000, socks5://10.0.0.2:1080 , not a proxy");
        p.setProxy("10.0.0.3:3128");
        VintedApiClient pooled = new VintedApiClient(p, new UserAgentRotator(), new ObjectMapper());
        assertThat(pooled.endpointCount()).isEqualTo(3);
    }

    @Test
    void parseItems_failsLoudlyWhenResponseShapeChanges() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> client.parseItems("{\"results\":[]}", "www.vinted.de"))
                .isInstanceOf(com.example.vintedbot.service.VintedParseException.class);
    }

    @Test
    void noProxiesConfigured_goesDirect() {
        assertThat(client.endpointCount()).isEqualTo(1);
    }
}
