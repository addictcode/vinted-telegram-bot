package com.example.vintedbot.service;

import com.example.vintedbot.config.VintedParserProperties;
import com.example.vintedbot.dto.CatalogItemSummary;
import com.example.vintedbot.util.UserAgentRotator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URI;
import java.net.URLDecoder;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

/**
 * Catalog poller. Vinted dropped the JSON catalog API ({@code /api/v2/catalog/items}
 * now 404s), so listings are read from the server-rendered catalog page, where
 * Next.js embeds them as JSON (~530 KB gzipped, ~0.9 s per poll).
 *
 * Flow: bootstrap an anonymous session (cookies) from the domain homepage,
 * then fetch the catalog page with those cookies. Requests rotate across the configured
 * proxy endpoints (or go direct when none are set); each endpoint keeps its own
 * per-domain session, since anti-bot systems tie cookies to the client IP.
 */
@Slf4j
@Service
public class VintedApiClient {

    /** Session lifetime before a proactive refresh. */
    private static final Duration SESSION_TTL = Duration.ofMinutes(20);
    private static final int TIMEOUT_MS = 15_000;
    /** Proxy credentials by "host:port", consulted by the JVM-wide Authenticator. */
    private static final Map<String, PasswordAuthentication> PROXY_CREDENTIALS = new ConcurrentHashMap<>();
    private static final AtomicBoolean AUTHENTICATOR_INSTALLED = new AtomicBoolean();
    /** Answers proxy auth challenges from the credentials parsed out of proxy URLs. */
    private static final Authenticator PROXY_AUTHENTICATOR = new Authenticator() {
        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
            // Proxy challenges only: never hand proxy credentials to an origin server's 401.
            if (getRequestorType() != RequestorType.PROXY) return null;
            return PROXY_CREDENTIALS.get(getRequestingHost() + ":" + getRequestingPort());
        }
    };

    private final VintedParserProperties props;
    private final UserAgentRotator userAgentRotator;
    private final ObjectMapper objectMapper;
    /** Outbound routes: DIRECT, or one per configured proxy. */
    private final List<Endpoint> endpoints;
    /** One keep-alive HTTP/2 client per endpoint (none for SOCKS — java.net.http can't tunnel via SOCKS). */
    private final Map<String, HttpClient> httpClients;
    private final AtomicInteger roundRobin = new AtomicInteger();
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> bootstrapLocks = new ConcurrentHashMap<>();
    /** Per (endpoint, domain) cool-down after a block, so rotation skips burnt routes. */
    private final ConcurrentHashMap<String, Instant> coolingUntil = new ConcurrentHashMap<>();

    record Endpoint(String key, Proxy proxy) {
        static final Endpoint DIRECT = new Endpoint("direct", null);
    }

    private record Session(Map<String, String> cookies, String userAgent, Instant createdAt) {
        boolean expired() {
            return Instant.now().isAfter(createdAt.plus(SESSION_TTL));
        }
    }

    public VintedApiClient(VintedParserProperties props, UserAgentRotator userAgentRotator,
                           ObjectMapper objectMapper) {
        this.props = props;
        this.userAgentRotator = userAgentRotator;
        this.objectMapper = objectMapper;
        this.endpoints = buildEndpoints(props);
        this.httpClients = buildHttpClients(endpoints);
        if (endpoints.get(0).proxy() != null) {
            log.info("Vinted API client rotating across {} proxy endpoint(s)", endpoints.size());
        }
    }

    public int endpointCount() {
        return endpoints.size();
    }

    /**
     * Best-effort proactive refresh so a session's TTL never expires in the
     * middle of a real poll cycle — moves the redirect-chain bootstrap cost
     * off the hot delivery path. No-ops for sessions that are still fresh.
     */
    public void warmSession(String host) {
        if (host == null) return;
        for (Endpoint ep : endpoints) {
            if (isCooling(ep, host)) continue;
            Session cached = sessions.get(key(ep, host));
            if (cached != null && !nearExpiry(cached)) continue;
            try {
                obtainSession(host, ep);
            } catch (Exception e) {
                log.debug("Proactive session warmup failed for {} via {}: {}", host, ep.key(), e.getMessage());
            }
        }
    }

    /**
     * Fetches the freshest listings for a catalog/search URL via the JSON API.
     * Returned in API order (newest first for {@code order=newest_first}).
     * With several proxies, a block on one endpoint is retried once through
     * another before surfacing as {@link VintedParseException.Reason#BLOCKED}.
     */
    public List<CatalogItemSummary> fetchCatalog(String catalogUrl, int perPage) {
        URI uri = URI.create(catalogUrl.trim());
        String host = uri.getHost();
        if (host == null || !host.contains("vinted")) {
            throw new VintedParseException(VintedParseException.Reason.INVALID_URL,
                    "Not a Vinted URL: " + catalogUrl);
        }
        jitter();
        Endpoint first = pickEndpoint(host, null);
        if (first == null) {
            throw new VintedParseException(VintedParseException.Reason.BLOCKED,
                    "All proxy endpoints are cooling down for " + host);
        }
        try {
            return attempt(host, first, uri.getRawQuery(), perPage);
        } catch (VintedParseException e) {
            if (e.getReason() != VintedParseException.Reason.BLOCKED || endpoints.size() < 2) throw e;
            coolDown(first, host);
            Endpoint alt = pickEndpoint(host, first);
            if (alt == null) throw e;
            log.debug("Endpoint {} blocked for {}, retrying via {}", first.key(), host, alt.key());
            try {
                return attempt(host, alt, uri.getRawQuery(), perPage);
            } catch (VintedParseException e2) {
                if (e2.getReason() == VintedParseException.Reason.BLOCKED) coolDown(alt, host);
                throw e2;
            }
        }
    }

    private List<CatalogItemSummary> attempt(String host, Endpoint ep, String rawQuery, int perPage) {
        try {
            return callApi(host, ep, rawQuery, perPage, true);
        } catch (VintedParseException e) {
            throw e;
        } catch (Exception e) {
            throw new VintedParseException(VintedParseException.Reason.UNKNOWN,
                    "Catalog API failed: " + e.getMessage(), e);
        }
    }

    private List<CatalogItemSummary> callApi(String host, Endpoint ep, String rawQuery, int perPage,
                                             boolean retryOnAuthFail) throws Exception {
        Session session = obtainSession(host, ep);
        // Bare `_rsc` is what Next.js redirects RSC requests to; sending it upfront saves that 307 hop.
        String page = "https://" + host + "/catalog?" + catalogQuery(rawQuery) + "&_rsc";

        HttpResult res = get(ep, page, session, "https://" + host + "/catalog");

        int code = res.status();
        if (code == 401 || code == 403 || code == 429) {
            sessions.remove(key(ep, host));
            if (retryOnAuthFail && code != 429) {
                log.debug("API auth failure ({}) for {} via {}, refreshing session once", code, host, ep.key());
                jitter();
                return callApi(host, ep, rawQuery, perPage, false);
            }
            throw new VintedParseException(VintedParseException.Reason.BLOCKED,
                    "Catalog page returned HTTP " + code);
        }
        if (code != 200) {
            throw new VintedParseException(VintedParseException.Reason.UNKNOWN,
                    "Catalog page returned HTTP " + code);
        }
        List<CatalogItemSummary> items = parseCatalogHtml(res.body(), host);
        return items.size() > perPage ? items.subList(0, perPage) : items;
    }

    record HttpResult(int status, String body) {
    }

    /** Hot-path GET: reuses the endpoint's keep-alive client; SOCKS endpoints fall back to Jsoup. */
    private HttpResult get(Endpoint ep, String url, Session session, String referer) throws Exception {
        String cookie = cookieHeader(session.cookies());
        HttpClient client = httpClients.get(ep.key());
        if (client == null) {
            Connection.Response r = connect(url, ep)
                    .userAgent(session.userAgent())
                    .header("Cookie", cookie)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", props.getAcceptLanguage())
                    .header("RSC", "1")
                    .referrer(referer)
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .maxBodySize(0)
                    .timeout(TIMEOUT_MS)
                    .method(Connection.Method.GET)
                    .execute();
            return new HttpResult(r.statusCode(), r.body());
        }
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(TIMEOUT_MS))
                .header("User-Agent", session.userAgent())
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", props.getAcceptLanguage())
                .header("Accept-Encoding", "gzip")
                // Ask Next.js for the bare flight payload (no HTML): ~40% faster, slightly smaller.
                .header("RSC", "1")
                .header("Referer", referer)
                .GET();
        if (!cookie.isEmpty()) req.header("Cookie", cookie);
        return send(client, req.build());
    }

    /** Sends a request and decodes a gzip body (java.net.http doesn't decompress on its own). */
    static HttpResult send(HttpClient client, HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<InputStream> resp = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        InputStream in = resp.body();
        boolean gzip = resp.headers().firstValue("Content-Encoding")
                .map(v -> v.equalsIgnoreCase("gzip")).orElse(false);
        try (InputStream body = gzip ? new GZIPInputStream(in) : in) {
            return new HttpResult(resp.statusCode(), new String(body.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /**
     * Anonymous session: hit the homepage and accumulate cookies across the
     * redirect chain. The critical {@code access_token_web} cookie is set on an
     * intermediate hop, so we follow redirects manually (Jsoup's
     * {@code Response.cookies()} only carries the final hop's cookies).
     */
    private Session obtainSession(String host, Endpoint ep) throws Exception {
        String sessionKey = key(ep, host);
        Session cached = sessions.get(sessionKey);
        if (cached != null && !cached.expired()) {
            return cached;
        }
        // Single-flight per (endpoint, host): parallel polls must not both spend the 6-hop bootstrap.
        synchronized (bootstrapLocks.computeIfAbsent(sessionKey, k -> new Object())) {
            cached = sessions.get(sessionKey);
            if (cached != null && !cached.expired()) {
                return cached;
            }
            return bootstrapSession(host, ep, sessionKey);
        }
    }

    private Session bootstrapSession(String host, Endpoint ep, String sessionKey) throws Exception {
        String ua = userAgentRotator.random();
        Map<String, String> jar = new java.util.LinkedHashMap<>();
        String url = "https://" + host + "/";

        for (int hop = 0; hop < 6; hop++) {
            Connection.Response res = connect(url, ep)
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
            log.debug("Session for {} via {} has no access_token_web cookie ({} total)", host, ep.key(), jar.size());
        }
        Session session = new Session(Map.copyOf(jar), ua, Instant.now());
        sessions.put(sessionKey, session);
        log.debug("Bootstrapped Vinted session for {} via {} ({} cookies)", host, ep.key(), session.cookies().size());
        return session;
    }

    // --------------------------------------------------------- endpoint routing

    private Connection connect(String url, Endpoint ep) {
        Connection c = Jsoup.connect(url);
        if (ep.proxy() != null) c.proxy(ep.proxy());
        return c;
    }

    /** Next non-cooling endpoint in round-robin order, or null if none is usable. */
    private Endpoint pickEndpoint(String host, Endpoint exclude) {
        int n = endpoints.size();
        if (n == 1) return exclude == null ? endpoints.get(0) : null;
        for (int i = 0; i < n; i++) {
            Endpoint ep = endpoints.get(Math.floorMod(roundRobin.getAndIncrement(), n));
            if (ep.equals(exclude) || isCooling(ep, host)) continue;
            return ep;
        }
        return null;
    }

    private void coolDown(Endpoint ep, String host) {
        coolingUntil.put(key(ep, host), Instant.now().plusMillis(props.getProxyCooldownMs()));
        log.info("Proxy endpoint {} cooling down for {} ({} ms)", ep.key(), host, props.getProxyCooldownMs());
    }

    private boolean isCooling(Endpoint ep, String host) {
        return Instant.now().isBefore(coolingUntil.getOrDefault(key(ep, host), Instant.EPOCH));
    }

    private static String key(Endpoint ep, String host) {
        return ep.key() + "|" + host;
    }

    private boolean nearExpiry(Session s) {
        return Instant.now().isAfter(s.createdAt().plus(SESSION_TTL.minusMinutes(3)));
    }

    /** Parses {@code vinted.parser.proxies} + legacy {@code proxy} into endpoints. */
    static List<Endpoint> buildEndpoints(VintedParserProperties props) {
        LinkedHashSet<String> specs = new LinkedHashSet<>();
        String all = nullToEmpty(props.getProxies()) + "," + nullToEmpty(props.getProxy());
        for (String s : all.split("[,\\s]+")) {
            if (!s.isBlank()) specs.add(s.trim());
        }
        List<Endpoint> out = new ArrayList<>();
        for (String spec : specs) {
            Endpoint ep = parseProxy(spec);
            if (ep != null) out.add(ep);
        }
        if (out.isEmpty() && !specs.isEmpty()) {
            log.error("Proxies are configured but none could be parsed — polling DIRECT, expect IP bans");
        }
        return out.isEmpty() ? List.of(Endpoint.DIRECT) : List.copyOf(out);
    }

    /** {@code http://user:pass@host:port} or {@code socks5://host:port}; bare {@code host:port} = HTTP. */
    static Endpoint parseProxy(String spec) {
        try {
            URI u = URI.create(spec.contains("://") ? spec : "http://" + spec);
            if (u.getHost() == null || u.getPort() < 0) {
                log.warn("Ignoring proxy entry without host:port");
                return null;
            }
            Proxy.Type type = u.getScheme().toLowerCase().startsWith("socks") ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
            String hostPort = u.getHost() + ":" + u.getPort();
            String userInfo = u.getRawUserInfo();
            if (userInfo != null && userInfo.contains(":")) {
                int c = userInfo.indexOf(':');
                PROXY_CREDENTIALS.put(hostPort, new PasswordAuthentication(
                        URLDecoder.decode(userInfo.substring(0, c), StandardCharsets.UTF_8),
                        URLDecoder.decode(userInfo.substring(c + 1), StandardCharsets.UTF_8).toCharArray()));
                installAuthenticator();
            }
            return new Endpoint(hostPort, new Proxy(type, new InetSocketAddress(u.getHost(), u.getPort())));
        } catch (Exception e) {
            // Never log the spec itself — it may contain credentials.
            log.warn("Ignoring malformed proxy entry: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    private static void installAuthenticator() {
        if (!AUTHENTICATOR_INSTALLED.compareAndSet(false, true)) return;
        Authenticator.setDefault(PROXY_AUTHENTICATOR);
    }

    private static Map<String, HttpClient> buildHttpClients(List<Endpoint> endpoints) {
        Map<String, HttpClient> clients = new ConcurrentHashMap<>();
        for (Endpoint ep : endpoints) {
            if (ep.proxy() != null && ep.proxy().type() != Proxy.Type.HTTP) continue;
            HttpClient.Builder b = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_2)
                    .connectTimeout(Duration.ofMillis(TIMEOUT_MS))
                    .followRedirects(HttpClient.Redirect.NORMAL);
            if (ep.proxy() != null) {
                b.proxy(ProxySelector.of((InetSocketAddress) ep.proxy().address()))
                        .authenticator(PROXY_AUTHENTICATOR);
            }
            clients.put(ep.key(), b.build());
        }
        return clients;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    // ------------------------------------------------------------------ parsing

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
     * Catalog page query: the user's filters pass through 1:1, volatile params
     * (time/page/per_page) are dropped, and order is always newest-first — with
     * any other sort a brand-new listing may never reach page 1 and is missed.
     */
    public static String catalogQuery(String rawQuery) {
        StringBuilder sb = new StringBuilder();
        if (rawQuery != null && !rawQuery.isBlank()) {
            for (String p : rawQuery.split("&")) {
                String key = p.split("=", 2)[0];
                if (key.equals("time") || key.equals("page") || key.equals("per_page") || key.equals("order")) continue;
                if (!sb.isEmpty()) sb.append('&');
                sb.append(p);
            }
        }
        if (!sb.isEmpty()) sb.append('&');
        sb.append("order=newest_first");
        return sb.toString();
    }

    private static final String FLIGHT_MARKER = "self.__next_f.push([1,\"";
    private static final String ITEMS_MARKER = "\"items\":{\"items\":[";

    /**
     * Vinted removed the JSON catalog API; listings are now server-rendered and
     * embedded in the page as a Next.js flight payload. This pulls the catalog's
     * item array out of it, newest first, skipping promoted (ad) slots.
     */
    public List<CatalogItemSummary> parseCatalogHtml(String body, String host) throws Exception {
        // RSC responses are the flight payload itself; full HTML pages embed it in script chunks.
        String flight = body.contains(FLIGHT_MARKER) ? flightPayload(body) : body;
        int at = flight.indexOf(ITEMS_MARKER);
        if (at < 0) {
            // Shape changed or a challenge page: fail loudly instead of reading as "nothing new".
            throw new VintedParseException(VintedParseException.Reason.UNKNOWN,
                    "Catalog page has no items payload");
        }
        JsonNode items;
        try (var parser = objectMapper.createParser(flight.substring(flight.indexOf('[', at)))) {
            items = objectMapper.readTree(parser);
        }
        List<CatalogItemSummary> out = new ArrayList<>();
        for (JsonNode el : items) {
            JsonNode p = el.path("productItem");
            if (p.path("isPromoted").asBoolean(false)) continue;
            String id = textOrNull(p.path("id"));
            if (id == null) continue;
            String url = textOrNull(p.path("url"));
            if (url != null && url.startsWith("/")) url = "https://" + host + url;

            Double price = null;
            try {
                price = Double.parseDouble(p.path("price").path("amount").asText());
            } catch (NumberFormatException ignore) {
                // leave null
            }
            String photo = textOrNull(p.path("photos").path(0).path("url"));
            if (photo == null) photo = textOrNull(p.path("thumbnailUrl"));

            // itemBox.secondLine is "size · condition", or just the condition when there's no size.
            String size = null;
            String condition = null;
            String second = textOrNull(p.path("itemBox").path("secondLine"));
            if (second != null) {
                String[] parts = second.split(" · ");
                condition = parts[parts.length - 1].trim();
                if (parts.length > 1) size = parts[0].trim();
            }
            out.add(CatalogItemSummary.builder()
                    .id(id)
                    .url(url)
                    .title(textOrNull(p.path("title")))
                    .price(price)
                    .currency(textOrNull(p.path("price").path("currencyCode")))
                    .brand(textOrNull(p.path("itemBox").path("firstLine")))
                    .size(size)
                    .condition(condition)
                    .photoUrl(photo)
                    .build());
        }
        return out;
    }

    /** Concatenates the page's {@code self.__next_f.push([1,"..."])} string chunks, JSON-unescaped. */
    String flightPayload(String html) throws Exception {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while ((i = html.indexOf(FLIGHT_MARKER, i)) >= 0) {
            int open = i + FLIGHT_MARKER.length() - 1;   // the opening quote
            int j = open + 1;
            while (j < html.length()) {
                char c = html.charAt(j);
                if (c == '\\') {
                    j += 2;
                } else if (c == '"') {
                    break;
                } else {
                    j++;
                }
            }
            if (j >= html.length()) break;
            out.append(objectMapper.readValue(html.substring(open, j + 1), String.class));
            i = j + 1;
        }
        return out.toString();
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
        if (!n.isValueNode() || n.isNull()) return null;
        String t = n.asText();
        // Next.js serialises missing values as the literal "$undefined".
        return t.isBlank() || t.equals("$undefined") ? null : t;
    }

    /**
     * Light pacing so polling doesn't look machine-gun regular. It exists to space
     * requests on one IP; with N rotating IPs each one is already hit N× less often,
     * so the pause shrinks accordingly instead of taxing every poll.
     */
    private void jitter() {
        int n = endpoints.size();
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(250 / n, 750 / n + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
