package com.example.vintedbot;

import com.example.vintedbot.config.VintedParserProperties;
import com.example.vintedbot.dto.CatalogItemSummary;
import com.example.vintedbot.service.VintedApiClient;
import com.example.vintedbot.service.VintedParseException;
import com.example.vintedbot.util.UserAgentRotator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VintedApiClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final VintedApiClient client = new VintedApiClient(
            new VintedParserProperties(), new UserAgentRotator(), mapper);

    /** Mirrors the real page: the flight payload is split across several push() script chunks. */
    private String catalogPage(String payload) throws Exception {
        int mid = payload.length() / 2;
        StringBuilder html = new StringBuilder("<html><body><div>grid</div>");
        for (String chunk : List.of(payload.substring(0, mid), payload.substring(mid))) {
            html.append("<script>self.__next_f.push([1,").append(mapper.writeValueAsString(chunk))
                    .append("])</script>");
        }
        return html.append("</body></html>").toString();
    }

    @Test
    void catalogQuery_passesFiltersDropsVolatileParams() {
        assertThat(VintedApiClient.catalogQuery(
                "search_text=swear&order=newest_first&page=3&time=1783208420&per_page=96"))
                .isEqualTo("search_text=swear&order=newest_first");
        assertThat(VintedApiClient.catalogQuery(null)).isEqualTo("order=newest_first");
    }

    @Test
    void catalogQuery_forcesNewestFirstSoNewListingsAlwaysReachPageOne() {
        assertThat(VintedApiClient.catalogQuery("brand_ids[]=201700&brand_ids[]=304403"))
                .isEqualTo("brand_ids[]=201700&brand_ids[]=304403&order=newest_first");
        assertThat(VintedApiClient.catalogQuery("search_text=x&order=price_low_to_high"))
                .isEqualTo("search_text=x&order=newest_first");
    }

    @Test
    void parseCatalogHtml_readsItemsFromNextFlightPayload() throws Exception {
        String payload = "0:{\"a\":1}\n7:[\"$\",\"div\",null,{\"catalog\":{\"items\":{\"items\":["
                + "{\"id\":101,\"productItem\":{\"id\":101,\"title\":\"Jeans [W32] \\\"Levi's\\\"\","
                + "\"url\":\"/items/101-jeans\",\"price\":{\"amount\":\"12.5\",\"currencyCode\":\"EUR\"},"
                + "\"isPromoted\":false,\"thumbnailUrl\":\"https://images1.vinted.net/310x430/a.webp\","
                + "\"photos\":[{\"url\":\"https://images1.vinted.net/f800/a.webp\"}],"
                + "\"itemBox\":{\"firstLine\":\"Levi's\",\"secondLine\":\"W32 · Bardzo dobry\"}}},"
                + "{\"id\":102,\"productItem\":{\"id\":102,\"title\":\"Ad\",\"isPromoted\":true}},"
                + "{\"id\":103,\"productItem\":{\"id\":103,\"title\":\"Cap\",\"url\":\"/items/103-cap\","
                + "\"price\":{\"amount\":\"?\",\"currencyCode\":\"PLN\"},\"isPromoted\":false,"
                + "\"thumbnailUrl\":\"https://images1.vinted.net/310x430/b.webp\",\"photos\":[],"
                + "\"itemBox\":{\"firstLine\":\"$undefined\",\"secondLine\":\"Nowy z metką\"}}}"
                + "]},\"uiState\":\"SUCCESS\"}}]";

        List<CatalogItemSummary> items = client.parseCatalogHtml(catalogPage(payload), "www.vinted.pl");

        assertThat(items).extracting(CatalogItemSummary::getId).containsExactly("101", "103");   // ad skipped
        CatalogItemSummary first = items.get(0);
        assertThat(first.getTitle()).isEqualTo("Jeans [W32] \"Levi's\"");
        assertThat(first.getUrl()).isEqualTo("https://www.vinted.pl/items/101-jeans");
        assertThat(first.getPrice()).isEqualTo(12.5);
        assertThat(first.getCurrency()).isEqualTo("EUR");
        assertThat(first.getBrand()).isEqualTo("Levi's");
        assertThat(first.getSize()).isEqualTo("W32");
        assertThat(first.getCondition()).isEqualTo("Bardzo dobry");
        assertThat(first.getPhotoUrl()).isEqualTo("https://images1.vinted.net/f800/a.webp");

        CatalogItemSummary second = items.get(1);
        assertThat(second.getBrand()).isNull();             // "$undefined"
        assertThat(second.getSize()).isNull();              // only a condition on the line
        assertThat(second.getCondition()).isEqualTo("Nowy z metką");
        assertThat(second.getPrice()).isNull();
        assertThat(second.getPhotoUrl()).contains("310x430/b.webp");   // thumbnail fallback
    }

    @Test
    void parseCatalogHtml_acceptsBareRscFlightResponse() throws Exception {
        String rsc = "1:\"$Sreact.fragment\"\n9:[\"$\",\"div\",null,{\"items\":{\"items\":["
                + "{\"id\":7,\"productItem\":{\"id\":7,\"title\":\"Tee\",\"url\":\"/items/7-tee\","
                + "\"price\":{\"amount\":\"5\",\"currencyCode\":\"EUR\"},\"isPromoted\":false}}]}}]";
        List<CatalogItemSummary> items = client.parseCatalogHtml(rsc, "www.vinted.de");
        assertThat(items).extracting(CatalogItemSummary::getUrl).containsExactly("https://www.vinted.de/items/7-tee");
    }

    @Test
    void parseCatalogHtml_failsLoudlyWhenPayloadIsMissing() throws Exception {
        String page = catalogPage("0:{\"challenge\":true}");
        assertThatThrownBy(() -> client.parseCatalogHtml(page, "www.vinted.pl"))
                .isInstanceOf(VintedParseException.class);
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
    void noProxiesConfigured_goesDirect() {
        assertThat(client.endpointCount()).isEqualTo(1);
    }
}
