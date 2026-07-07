package com.example.vintedbot;

import com.example.vintedbot.bot.VintedTelegramBot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BotUrlExtractionTest {

    @Test
    void extractsMultipleDistinctVintedUrls() {
        String text = """
                Посмотри эти:
                https://www.vinted.com/items/111-a
                https://www.vinted.de/items/222-b?referrer=x
                https://www.vinted.com/items/111-a
                и ещё https://vinted.co.uk/items/333-c
                """;
        List<String> urls = VintedTelegramBot.extractUrls(text);
        assertThat(urls).containsExactly(
                "https://www.vinted.com/items/111-a",
                "https://www.vinted.de/items/222-b?referrer=x",
                "https://vinted.co.uk/items/333-c");
    }

    @Test
    void ignoresNonVintedUrls() {
        List<String> urls = VintedTelegramBot.extractUrls(
                "https://example.com/items/1 and https://google.com");
        assertThat(urls).isEmpty();
    }

    @Test
    void handlesNoUrls() {
        assertThat(VintedTelegramBot.extractUrls("just some text")).isEmpty();
        assertThat(VintedTelegramBot.extractUrls(null)).isEmpty();
    }
}
