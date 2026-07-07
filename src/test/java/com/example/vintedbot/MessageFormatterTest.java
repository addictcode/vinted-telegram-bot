package com.example.vintedbot;

import com.example.vintedbot.dto.VintedItem;
import com.example.vintedbot.service.MessageFormatter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MessageFormatterTest {

    private final MessageFormatter formatter = new MessageFormatter();

    @Test
    void formatItem_includesKeyFieldsAndEscapesHtml() {
        VintedItem item = VintedItem.builder()
                .url("https://www.vinted.com/items/1")
                .title("Nike <Hoodie> & Co")
                .price(35.0)
                .currency("EUR")
                .size("M")
                .brand("Nike")
                .condition("Like New")
                .description("Rare vintage")
                .imageUrls(List.of("https://images.vinted.net/a.jpg"))
                .build();

        String msg = formatter.formatItem(item);

        assertThat(msg).contains("€35");
        assertThat(msg).contains("Размер: M");
        assertThat(msg).contains("Бренд: Nike");
        assertThat(msg).contains("Like New");
        // HTML special chars escaped
        assertThat(msg).contains("Nike &lt;Hoodie&gt; &amp; Co");
        assertThat(msg).doesNotContain("<Hoodie>");
    }

    @Test
    void formatPrice_handlesNullAndCurrencies() {
        assertThat(formatter.formatPrice(null, "EUR")).isEqualTo("—");
        assertThat(formatter.formatPrice(35.0, "EUR")).isEqualTo("€35");
        assertThat(formatter.formatPrice(12.5, "USD")).isEqualTo("$12.5");
        assertThat(formatter.formatPrice(20.0, "PLN")).isEqualTo("PLN 20");
    }
}
