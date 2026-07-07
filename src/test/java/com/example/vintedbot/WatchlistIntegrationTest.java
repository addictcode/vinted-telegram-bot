package com.example.vintedbot;

import com.example.vintedbot.dto.VintedItem;
import com.example.vintedbot.model.ParsedItem;
import com.example.vintedbot.model.User;
import com.example.vintedbot.model.WatchedItem;
import com.example.vintedbot.repository.ParsedItemRepository;
import com.example.vintedbot.repository.UserRepository;
import com.example.vintedbot.repository.UserSubscriptionRepository;
import com.example.vintedbot.repository.WatchedItemRepository;
import com.example.vintedbot.service.HistoryService;
import com.example.vintedbot.service.UserService;
import com.example.vintedbot.service.WatchlistService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end persistence test for the watchlist + history logic against a real
 * (H2-in-PostgreSQL-mode) database, exercising the actual JPA mappings and the
 * service layer that the /watch, /list and /history commands rely on.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class WatchlistIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired UserSubscriptionRepository subscriptionRepository;
    @Autowired WatchedItemRepository watchedItemRepository;
    @Autowired ParsedItemRepository parsedItemRepository;

    private UserService userService;
    private WatchlistService watchlistService;
    private HistoryService historyService;
    private User user;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, subscriptionRepository);
        watchlistService = new WatchlistService(watchedItemRepository);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        historyService = new HistoryService(parsedItemRepository, mapper);
        user = userService.registerOrGet(1001L, "tester");
    }

    private VintedItem item(String url, String title, double price) {
        return VintedItem.builder()
                .url(url).title(title).price(price).currency("EUR")
                .brand("Nike").size("M").condition("Good").color("Black")
                .imageUrls(List.of("https://images.vinted.net/a.jpg")).build();
    }

    @Test
    void registersUserAndCreatesFreeSubscription() {
        assertThat(user.getId()).isNotNull();
        assertThat(userService.subscriptionLevel(user.getId()).name()).isEqualTo("FREE");
        // Idempotent: second /start returns the same user.
        assertThat(userService.registerOrGet(1001L, "tester").getId()).isEqualTo(user.getId());
    }

    @Test
    void savesHistoryWithAllFieldsIncludingColor() {
        ParsedItem saved = historyService.save(user.getId(),
                item("https://www.vinted.com/items/1", "Nike Hoodie", 35.0));
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getColor()).isEqualTo("Black");
        assertThat(saved.getImageUrls()).contains("images.vinted.net");
        assertThat(historyService.totalCount(user.getId())).isEqualTo(1);
    }

    @Test
    void addToWatchlist_dedupesAndDefaultsLabelToTitle() {
        var r1 = watchlistService.add(user.getId(), "https://www.vinted.com/items/1", null,
                item("https://www.vinted.com/items/1", "Nike Hoodie", 35.0));
        var r2 = watchlistService.add(user.getId(), "https://www.vinted.com/items/1", null, null);

        assertThat(r1).isEqualTo(WatchlistService.AddResult.ADDED);
        assertThat(r2).isEqualTo(WatchlistService.AddResult.ALREADY_EXISTS);

        List<WatchedItem> list = watchlistService.list(user.getId());
        assertThat(list).hasSize(1);
        assertThat(list.get(0).getLabel()).isEqualTo("Nike Hoodie"); // defaulted from title
        assertThat(list.get(0).getLastPrice()).isEqualTo(35.0);
    }

    @Test
    void editLabelAndDelete() {
        watchlistService.add(user.getId(), "https://www.vinted.com/items/2", null,
                item("https://www.vinted.com/items/2", "Adidas", 20.0));
        Long id = watchlistService.list(user.getId()).get(0).getId();

        assertThat(watchlistService.updateLabel(user.getId(), id, "Мои кроссовки")).isTrue();
        assertThat(watchlistService.get(user.getId(), id).orElseThrow().getLabel())
                .isEqualTo("Мои кроссовки");

        assertThat(watchlistService.delete(user.getId(), id)).isTrue();
        assertThat(watchlistService.list(user.getId())).isEmpty();
    }

    @Test
    void refreshSnapshotUpdatesPrice() {
        watchlistService.add(user.getId(), "https://www.vinted.com/items/3", "watch",
                item("https://www.vinted.com/items/3", "Puma", 50.0));
        Long id = watchlistService.list(user.getId()).get(0).getId();

        watchlistService.applySnapshot(user.getId(), id,
                item("https://www.vinted.com/items/3", "Puma", 42.0));

        assertThat(watchlistService.get(user.getId(), id).orElseThrow().getLastPrice())
                .isEqualTo(42.0);
    }

    @Test
    void usersCannotTouchEachOthersItems() {
        User other = userService.registerOrGet(2002L, "mallory");
        watchlistService.add(user.getId(), "https://www.vinted.com/items/9", "mine",
                item("https://www.vinted.com/items/9", "Mine", 10.0));
        Long id = watchlistService.list(user.getId()).get(0).getId();

        assertThat(watchlistService.delete(other.getId(), id)).isFalse();
        assertThat(watchlistService.updateLabel(other.getId(), id, "hacked")).isFalse();
        assertThat(watchlistService.get(other.getId(), id)).isEmpty();
    }
}
