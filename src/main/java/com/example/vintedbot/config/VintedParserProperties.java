package com.example.vintedbot.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "vinted.parser")
public class VintedParserProperties {

    private boolean headless = true;
    /**
     * When true, a headless-Chrome fallback is used if the lightweight HTTP
     * (Jsoup) fetch hits an anti-bot challenge. Set false on minimal hosts
     * without Chrome (the bot then runs pure-HTTP + optional proxy).
     */
    private boolean seleniumEnabled = true;
    private String chromeDriverPath = "";
    private String chromeBinaryPath = "";
    private int pageLoadTimeoutSeconds = 30;
    private long minDelayMs = 2000;
    private long maxDelayMs = 5000;
    private int maxRetries = 3;
    private String proxy = "";
    /** Comma-separated proxy pool for the catalog API poller, e.g. {@code http://u:p@h1:8000,http://u:p@h2:8000}. */
    private String proxies = "";
    /** How long a proxy that got blocked is skipped for that Vinted domain. */
    private long proxyCooldownMs = 120_000;
    private String imageCacheDir = "/tmp/vinted_images";
    private String referer = "https://www.vinted.com/";
    private String acceptLanguage = "en-US,en;q=0.9";
}
