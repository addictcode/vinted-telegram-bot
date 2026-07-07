package com.example.vintedbot.service;

import com.example.vintedbot.config.VintedParserProperties;
import com.example.vintedbot.dto.CatalogItemSummary;
import com.example.vintedbot.util.UserAgentRotator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Lightweight client for Vinted's internal catalog JSON API
 * ({@code /api/v2/catalog/items}). ~200× smaller payload than the HTML page,
 * which makes near-real-time polling of saved searches feasible.
 *
 * Flow: bootstrap an anonymous session (cookies) from the domain homepage,
 * then query the API with those cookies. Sessions are cached per domain and
 * refreshed on expiry or 401/403.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VintedApiClient {

    /** Session lifetime before a proactive refresh. */
    private static final Duration SESSION_TTL = Duration.ofMinutes(20);
    private static final int TIMEOUT_MS = 15_000;

    private final VintedParserProperties props;
    private final UserAgentRotator userAgentRotator;
    private final ObjectMapper objectMapper;

    private record Session(Map<String, String> cookies, String userAgent, Instant createdAt) {
        boolean expired() {
            return Instant.now().isAfter(createdAt.plus(SESSION_TTL));
        }
    }

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    /**
     * Fetches the freshest listings for a catalog/search URL via the JSON API.
     * Returned in API order (newest first for {@code order=newest_first}).
     */
    public List<CatalogItemSummary> fetchCatalog(String catalogUrl, int perPage) {
        URI uri = URI.create(catalogUrl.trim());
        String host = uri.getHost();
        if (host == null || !host.contains("vinted")) {
            throw new VintedParseException(VintedParseException.Reason.INVALID_URL,
                    "Not a Vinted URL: " + catalogUrl);
        }
        jitter();
        try {
            return callApi(host, uri.getRawQuery(), perPage, true);
        } catch (VintedParseException e) {
            throw e;
        } catch (Exception e) {
            throw new VintedParseException(VintedParseException.Reason.UNKNOWN,
                    "Catalog API failed: " + e.getMessage(), e);
        }
    }

    private List<CatalogItemSummary> callApi(String host, String rawQuery, int perPage,
                                             boolean retryOnAuthFail) throws Exception {
        Session session = obtainSession(host);
        String api = "https://" + host + "/api/v2/catalog/items?" + apiQuery(rawQuery, perPage);

        Connection.Response res = Jsoup.connect(api)
                .userAgent(session.userAgent())
                .header("Cookie", cookieHeader(session.cookies()))
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", props.getAcceptLanguage())
                .referrer("https://" + host + "/catalog")
                .ignoreContentType(true)
                .ignoreHttpErrors(true)
                .maxBodySize(0)
                .timeout(TIMEOUT_MS)
                .method(Connection.Method.GET)
                .execute();

        int code = res.statusCode();
        if (code == 401 || code == 403 || code == 429) {
            sessions.remove(host);
            if (retryOnAuthFail && code != 429) {
                log.debug("API auth failure ({}) for {}, refreshing session once", code, host);
                jitter();
                return callApi(host, rawQuery, perPage, false);
            }
            throw new VintedParseException(VintedParseException.Reason.BLOCKED,
                    "Catalog API returned HTTP " + code);
        }
        if (code != 200) {
            throw new VintedParseException(VintedParseException.Reason.UNKNOWN,
                    "Catalog API returned HTTP " + code);
        }
        return parseItems(res.body(), host);
    }

    /**
     * Anonymous session: hit the homepage and accumulate cookies across the
     * redirect chain. The critical {@code access_token_web} cookie is set on an
     * intermediate hop, so we follow redirects manually (Jsoup's
     * {@code Response.cookies()} only carries the final hop's cookies).
     */
    private Session obtainSession(String host) throws Exception {
        Session cached = sessions.get(host);
        if (cached != null && !cached.expired()) {
            return cached;
        }
        String ua = userAgentRotator.random();
        Map<String, String> jar = new java.util.LinkedHashMap<>();
        String url = "https://" + host + "/";

        for (int hop = 0; hop < 6; hop++) {
            Connection.Response res = Jsoup.connect(url)
                    .userAgent(ua)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", props.getAcceptLanguage())
                    .cookies(jar)
                    .followRedirects(false)
                    .maxBodySize(500_000)
                    .timeout(TIMEOUT_MS)
                    .ignoreHttpErrors(true)
                    .method(Connection.Method.GET)
                    .execute();
            // Parse raw Set-Cookie lines: Vinted sends the JWT cookies twice
            // (a clearing empty value + the real value); Jsoup's cookie map
            // keeps the empty one, so we take the last NON-EMPTY value here.
            mergeSetCookies(jar, res);
            int code = res.statusCode();
            if (code >= 300 && code < 400 && res.header("Location") != null) {
                url = res.header("Location");
                if (url.startsWith("/")) url = "https://" + host + url;
                continue;
            }
            break;
        }

        if (!jar.containsKey("access_token_web")) {
            log.debug("Session for {} has no access_token_web cookie ({} total)", host, jar.size());
        }
        Session session = new Session(Map.copyOf(jar), ua, Instant.now());
        sessions.put(host, session);
        log.debug("Bootstrapped Vinted session for {} ({} cookies)", host, session.cookies().size());
        return session;
    }

    /**
     * Merges Set-Cookie headers into the jar, preferring non-empty values
     * (Vinted emits a clearing empty cookie alongside the real JWT).
     */
    private void mergeSetCookies(Map<String, String> jar, Connection.Response res) {
        List<String> setCookies = res.multiHeaders().get("Set-Cookie");
        if (setCookies == null) {
            res.cookies().forEach(jar::putIfAbsent);
            return;
        }
        for (String line : setCookies) {
            int semi = line.indexOf(';');
            String pair = semi >= 0 ? line.substring(0, semi) : line;
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String name = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            // Keep an existing non-empty value rather than overwriting with an empty one.
            if (value.isEmpty() && jar.getOrDefault(name, "").length() > 0) continue;
            jar.put(name, value);
        }
    }

    /**
     * Translates the web catalog query into API params: user filters pass
     * through 1:1; volatile params are dropped; paging pinned to page 1.
     */
    public static String apiQuery(String rawQuery, int perPage) {
        StringBuilder sb = new StringBuilder();
        if (rawQuery != null && !rawQuery.isBlank()) {
            for (String p : rawQuery.split("&")) {
                String key = p.split("=", 2)[0];
                if (key.equals("time") || key.equals("page") || key.equals("per_page")) continue;
                if (!sb.isEmpty()) sb.append('&');
                sb.append(p);
            }
        }
        if (!sb.isEmpty()) sb.append('&');
        sb.append("page=1&per_page=").append(perPage);
        return sb.toString();
    }

    /** Parses the API JSON body into summaries. */
    public List<CatalogItemSummary> parseItems(String json, String host) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode items = root.path("items");
        List<CatalogItemSummary> out = new ArrayList<>();
        for (JsonNode it : items) {
            String url = it.path("url").asText("");
            if (url.isEmpty()) {
                String path = it.path("path").asText("");
                if (!path.isEmpty()) url = "https://" + host + path;
            }
            Double price = null;
            String currency = null;
            JsonNode priceNode = it.path("price");
            if (priceNode.isObject()) {
                try {
                    price = Double.parseDouble(priceNode.path("amount").asText());
                } catch (NumberFormatException ignore) {
                    // leave null
                }
                currency = textOrNull(priceNode.path("currency_code"));
            }
            out.add(CatalogItemSummary.builder()
                    .id(it.path("id").asText(null))
                    .url(url)
                    .title(textOrNull(it.path("title")))
                    .price(price)
                    .currency(currency)
                    .brand(textOrNull(it.path("brand_title")))
                    .size(textOrNull(it.path("size_title")))
                    .condition(textOrNull(it.path("status")))
                    .photoUrl(textOrNull(it.path("photo").path("url")))
                    .build());
        }
        return out;
    }

    private String cookieHeader(Map<String, String> cookies) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static String textOrNull(JsonNode n) {
        return n.isValueNode() && !n.isNull() && !n.asText().isBlank() ? n.asText() : null;
    }

    /** Light pacing so API polling doesn't look machine-gun regular. */
    private void jitter() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(250, 750));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
