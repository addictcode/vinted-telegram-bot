package com.example.vintedbot;

import com.example.vintedbot.config.VintedParserProperties;
import com.example.vintedbot.config.WebDriverFactory;
import com.example.vintedbot.dto.VintedItem;
import com.example.vintedbot.service.ImageCacheService;
import com.example.vintedbot.service.VintedParserService;
import com.example.vintedbot.util.UserAgentRotator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live end-to-end test that hits a REAL Vinted listing through headless Chrome.
 *
 * Disabled by default (network + anti-bot flakiness). Enable with:
 *   VINTED_TEST_URL=https://www.vinted.com/items/123456789 mvn test
 *
 * Also serves as the "anti-bot bypass" smoke test — a successful parse means the
 * browser-like headers / UA rotation got past basic protections.
 */
@EnabledIfEnvironmentVariable(named = "VINTED_TEST_URL", matches = "https?://.*vinted.*")
class VintedLiveIntegrationTest {

    @Test
    void parsesRealListing() {
        VintedParserProperties props = new VintedParserProperties();
        props.setHeadless(true);
        UserAgentRotator rotator = new UserAgentRotator();
        WebDriverFactory factory = new WebDriverFactory(
                props, new WebDriverFactory.UserAgentRotatorHolder(rotator));
        VintedParserService parser = new VintedParserService(
                props, factory, rotator,
                new ImageCacheService(props),
                new ObjectMapper());

        String url = System.getenv("VINTED_TEST_URL");
        VintedItem item = parser.parseVintedUrl(url);

        assertThat(item).isNotNull();
        assertThat(item.isEmpty()).isFalse();
        System.out.println("Parsed: " + item);
    }
}
