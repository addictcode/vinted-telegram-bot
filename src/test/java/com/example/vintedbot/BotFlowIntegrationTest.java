package com.example.vintedbot;

import com.example.vintedbot.bot.VintedTelegramBot;
import com.example.vintedbot.config.*;
import com.example.vintedbot.dto.CatalogItemSummary;
import com.example.vintedbot.dto.VintedItem;
import com.example.vintedbot.repository.*;
import com.example.vintedbot.service.*;
import com.example.vintedbot.util.UserAgentRotator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.*;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the real bot end-to-end: synthetic Telegram updates in, replies and DB
 * effects out. The parser is stubbed (no network) and the Telegram send layer is
 * captured via the {@code dispatch} seam.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class BotFlowIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired UserSubscriptionRepository subscriptionRepository;
    @Autowired WatchedItemRepository watchedItemRepository;
    @Autowired ParsedItemRepository parsedItemRepository;
    @Autowired SearchSubscriptionRepository searchSubscriptionRepository;

    private static final long CHAT_ID = 555L;
    private CapturingBot bot;
    private SearchSubscriptionService searchSubs;
    private SearchMonitorService monitor;
    private MonitorProperties monitorProps;
    private StubParser stubParser;
    private StubApiClient stubApi;
    private final List<SendMessage> sent = new ArrayList<>();
    private final List<AnswerCallbackQuery> acks = new ArrayList<>();

    /** Bot subclass that captures outgoing messages instead of hitting Telegram. */
    static class CapturingBot extends VintedTelegramBot {
        final List<SendMessage> sent; final List<AnswerCallbackQuery> acks;
        final List<SendPhoto> photos = new ArrayList<>();
        int nextThreadId = 4242;
        Integer createdThreadFor;
        CapturingBot(BotProperties bp, UserService us, RateLimitService rl, VintedParserService ps,
                     VintedApiClient api, HistoryService hs, WatchlistService ws,
                     SearchSubscriptionService ss, SearchMonitorService ms, MessageFormatter mf,
                     List<SendMessage> sent, List<AnswerCallbackQuery> acks) {
            super(bp, us, rl, ps, api, hs, ws, ss, ms, mf);
            this.sent = sent; this.acks = acks;
        }
        /** When true, every send fails like a Telegram rate limit / outage. */
        boolean failSends = false;
        @Override protected void dispatch(SendMessage m) throws org.telegram.telegrambots.meta.exceptions.TelegramApiException {
            if (failSends) throw new org.telegram.telegrambots.meta.exceptions.TelegramApiException("429 Too Many Requests");
            sent.add(m);
        }
        @Override protected void dispatch(SendPhoto p) { photos.add(p); }
        @Override protected void dispatch(AnswerCallbackQuery a) { acks.add(a); }
        @Override protected Integer createForumTopic(Long chatId, String name) {
            createdThreadFor = nextThreadId;
            return nextThreadId;
        }
    }

    /** Parser stub: no network. URLs containing "fail" throw NOT_FOUND. */
    static class StubParser extends VintedParserService {
        /** Mutable "current catalog page" the stub returns (HTML fallback path). */
        List<String> catalogPage = new ArrayList<>();
        StubParser(VintedParserProperties p) {
            super(p, new WebDriverFactory(p, new WebDriverFactory.UserAgentRotatorHolder(new UserAgentRotator())),
                    new UserAgentRotator(), new ImageCacheService(p), new ObjectMapper());
        }
        @Override public VintedItem parseVintedUrl(String url) {
            if (url.contains("fail")) {
                throw new VintedParseException(VintedParseException.Reason.NOT_FOUND, "not found");
            }
            return VintedItem.builder().url(url).title("Item " + url.replaceAll("\\D+", ""))
                    .price(42.0).currency("EUR").brand("Nike").size("M")
                    .condition("Good").color("Black").imageUrls(List.of()).build();
        }
        @Override public List<String> fetchCatalogItemUrls(String catalogUrl) {
            return new ArrayList<>(catalogPage);
        }
    }

    /** API client stub: serves summaries built from a mutable URL list. */
    static class StubApiClient extends VintedApiClient {
        List<String> page = new ArrayList<>();
        boolean fail = false;
        boolean withPhoto = false;
        int calls = 0;
        /** Host that should simulate an anti-bot BLOCKED response; others unaffected. */
        String blockedHost = null;
        /** Per-host page override; hosts not present here fall back to {@link #page}. */
        java.util.Map<String, List<String>> pageByHost = new java.util.HashMap<>();
        StubApiClient(VintedParserProperties p, ObjectMapper m) {
            super(p, new UserAgentRotator(), m);
        }
        @Override public List<CatalogItemSummary> fetchCatalog(String catalogUrl, int perPage) {
            calls++;
            if (fail) throw new VintedParseException(VintedParseException.Reason.UNKNOWN, "api down");
            String host = java.net.URI.create(catalogUrl.trim()).getHost();
            if (blockedHost != null && blockedHost.equals(host)) {
                throw new VintedParseException(VintedParseException.Reason.BLOCKED, "blocked");
            }
            List<String> urls = pageByHost.getOrDefault(host, page);
            List<CatalogItemSummary> out = new ArrayList<>();
            for (String url : urls) {
                String id = url.replaceAll("\\D+", "");
                out.add(CatalogItemSummary.builder()
                        .id(id).url(url).title("Item " + id)
                        .price(42.0).currency("EUR").brand("Nike").size("M")
                        .condition("Good")
                        .photoUrl(withPhoto ? "https://images.vinted.net/" + id + ".jpg" : null)
                        .build());
            }
            return out;
        }
    }

    @BeforeEach
    void setUp() {
        VintedParserProperties pp = new VintedParserProperties();
        pp.setMinDelayMs(0); pp.setMaxDelayMs(0);
        RateLimitProperties rlp = new RateLimitProperties(); rlp.setFreeRequestsPerHour(100);
        BotProperties bp = new BotProperties(); bp.setToken("test"); bp.setUsername("testbot");
        MonitorProperties mp = new MonitorProperties();
        monitorProps = mp;
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

        UserService userService = new UserService(userRepository, subscriptionRepository);
        RateLimitService rateLimit = new RateLimitService(rlp);
        HistoryService history = new HistoryService(parsedItemRepository, mapper);
        WatchlistService watchlist = new WatchlistService(watchedItemRepository);
        MessageFormatter formatter = new MessageFormatter();
        stubParser = new StubParser(pp);
        stubApi = new StubApiClient(pp, mapper);
        searchSubs = new SearchSubscriptionService(searchSubscriptionRepository, mapper);
        monitor = new SearchMonitorService(searchSubs, stubApi, stubParser, history,
                userRepository, mp);

        bot = new CapturingBot(bp, userService, rateLimit, stubParser, stubApi,
                history, watchlist, searchSubs, monitor, formatter, sent, acks);
    }

    // --------- update builders ---------
    private Update groupCatalog(String body, boolean forum) {
        Chat chat = new Chat(); chat.setId(CHAT_ID); chat.setType("supergroup");
        chat.setTitle("Test Group"); chat.setIsForum(forum);
        User from = new User(); from.setId(999L); from.setUserName("tester"); from.setIsBot(false); from.setFirstName("T");
        Message m = new Message(); m.setChat(chat); m.setFrom(from); m.setText(body);
        Update u = new Update(); u.setMessage(m); return u;
    }

    private Update text(String body) {
        Chat chat = new Chat(); chat.setId(CHAT_ID); chat.setType("private");
        User from = new User(); from.setId(CHAT_ID); from.setUserName("tester"); from.setIsBot(false); from.setFirstName("T");
        Message m = new Message(); m.setChat(chat); m.setFrom(from); m.setText(body);
        Update u = new Update(); u.setMessage(m); return u;
    }
    private Update callback(String data) {
        Chat chat = new Chat(); chat.setId(CHAT_ID); chat.setType("private");
        User from = new User(); from.setId(CHAT_ID); from.setUserName("tester"); from.setIsBot(false); from.setFirstName("T");
        Message m = new Message(); m.setChat(chat); m.setFrom(from);
        CallbackQuery cq = new CallbackQuery(); cq.setId("cb"); cq.setData(data); cq.setFrom(from); cq.setMessage(m);
        Update u = new Update(); u.setCallbackQuery(cq); return u;
    }
    private String lastText() { return sent.get(sent.size() - 1).getText(); }

    // --------- tests ---------

    @Test
    void start_registersUserAndGreets() {
        bot.onUpdateReceived(text("/start"));
        assertThat(userRepository.findByChatId(CHAT_ID)).isPresent();
        assertThat(lastText()).contains("Привет");
    }

    @Test
    void singleLink_parsesSavesAndOffersSaveButton() {
        bot.onUpdateReceived(text("https://www.vinted.com/items/123-x"));
        // "parsing…" + card
        SendMessage card = sent.get(sent.size() - 1);
        assertThat(card.getText()).contains("Item 123").contains("€42");
        assertThat(card.getReplyMarkup()).isNotNull();               // ⭐ save button
        assertThat(parsedItemRepository.count()).isEqualTo(1);       // saved to history
    }

    @Test
    void multipleLinks_parseAllWithSummary() {
        bot.onUpdateReceived(text("https://www.vinted.com/items/1-a\nhttps://www.vinted.com/items/2-b"));
        assertThat(parsedItemRepository.count()).isEqualTo(2);
        assertThat(sent).anyMatch(m -> m.getText().contains("Готово"));
    }

    @Test
    void failedLink_reportsError() {
        bot.onUpdateReceived(text("https://www.vinted.com/items/999-fail"));
        assertThat(lastText()).contains("не найдено").containsIgnoringCase("не найдено");
        assertThat(parsedItemRepository.count()).isZero();
    }

    @Test
    void watchCommand_addsToWatchlist() {
        bot.onUpdateReceived(text("/watch https://www.vinted.com/items/7-w"));
        assertThat(watchedItemRepository.count()).isEqualTo(1);
        assertThat(sent).anyMatch(m -> m.getText().contains("Item 7"));
    }

    @Test
    void list_showsWatchlistWithButtons() {
        bot.onUpdateReceived(text("/watch https://www.vinted.com/items/8-w"));
        sent.clear();
        bot.onUpdateReceived(text("/list"));
        SendMessage listMsg = sent.get(sent.size() - 1);
        assertThat(listMsg.getText()).contains("Мой список");
        assertThat(listMsg.getReplyMarkup()).isNotNull();
    }

    @Test
    void saveCallback_addsParsedItemToWatchlist() {
        bot.onUpdateReceived(text("https://www.vinted.com/items/50-x"));
        Long parsedId = parsedItemRepository.findAll().get(0).getId();
        bot.onUpdateReceived(callback("save:" + parsedId));
        assertThat(watchedItemRepository.count()).isEqualTo(1);
        assertThat(acks).isNotEmpty();
    }

    @Test
    void editLabelFlow_updatesLabel() {
        bot.onUpdateReceived(text("/watch https://www.vinted.com/items/60-x"));
        Long watchId = watchedItemRepository.findAll().get(0).getId();
        bot.onUpdateReceived(callback("wl:edit:" + watchId));   // arms pending edit
        bot.onUpdateReceived(text("Моя ссылка"));               // supplies new label
        assertThat(watchedItemRepository.findById(watchId).orElseThrow().getLabel())
                .isEqualTo("Моя ссылка");
    }

    @Test
    void deleteCallback_removesWatchedItem() {
        bot.onUpdateReceived(text("/watch https://www.vinted.com/items/70-x"));
        Long watchId = watchedItemRepository.findAll().get(0).getId();
        bot.onUpdateReceived(callback("wl:del:" + watchId));
        assertThat(watchedItemRepository.count()).isZero();
    }

    // ------------------------------------------ catalog search subscriptions

    private static final String CATALOG_URL =
            "https://www.vinted.de/catalog?search_text=swear&order=newest_first&page=1&time=1783208420";
    private static final String CATALOG_URL_FR =
            "https://www.vinted.fr/catalog?search_text=swear&order=newest_first&page=1&time=1783208420";

    @Test
    void catalogLink_sendsThreeFreshestAndSubscribes() {
        stubApi.page = List.of(
                "https://www.vinted.de/items/101-a", "https://www.vinted.de/items/102-b",
                "https://www.vinted.de/items/103-c", "https://www.vinted.de/items/104-d");

        bot.onUpdateReceived(text(CATALOG_URL));

        // 3 freshest cards sent (in page order), subscription created.
        long cards = sent.stream().filter(m -> m.getText().contains("Item 10")).count();
        assertThat(cards).isEqualTo(3);
        assertThat(sent).anyMatch(m -> m.getText().contains("Подписка создана"));
        assertThat(searchSubscriptionRepository.count()).isEqualTo(1);
        // Volatile params (time/page) stripped from the stored URL.
        assertThat(searchSubscriptionRepository.findAll().get(0).getCatalogUrl())
                .doesNotContain("time=").doesNotContain("page=")
                .contains("search_text=swear").contains("order=newest_first");
        // All 4 page ids seeded as seen.
        assertThat(searchSubscriptionRepository.findAll().get(0).getSeenItemIds())
                .contains("101").contains("104");
    }

    @Test
    void duplicateCatalogLink_reportsAlreadySubscribed() {
        stubApi.page = List.of("https://www.vinted.de/items/1-a");
        bot.onUpdateReceived(text(CATALOG_URL));
        sent.clear();
        bot.onUpdateReceived(text(CATALOG_URL));
        assertThat(sent).anyMatch(m -> m.getText().contains("уже подписаны"));
        assertThat(searchSubscriptionRepository.count()).isEqualTo(1);
    }

    @Test
    void monitor_pushesOnlyNewListings() {
        stubApi.page = new ArrayList<>(List.of(
                "https://www.vinted.de/items/201-a", "https://www.vinted.de/items/202-b"));
        bot.onUpdateReceived(text(CATALOG_URL));   // subscribes, seeds 201+202
        sent.clear();

        // Nothing new yet → no pushes.
        assertThat(monitor.checkAll()).isZero();
        assertThat(sent).isEmpty();

        // Two new listings appear at the top of the search.
        stubApi.page = List.of(
                "https://www.vinted.de/items/204-new", "https://www.vinted.de/items/203-new",
                "https://www.vinted.de/items/201-a", "https://www.vinted.de/items/202-b");
        assertThat(monitor.checkAll()).isEqualTo(2);
        assertThat(sent).hasSize(2);
        assertThat(sent.get(0).getText()).contains("Item 204");
        assertThat(sent.get(1).getText()).contains("Item 203");

        // Same page again → already seen, no repeats.
        sent.clear();
        assertThat(monitor.checkAll()).isZero();
        assertThat(sent).isEmpty();
    }

    @Test
    void monitor_fallsBackToHtmlWhenApiFails() {
        stubApi.page = new ArrayList<>(List.of("https://www.vinted.de/items/501-a"));
        bot.onUpdateReceived(text(CATALOG_URL));   // subscribe via API, seeds 501
        sent.clear();

        // API goes down; HTML page shows one new listing.
        stubApi.fail = true;
        stubParser.catalogPage = List.of(
                "https://www.vinted.de/items/502-new", "https://www.vinted.de/items/501-a");
        assertThat(monitor.checkAll()).isEqualTo(1);
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).getText()).contains("Item 502");
    }

    @Test
    void monitor_hostBackoffOnlyAffectsThatHost() {
        stubApi.pageByHost.put("www.vinted.de", new ArrayList<>(List.of("https://www.vinted.de/items/301-a")));
        stubApi.pageByHost.put("www.vinted.fr", new ArrayList<>(List.of("https://www.vinted.fr/items/401-a")));

        bot.onUpdateReceived(text(CATALOG_URL));       // subscribes to .de, seeds 301
        bot.onUpdateReceived(text(CATALOG_URL_FR));    // subscribes to .fr, seeds 401
        sent.clear();

        // .de gets blocked; .fr still has a fresh listing and must still be delivered.
        stubApi.blockedHost = "www.vinted.de";
        stubApi.pageByHost.put("www.vinted.fr", List.of(
                "https://www.vinted.fr/items/402-new", "https://www.vinted.fr/items/401-a"));

        assertThat(monitor.checkAll()).isEqualTo(1);
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).getText()).contains("Item 402");
    }

    @Test
    void snipeMode_fastSubComesAroundWhileNormalOneWaits() {
        stubApi.page = new ArrayList<>(List.of("https://www.vinted.de/items/1001-a"));
        bot.onUpdateReceived(text(CATALOG_URL));       // normal
        bot.onUpdateReceived(text(CATALOG_URL_FR));    // to be switched to snipe mode
        Long frId = searchSubscriptionRepository.findAll().stream()
                .filter(s -> s.getCatalogUrl().contains("vinted.fr")).findFirst().orElseThrow().getId();

        bot.onUpdateReceived(callback("sub:fast:" + frId));
        assertThat(searchSubscriptionRepository.findById(frId).orElseThrow().isFast()).isTrue();

        monitorProps.setSnipeMinIntervalMs(0);         // no floor, and a huge request budget,
        monitorProps.setRequestsPerMinutePerIp(1_000_000); // so snipe subs are due again immediately
        stubApi.calls = 0;
        monitor.checkDue();                            // first tick: both are due
        assertThat(stubApi.calls).isEqualTo(2);

        stubApi.calls = 0;
        monitor.checkDue();                            // normal one waits its 20 s, snipe one goes again
        assertThat(stubApi.calls).isEqualTo(1);
    }

    @Test
    void monitor_listingsBeyondPerCycleCapAreDeferredNotLost() {
        monitorProps.setMaxNewPerCycle(1);
        stubApi.page = new ArrayList<>(List.of("https://www.vinted.de/items/2001-a"));
        bot.onUpdateReceived(text(CATALOG_URL));   // seeds 2001
        sent.clear();

        stubApi.page = List.of("https://www.vinted.de/items/2004-new", "https://www.vinted.de/items/2003-new",
                "https://www.vinted.de/items/2002-new", "https://www.vinted.de/items/2001-a");
        assertThat(monitor.checkAll()).isEqualTo(1);
        assertThat(monitor.checkAll()).isEqualTo(1);
        assertThat(monitor.checkAll()).isEqualTo(1);
        assertThat(monitor.checkAll()).isZero();   // all three delivered, nothing re-sent
        assertThat(sent).extracting(SendMessage::getText)
                .anySatisfy(t -> assertThat(t).contains("Item 2004"))
                .anySatisfy(t -> assertThat(t).contains("Item 2003"))
                .anySatisfy(t -> assertThat(t).contains("Item 2002"));
    }

    @Test
    void monitor_failedTelegramSendIsRetriedNextCycle() {
        stubApi.page = new ArrayList<>(List.of("https://www.vinted.de/items/3001-a"));
        bot.onUpdateReceived(text(CATALOG_URL));
        sent.clear();
        stubApi.page = List.of("https://www.vinted.de/items/3002-new", "https://www.vinted.de/items/3001-a");

        bot.failSends = true;
        assertThat(monitor.checkAll()).isZero();   // Telegram down: nothing delivered...
        bot.failSends = false;
        assertThat(monitor.checkAll()).isEqualTo(1);   // ...and it is not lost
        assertThat(sent.get(sent.size() - 1).getText()).contains("Item 3002");
    }

    @Test
    void monitor_givesUpOnListingAfterRepeatedDeliveryFailures() {
        stubApi.page = new ArrayList<>(List.of("https://www.vinted.de/items/5001-a"));
        bot.onUpdateReceived(text(CATALOG_URL));
        stubApi.page = List.of("https://www.vinted.de/items/5002-new", "https://www.vinted.de/items/5001-a");
        bot.failSends = true;
        for (int i = 0; i < 5; i++) monitor.checkAll();   // chat unreachable: retried, then dropped
        bot.failSends = false;
        sent.clear();
        assertThat(monitor.checkAll()).isZero();           // no endless retry once given up
    }

    @Test
    void monitor_alertsOwnerOnLongBlockAndOnRecovery() {
        stubApi.page = new ArrayList<>(List.of("https://www.vinted.de/items/4001-a"));
        bot.onUpdateReceived(text(CATALOG_URL));   // registers the owner (first user)
        sent.clear();
        monitorProps.setBlockAlertAfterMs(0);
        monitorProps.setBackoffMs(0);
        monitorProps.setIntervalMs(0);               // the sub is due again on every tick

        stubApi.blockedHost = "www.vinted.de";
        monitor.checkDue();                          // block recorded
        monitor.checkDue();                          // block older than threshold → alert
        assertThat(sent).anyMatch(m -> m.getText().contains("блокирует"));

        stubApi.blockedHost = null;
        monitor.checkDue();
        monitor.checkDue();
        assertThat(sent).anyMatch(m -> m.getText().contains("восстановлен"));
    }

    @Test
    void subCheckNowCallback_reportsNoNews() {
        stubApi.page = List.of("https://www.vinted.de/items/301-a");
        bot.onUpdateReceived(text(CATALOG_URL));
        Long subId = searchSubscriptionRepository.findAll().get(0).getId();
        sent.clear();
        bot.onUpdateReceived(callback("sub:chk:" + subId));
        assertThat(sent).anyMatch(m -> m.getText().contains("пока нет"));
    }

    @Test
    void subEditAndDelete_flow() {
        stubApi.page = List.of("https://www.vinted.de/items/401-a");
        bot.onUpdateReceived(text(CATALOG_URL));
        Long subId = searchSubscriptionRepository.findAll().get(0).getId();

        bot.onUpdateReceived(callback("sub:edit:" + subId));
        bot.onUpdateReceived(text("Свитера swear"));
        assertThat(searchSubscriptionRepository.findById(subId).orElseThrow().getLabel())
                .isEqualTo("Свитера swear");

        bot.onUpdateReceived(callback("sub:del:" + subId));
        assertThat(searchSubscriptionRepository.count()).isZero();
    }

    @Test
    void groupForum_createsTopicAndSubscribesWithThread() {
        stubApi.page = List.of("https://www.vinted.de/items/601-a", "https://www.vinted.de/items/602-b");
        bot.onUpdateReceived(groupCatalog(CATALOG_URL, true));

        // A forum topic was created and the subscription stored the thread id.
        assertThat(bot.createdThreadFor).isEqualTo(4242);
        var sub = searchSubscriptionRepository.findAll().get(0);
        assertThat(sub.getChatId()).isEqualTo(CHAT_ID);
        assertThat(sub.getMessageThreadId()).isEqualTo(4242L);
        assertThat(sub.getChatTitle()).isEqualTo("Test Group");
        // Preview messages were delivered into the created topic thread.
        assertThat(sent).anyMatch(m -> "4242".equals(m.getMessageThreadId() == null ? null
                : String.valueOf(m.getMessageThreadId())));

        // New listing pushed into the topic thread.
        sent.clear();
        stubApi.page = List.of("https://www.vinted.de/items/603-new",
                "https://www.vinted.de/items/601-a", "https://www.vinted.de/items/602-b");
        assertThat(monitor.checkAll()).isEqualTo(1);
        SendMessage push = sent.get(sent.size() - 1);
        assertThat(push.getMessageThreadId()).isEqualTo(4242);
        assertThat(push.getText()).contains("Item 603");
    }

    @Test
    void groupNonForum_subscribesToGroupChatNoThread() {
        stubApi.page = List.of("https://www.vinted.de/items/701-a");
        bot.onUpdateReceived(groupCatalog(CATALOG_URL, false));
        var sub = searchSubscriptionRepository.findAll().get(0);
        assertThat(sub.getChatId()).isEqualTo(CHAT_ID);
        assertThat(sub.getMessageThreadId()).isNull();
    }

    @Test
    void pauseResumeSubscription() {
        stubApi.page = List.of("https://www.vinted.de/items/801-a");
        bot.onUpdateReceived(text(CATALOG_URL));
        Long subId = searchSubscriptionRepository.findAll().get(0).getId();

        bot.onUpdateReceived(callback("sub:tgl:" + subId));   // pause
        assertThat(searchSubscriptionRepository.findById(subId).orElseThrow().isActive()).isFalse();

        // Paused subs are excluded from monitoring.
        stubApi.page = List.of("https://www.vinted.de/items/802-new",
                "https://www.vinted.de/items/801-a");
        assertThat(monitor.checkAll()).isZero();

        bot.onUpdateReceived(callback("sub:tgl:" + subId));   // resume
        assertThat(searchSubscriptionRepository.findById(subId).orElseThrow().isActive()).isTrue();
    }

    @Test
    void menuButtonCaptionRoutesToCommand() {
        bot.onUpdateReceived(text("/start"));
        sent.clear();
        bot.onUpdateReceived(text("🔔 Мои подписки"));   // reply-keyboard caption
        assertThat(sent).anyMatch(m -> m.getText().contains("нет подписок")
                || m.getText().contains("Подписки на поиск"));
    }

    @Test
    void addMenu_guidedSearchFlow() {
        stubApi.page = List.of("https://www.vinted.de/items/901-a");
        // Open the add menu, choose "track search", then supply the link.
        bot.onUpdateReceived(text("/add"));
        assertThat(sent).anyMatch(m -> m.getText().contains("Что добавить"));
        bot.onUpdateReceived(callback("add:search"));
        assertThat(sent).anyMatch(m -> m.getText().contains("ссылку на"));
        bot.onUpdateReceived(text(CATALOG_URL));
        // The link supplied after the prompt created a subscription.
        assertThat(searchSubscriptionRepository.count()).isEqualTo(1);
    }

    @Test
    void addMenu_rejectsNonLinkAfterPrompt() {
        bot.onUpdateReceived(callback("add:search"));
        sent.clear();
        bot.onUpdateReceived(text("привет"));
        assertThat(sent).anyMatch(m -> m.getText().contains("не похоже на ссылку"));
        assertThat(searchSubscriptionRepository.count()).isZero();
    }

    @Test
    void searchCommandWithLink_subscribesInGroupEvenWithoutBareMessages() {
        stubApi.page = List.of("https://www.vinted.de/items/905-a");
        // In groups only commands reach the bot; /search must work.
        bot.onUpdateReceived(groupCatalog("/search " + CATALOG_URL, false));
        assertThat(searchSubscriptionRepository.count()).isEqualTo(1);
        assertThat(searchSubscriptionRepository.findAll().get(0).getChatId()).isEqualTo(CHAT_ID);
    }

    @Test
    void listingCard_sentAsPhotoWithCaptionAndLinkButton() {
        stubApi.withPhoto = true;
        stubApi.page = List.of("https://www.vinted.de/items/950-a");
        bot.onUpdateReceived(text(CATALOG_URL));

        // At least one photo card was sent, with a compact caption + link button.
        assertThat(bot.photos).isNotEmpty();
        var photo = bot.photos.get(0);
        assertThat(photo.getCaption()).contains("Item 950").contains("€42");
        // Caption is compact: title + price only, no description/condition lines.
        assertThat(photo.getCaption()).doesNotContain("Состояние").doesNotContain("Описание");
        // A URL button linking to the listing is present.
        var kb = ((org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup)
                photo.getReplyMarkup()).getKeyboard();
        assertThat(kb.stream().flatMap(List::stream))
                .anyMatch(b -> b.getUrl() != null && b.getUrl().contains("/items/950"));
    }

    @Test
    void monitorPush_photoCardGoesToCorrectThread() {
        stubApi.withPhoto = true;
        stubApi.page = new ArrayList<>(List.of("https://www.vinted.de/items/960-a"));
        bot.onUpdateReceived(groupCatalog(CATALOG_URL, true));   // forum topic 4242
        bot.photos.clear();
        stubApi.page = List.of("https://www.vinted.de/items/961-new",
                "https://www.vinted.de/items/960-a");
        assertThat(monitor.checkAll()).isEqualTo(1);
        assertThat(bot.photos).hasSize(1);
        assertThat(bot.photos.get(0).getMessageThreadId()).isEqualTo(4242);
        assertThat(bot.photos.get(0).getCaption()).contains("Item 961");
    }

    @Test
    void labelDerivedFromSearchText() {
        assertThat(VintedTelegramBot.labelFromCatalogUrl(
                "https://www.vinted.de/catalog?search_text=nike+air&order=newest_first"))
                .isEqualTo("Поиск: nike air");
        assertThat(VintedTelegramBot.labelFromCatalogUrl(
                "https://www.vinted.de/catalog?brand_ids[]=53"))
                .isEqualTo("Vinted поиск");
    }
}
