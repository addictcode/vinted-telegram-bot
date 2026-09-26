package com.example.vintedbot.service;

import com.example.vintedbot.config.MonitorProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class SnipeBudgetAndHttpTest {

    private final MonitorProperties props = new MonitorProperties();   // 20 req/min/IP, 20 s normal, 1 s floor
    private final SearchMonitorService monitor = new SearchMonitorService(null, null, null, null, null, props);

    @Test
    void singleIpAlreadySaturatedByNormalSubs_givesNoSnipeAdvantage() {
        // 10 normal subs × 3 req/min = 30 req/min > 20 budget → snipe falls back to the normal interval.
        assertThat(monitor.snipeIntervalMs(2, 10, 1)).isEqualTo(props.getIntervalMs());
    }

    @Test
    void moreProxies_spreadSpareBudgetAcrossSnipeSubs() {
        // 15 IPs × 20 = 300 req/min; 10 normal subs use 30 → 270 spare; 5 snipe subs → ~1.11 s each.
        assertThat(monitor.snipeIntervalMs(5, 10, 15)).isEqualTo(1112);
    }

    @Test
    void neverPollsFasterThanTheFloor() {
        assertThat(monitor.snipeIntervalMs(1, 0, 100)).isEqualTo(props.getSnipeMinIntervalMs());
    }

    @Test
    void send_decodesGzipBodies() throws Exception {
        byte[] json = "{\"items\":[]}".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
            out.write(json);
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", ex -> {
            ex.getResponseHeaders().add("Content-Encoding", "gzip");
            ex.sendResponseHeaders(200, gz.size());
            ex.getResponseBody().write(gz.toByteArray());
            ex.close();
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api");
            VintedApiClient.HttpResult res = VintedApiClient.send(HttpClient.newHttpClient(),
                    HttpRequest.newBuilder(uri).header("Accept-Encoding", "gzip").GET().build());
            assertThat(res.status()).isEqualTo(200);
            assertThat(res.body()).isEqualTo("{\"items\":[]}");
        } finally {
            server.stop(0);
        }
    }
}
