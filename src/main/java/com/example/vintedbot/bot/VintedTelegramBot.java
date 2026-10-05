package com.example.vintedbot.bot;

import com.example.vintedbot.config.BotProperties;
import com.example.vintedbot.dto.CatalogItemSummary;
import com.example.vintedbot.dto.ListingCard;
import com.example.vintedbot.dto.SendTarget;
import com.example.vintedbot.dto.VintedItem;
import com.example.vintedbot.model.ParsedItem;
import com.example.vintedbot.model.SearchSubscription;
import com.example.vintedbot.model.SubscriptionLevel;
import com.example.vintedbot.model.User;
import com.example.vintedbot.model.WatchedItem;
import com.example.vintedbot.service.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.forum.CreateForumTopic;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.ChatMemberUpdated;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.forum.ForumTopic;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class VintedTelegramBot extends TelegramLongPollingBot {

    private static final int MAX_LINKS_PER_MESSAGE = 10;
    private static final int MAX_CATALOGS_PER_MESSAGE = 3;
    private static final int CATALOG_PREVIEW_COUNT = 3;
    private static final int WATCH_PAGE_SIZE = 5;
    private static final Pattern URL_PATTERN =
            Pattern.compile("https?://(?:www\\.)?vinted\\.[a-z.]+/\\S+", Pattern.CASE_INSENSITIVE);

    // Reply-keyboard button captions (private chats) mapped to commands.
    private static final String BTN_ADD = "➕ Добавить";
    private static final String BTN_SUBS = "🔔 Мои подписки";
    private static final String BTN_LIST = "⭐ Список товаров";
    private static final String BTN_HISTORY = "📜 История";
    private static final String BTN_STATS = "📊 Статистика";
    private static final String BTN_HELP = "ℹ️ Помощь";

    private final BotProperties botProperties;
    private final UserService userService;
    private final RateLimitService rateLimitService;
    private final VintedParserService parserService;
    private final VintedApiClient apiClient;
    private final HistoryService historyService;
    private final WatchlistService watchlistService;
    private final SearchSubscriptionService subscriptionService;
    private final SearchMonitorService monitorService;
    private final MessageFormatter formatter;

    private final ConcurrentHashMap<Long, Pending> pending = new ConcurrentHashMap<>();

    private record Pending(Type type, Long targetId) {
        enum Type { EDIT_WATCH_LABEL, EDIT_SUB_LABEL, ADD_SEARCH, ADD_ITEM }
    }

    /** Context of an incoming message: where and from whom. */
    private record Ctx(Long chatId, Long threadId, boolean forum, boolean group,
                       String chatTitle, String username, User user) {
        SendTarget reply() {
            return new SendTarget(chatId, threadId);
        }
    }

    public VintedTelegramBot(BotProperties botProperties,
                             UserService userService,
                             RateLimitService rateLimitService,
                             VintedParserService parserService,
                             VintedApiClient apiClient,
                             HistoryService historyService,
                             WatchlistService watchlistService,
                             SearchSubscriptionService subscriptionService,
                             SearchMonitorService monitorService,
                             MessageFormatter formatter) {
        super(botProperties.getToken());
        this.botProperties = botProperties;
        this.userService = userService;
        this.rateLimitService = rateLimitService;
        this.parserService = parserService;
        this.apiClient = apiClient;
        this.historyService = historyService;
        this.watchlistService = watchlistService;
        this.subscriptionService = subscriptionService;
        this.monitorService = monitorService;
        this.formatter = formatter;
        monitorService.setNotifier(this::pushCard);
        monitorService.setAlerter((chatId, html) -> trySend(SendTarget.chat(chatId), html, null));
    }

    /** Delivery channel the monitor uses to push a listing; false = not delivered, retry later. */
    public boolean pushCard(SendTarget target, String header, ListingCard card) {
        return sendListingCard(target, card, header, null);
    }

    @Override
    public String getBotUsername() {
        return botProperties.getUsername();
    }

    /** Registers the "/" command menu shown in the Telegram UI. */
    public void registerCommands() {
        List<BotCommand> cmds = List.of(
                new BotCommand("start", "Запуск и меню"),
                new BotCommand("add", "➕ Добавить поиск или объявление"),
                new BotCommand("subs", "🔔 Мои подписки на поиск"),
                new BotCommand("list", "⭐ Сохранённые товары"),
                new BotCommand("history", "📜 История парсинга"),
                new BotCommand("stats", "📊 Статистика"),
                new BotCommand("setup", "👥 Настройка в группе"),
                new BotCommand("clear_history", "🗑 Очистить историю"),
                new BotCommand("help", "ℹ️ Справка"));
        try {
            execute(SetMyCommands.builder().commands(cmds).build());
            log.info("Bot command menu registered ({} commands)", cmds.size());
        } catch (Exception e) {
            log.warn("Failed to set command menu: {}", e.getMessage());
        }
    }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasMyChatMember()) {
                handleMyChatMember(update.getMyChatMember());
                return;
            }
            if (update.hasCallbackQuery()) {
                handleCallback(update.getCallbackQuery());
                return;
            }
            if (update.hasMessage() && update.getMessage().hasText()) {
                handleText(buildCtx(update.getMessage()), update.getMessage().getText().trim());
            }
        } catch (Exception e) {
            log.error("Unhandled error processing update", e);
        }
    }

    /** Posts a setup guide when the bot is added to a group. */
    private void handleMyChatMember(ChatMemberUpdated upd) {
        Chat chat = upd.getChat();
        String type = chat.getType() == null ? "" : chat.getType();
        boolean group = "group".equals(type) || "supergroup".equals(type);
        if (!group || upd.getNewChatMember() == null) return;
        String status = upd.getNewChatMember().getStatus();
        if ("member".equals(status) || "administrator".equals(status)) {
            send(chat.getId(), groupSetupMessage(chat.getId().toString().startsWith("-100")
                    && Boolean.TRUE.equals(chat.getIsForum())));
        }
    }

    private Ctx buildCtx(Message m) {
        Chat chat = m.getChat();
        String type = chat.getType() == null ? "private" : chat.getType();
        boolean group = "group".equals(type) || "supergroup".equals(type);
        String username = m.getFrom() != null ? m.getFrom().getUserName() : null;
        User user = userService.registerOrGet(chat.getId(), username);
        Long threadId = m.getMessageThreadId() == null ? null : m.getMessageThreadId().longValue();
        return new Ctx(chat.getId(), threadId, Boolean.TRUE.equals(chat.getIsForum()),
                group, chat.getTitle(), username, user);
    }

    // ------------------------------------------------------------- text flow

    private void handleText(Ctx ctx, String rawText) {
        Long chatId = ctx.chatId();
        User user = ctx.user();
        String text = mapMenuButton(rawText);

        Pending p = pending.get(chatId);
        if (p != null && !text.startsWith("/")) {
            pending.remove(chatId);
            consumePending(ctx, p, text);
            return;
        }
        if (text.startsWith("/")) {
            pending.remove(chatId);
        }

        if (text.startsWith("/start")) {
            sendMenu(ctx, welcomeMessage(ctx));
        } else if (text.startsWith("/help")) {
            sendMenu(ctx, helpMessage());
        } else if (text.startsWith("/add")) {
            sendAddMenu(chatId);
        } else if (text.startsWith("/setup")) {
            send(chatId, groupSetupMessage(ctx.forum()));
        } else if (text.startsWith("/search") || text.startsWith("/track")) {
            String rest = text.replaceFirst("^/\\w+(@\\S+)?", "").trim();
            if (rest.isBlank()) {
                pending.put(chatId, new Pending(Pending.Type.ADD_SEARCH, null));
                send(chatId, addSearchPrompt());
            } else {
                handleLinks(ctx, rest, false);
            }
        } else if (text.startsWith("/parse_link")) {
            handleLinks(ctx, stripCmd(text, "/parse_link"), false);
        } else if (text.startsWith("/watch")) {
            handleLinks(ctx, stripCmd(text, "/watch"), true);
        } else if (text.startsWith("/subs")) {
            sendSubsPage(chatId, user, 0);
        } else if (text.startsWith("/list")) {
            sendWatchlistPage(chatId, user, 0);
        } else if (text.startsWith("/history")) {
            sendHistoryPage(chatId, user, 0);
        } else if (text.startsWith("/clear_history")) {
            long removed = historyService.clear(user.getId());
            send(chatId, "🗑️ История очищена. Удалено записей: " + removed);
        } else if (text.startsWith("/stats")) {
            handleStats(chatId, user);
        } else if (URL_PATTERN.matcher(text).find()) {
            handleLinks(ctx, text, false);
        } else if (text.startsWith("/")) {
            send(chatId, "❓ Неизвестная команда. Наберите /help.");
        } else if (!ctx.group()) {
            send(ctx.reply(), "Пришлите ссылку на объявление или на поиск Vinted, "
                    + "либо нажмите «➕ Добавить» в меню ниже. /help", null);
        }
        // In groups: ignore free-form non-link chatter.
    }

    /** Maps reply-keyboard captions to their command equivalents. */
    private String mapMenuButton(String text) {
        return switch (text) {
            case BTN_ADD -> "/add";
            case BTN_SUBS -> "/subs";
            case BTN_LIST -> "/list";
            case BTN_HISTORY -> "/history";
            case BTN_STATS -> "/stats";
            case BTN_HELP -> "/help";
            default -> text;
        };
    }

    /** Strips a leading command token (and optional @botname) from the text. */
    private String stripCmd(String text, String cmd) {
        String rest = text.substring(cmd.length());
        // handle "/watch@botname args"
        if (rest.startsWith("@")) {
            int sp = rest.indexOf(' ');
            rest = sp < 0 ? "" : rest.substring(sp);
        }
        return rest;
    }

    private void consumePending(Ctx ctx, Pending p, String text) {
        Long chatId = ctx.chatId();
        User user = ctx.user();
        switch (p.type()) {
            case EDIT_WATCH_LABEL -> {
                boolean ok = watchlistService.updateLabel(user.getId(), p.targetId(), text.trim());
                send(chatId, ok ? "✅ Название обновлено." : "⚠️ Запись не найдена.");
                if (ok) sendWatchlistPage(chatId, user, 0);
            }
            case EDIT_SUB_LABEL -> {
                boolean ok = subscriptionService.updateLabel(user.getId(), p.targetId(), text.trim());
                send(chatId, ok ? "✅ Название подписки обновлено." : "⚠️ Подписка не найдена.");
                if (ok) sendSubsPage(chatId, user, 0);
            }
            case ADD_SEARCH -> {
                if (!URL_PATTERN.matcher(text).find()) {
                    send(chatId, "🤔 Это не похоже на ссылку. Пришлите ссылку на поиск Vinted "
                            + "(<code>…/catalog?...</code>) или нажмите /add ещё раз.");
                } else {
                    handleLinks(ctx, text, false);
                }
            }
            case ADD_ITEM -> {
                if (!URL_PATTERN.matcher(text).find()) {
                    send(chatId, "🤔 Это не похоже на ссылку объявления. Пришлите "
                            + "<code>…/items/…</code> или нажмите /add ещё раз.");
                } else {
                    handleLinks(ctx, text, false);
                }
            }
        }
    }

    // ------------------------------------------------------------- add menu

    private void sendAddMenu(Long chatId) {
        InlineKeyboardMarkup m = new InlineKeyboardMarkup();
        m.setKeyboard(List.of(
                List.of(button("🔔 Отслеживать поиск", "add:search")),
                List.of(button("👗 Разобрать объявление", "add:item")),
                List.of(button("❓ Где взять ссылку", "add:help"))));
        send(chatId, """
                <b>Что добавить?</b>

                🔔 <b>Отслеживать поиск</b> — пришлёте ссылку на поиск Vinted
                с фильтрами, я покажу свежее и буду присылать новое.
                👗 <b>Разобрать объявление</b> — карточка по ссылке на товар.""",
                m);
    }

    private String addSearchPrompt() {
        return """
                🔔 Ок! Пришлите ссылку на <b>поиск Vinted</b>.

                Как получить: откройте Vinted → задайте фильтры (поиск, размеры,
                бренды) → сортировка «Сначала новые» → скопируйте ссылку из
                адресной строки. Она выглядит так:
                <code>https://www.vinted.de/catalog?search_text=nike&amp;order=newest_first</code>""";
    }

    private String addItemPrompt() {
        return "👗 Пришлите ссылку на объявление вида <code>…/items/123…</code> "
                + "(можно несколько сразу, по одной в строке).";
    }

    // ---------------------------------------------------- parsing (batch)

    private void handleLinks(Ctx ctx, String payload, boolean addToWatch) {
        Long chatId = ctx.chatId();
        User user = ctx.user();
        List<String> all = extractUrls(payload);
        if (all.isEmpty()) {
            send(ctx.reply(), "⚠️ Не нашёл ссылку Vinted.\n"
                    + "Пример объявления: <code>https://www.vinted.com/items/123456789</code>\n"
                    + "Пример поиска: <code>https://www.vinted.de/catalog?search_text=…</code>", null);
            return;
        }

        List<String> catalogUrls = all.stream().filter(parserService::isCatalogUrl).toList();
        List<String> urls = all.stream().filter(u -> !parserService.isCatalogUrl(u)).toList();

        int catalogHandled = 0;
        for (String c : catalogUrls) {
            if (catalogHandled++ >= MAX_CATALOGS_PER_MESSAGE) {
                send(chatId, "⚠️ Не больше " + MAX_CATALOGS_PER_MESSAGE + " ссылок на поиск за раз.");
                break;
            }
            handleCatalog(ctx, c);
        }
        if (urls.isEmpty()) return;

        if (urls.size() > MAX_LINKS_PER_MESSAGE) {
            send(chatId, "⚠️ Слишком много ссылок за раз (макс " + MAX_LINKS_PER_MESSAGE
                    + "). Обрабатываю первые " + MAX_LINKS_PER_MESSAGE + ".");
            urls = urls.subList(0, MAX_LINKS_PER_MESSAGE);
        }

        SubscriptionLevel level = userService.subscriptionLevel(user.getId());
        SendTarget reply = ctx.reply();
        if (urls.size() > 1) {
            send(reply, "🔎 Нашёл ссылок: " + urls.size() + ". Обрабатываю по очереди…", null);
        } else {
            send(reply, "🔎 Парсю объявление, это займёт несколько секунд…", null);
        }

        int ok = 0, failed = 0, added = 0;
        for (String url : urls) {
            if (!rateLimitService.tryAcquire(user.getId(), level)) {
                send(reply, "🚦 Достигнут лимит запросов (" + level + "). "
                        + "Остальные ссылки пропущены. Попробуйте позже.", null);
                break;
            }
            try {
                VintedItem item = parserService.parseVintedUrl(url);
                ParsedItem saved = historyService.save(user.getId(), item);
                if (addToWatch) {
                    WatchlistService.AddResult r =
                            watchlistService.add(user.getId(), item.getUrl(), null, item);
                    if (r == WatchlistService.AddResult.ADDED) added++;
                    // Already saved to the watchlist → link button only.
                    sendListingCard(reply, ListingCard.of(item), null, null);
                } else {
                    // Link button + "⭐ В список" save button.
                    sendListingCard(reply, ListingCard.of(item), null, saved.getId());
                }
                ok++;
            } catch (VintedParseException e) {
                failed++;
                send(reply, userFacingError(e) + "\n<code>" + esc(url) + "</code>", null);
                log.warn("Parse failed for {} ({}): {}", url, e.getReason(), e.getMessage());
            } catch (Exception e) {
                failed++;
                send(reply, "❌ Не удалось обработать ссылку:\n<code>" + esc(url) + "</code>", null);
                log.error("Unexpected parse error for {}", url, e);
            }
        }

        if (urls.size() > 1) {
            StringBuilder sb = new StringBuilder("📊 Готово: ✅ " + ok + "  ❌ " + failed);
            if (addToWatch) sb.append("  ⭐ добавлено в список: ").append(added);
            send(reply, sb.toString(), null);
        }
    }

    // ------------------------------------------------- catalog subscriptions

    /**
     * Search/filter link flow. In a forum group we create a dedicated topic for
     * the search and deliver everything there; otherwise we deliver into the
     * current chat. Shows the freshest listings and subscribes the monitor.
     */
    private void handleCatalog(Ctx ctx, String rawUrl) {
        User user = ctx.user();
        SubscriptionLevel level = userService.subscriptionLevel(user.getId());
        if (!rateLimitService.tryAcquire(user.getId(), level)) {
            send(ctx.reply(), "🚦 Достигнут лимит запросов. Попробуйте позже.", null);
            return;
        }

        String url = VintedParserService.normalizeCatalogUrl(rawUrl);
        String label = labelFromCatalogUrl(url);

        // Decide the delivery target: a fresh forum topic, or the current chat.
        SendTarget target = ctx.reply();
        Long topicThreadId = null;
        if (ctx.group() && ctx.forum()) {
            Integer newThread = createForumTopic(ctx.chatId(), topicName(label));
            if (newThread != null) {
                topicThreadId = newThread.longValue();
                target = SendTarget.topic(ctx.chatId(), topicThreadId);
                send(ctx.reply(), "🧵 Создал тему «" + esc(topicName(label))
                        + "» — свежие и новые объявления по этому фильтру будут там.", null);
            } else {
                send(ctx.reply(), "⚠️ Не смог создать тему (нужны права «Управление темами»). "
                        + "Пишу сюда.", null);
            }
        } else if (ctx.group()) {
            send(ctx.reply(), "ℹ️ Совет: включите в группе «Темы» (Topics) и дайте мне право "
                    + "их создавать — тогда под каждый поиск я заведу отдельную подтему.", null);
        }

        send(target, "🔎 Читаю поиск «" + esc(label) + "»…", null);

        List<String> seedIds = new ArrayList<>();
        boolean previewSent = false;
        try {
            List<CatalogItemSummary> summaries = apiClient.fetchCatalog(url, 24);
            for (CatalogItemSummary s : summaries) {
                if (s.getId() != null) seedIds.add(s.getId());
            }
            if (summaries.isEmpty()) {
                send(target, "😕 Сейчас по фильтру ничего нет — подпишу, пришлю как появится.", null);
            } else {
                int n = Math.min(CATALOG_PREVIEW_COUNT, summaries.size());
                send(target, "🆕 Свежие объявления (" + n + " из " + summaries.size() + "):", null);
                for (int i = 0; i < n; i++) {
                    sendListingCard(target, ListingCard.of(summaries.get(i)), null, null);
                }
            }
            previewSent = true;
        } catch (Exception apiFail) {
            log.info("Catalog API preview failed for {} ({}), falling back to HTML",
                    url, apiFail.getMessage());
        }

        if (!previewSent && !previewViaHtml(target, user, url, seedIds)) {
            return;
        }

        SearchSubscriptionService.NewSubscription ns = new SearchSubscriptionService.NewSubscription(
                user.getId(), url, label, ctx.chatId(), topicThreadId,
                ctx.group() ? ctx.chatTitle() : null, seedIds);
        switch (subscriptionService.create(ns)) {
            case ADDED -> send(target, "🔔 <b>Подписка создана!</b> Новые объявления по этому "
                    + "фильтру буду присылать автоматически (обычно в течение секунд).\n"
                    + "Управление: /subs", null);
            case ALREADY_EXISTS -> send(target, "ℹ️ Вы уже подписаны на этот фильтр. /subs", null);
        }
    }

    /** HTML fallback preview; returns false if the page couldn't be read. */
    private boolean previewViaHtml(SendTarget target, User user, String url, List<String> seedIds) {
        List<String> itemUrls;
        try {
            itemUrls = parserService.fetchCatalogItemUrls(url);
        } catch (VintedParseException e) {
            send(target, userFacingError(e), null);
            return false;
        } catch (Exception e) {
            send(target, "❌ Не удалось прочитать страницу поиска. Попробуйте позже.", null);
            log.error("Unexpected catalog error for {}", url, e);
            return false;
        }
        for (String u : itemUrls) {
            String id = VintedParserService.extractItemId(u);
            if (id != null) seedIds.add(id);
        }
        if (itemUrls.isEmpty()) {
            send(target, "😕 Сейчас по фильтру ничего нет — подпишу, пришлю как появится.", null);
        } else {
            int n = Math.min(CATALOG_PREVIEW_COUNT, itemUrls.size());
            send(target, "🆕 Свежие объявления (" + n + " из " + itemUrls.size() + "):", null);
            for (int i = 0; i < n; i++) {
                try {
                    VintedItem item = parserService.parseVintedUrl(itemUrls.get(i));
                    historyService.save(user.getId(), item);
                    sendListingCard(target, ListingCard.of(item), null, null);
                } catch (Exception e) {
                    log.warn("Catalog preview parse failed for {}: {}", itemUrls.get(i), e.getMessage());
                }
            }
        }
        return true;
    }

    /** Human label from the search_text query param, e.g. "Поиск: swear". */
    public static String labelFromCatalogUrl(String url) {
        Matcher m = Pattern.compile("[?&]search_text=([^&]*)").matcher(url);
        if (m.find()) {
            String s = java.net.URLDecoder.decode(
                    m.group(1).replace("+", " "), java.nio.charset.StandardCharsets.UTF_8).trim();
            if (!s.isEmpty()) return "Поиск: " + s;
        }
        return "Vinted поиск";
    }

    private String topicName(String label) {
        String n = "Vinted · " + label;
        return n.length() > 120 ? n.substring(0, 120) : n;
    }

    /** Creates a forum topic; returns its thread id, or null on failure. */
    protected Integer createForumTopic(Long chatId, String name) {
        try {
            ForumTopic topic = execute(CreateForumTopic.builder()
                    .chatId(chatId.toString()).name(name).build());
            return topic.getMessageThreadId();
        } catch (Exception e) {
            log.warn("createForumTopic failed for chat {}: {}", chatId, e.getMessage());
            return null;
        }
    }

    private void sendSubsPage(Long chatId, User user, int page) {
        List<SearchSubscription> all = subscriptionService.list(user.getId());
        if (all.isEmpty()) {
            send(chatId, "🔔 У вас нет подписок на поиск.\n"
                    + "Пришлите ссылку на поиск Vinted с фильтрами "
                    + "(<code>…/catalog?search_text=…&amp;order=newest_first</code>) — "
                    + "я покажу свежие и подпишу на новые.");
            return;
        }
        int totalPages = (int) Math.ceil(all.size() / (double) WATCH_PAGE_SIZE);
        page = Math.max(0, Math.min(page, totalPages - 1));
        int from = page * WATCH_PAGE_SIZE;
        int to = Math.min(from + WATCH_PAGE_SIZE, all.size());

        StringBuilder sb = new StringBuilder("🔔 <b>Подписки на поиск</b> (")
                .append(all.size()).append(" шт., стр. ")
                .append(page + 1).append("/").append(totalPages).append(")\n\n");

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (int i = from; i < to; i++) {
            SearchSubscription s = all.get(i);
            sb.append(formatter.formatSubEntry(s, i + 1)).append("\n\n");
            String toggle = s.isActive() ? "⏸" : "▶️";
            rows.add(List.of(
                    button("🔄 #" + (i + 1), "sub:chk:" + s.getId()),
                    button(s.isFast() ? "⚡" : "🐢", "sub:fast:" + s.getId()),
                    button(toggle, "sub:tgl:" + s.getId()),
                    button("✏️", "sub:edit:" + s.getId()),
                    button("🗑", "sub:del:" + s.getId())
            ));
        }
        List<InlineKeyboardButton> nav = new ArrayList<>();
        if (page > 0) nav.add(button("⬅️", "sub:list:" + (page - 1)));
        if (page < totalPages - 1) nav.add(button("➡️", "sub:list:" + (page + 1)));
        if (!nav.isEmpty()) rows.add(nav);

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        send(chatId, sb.toString(), markup);
    }

    // -------------------------------------------------------------- watchlist

    private void sendWatchlistPage(Long chatId, User user, int page) {
        List<WatchedItem> all = watchlistService.list(user.getId());
        if (all.isEmpty()) {
            send(chatId, "⭐ Ваш список пуст.\n"
                    + "Добавляйте ссылки командой <code>/watch &lt;url&gt;</code> "
                    + "или кнопкой «В список» под карточкой товара.");
            return;
        }
        int totalPages = (int) Math.ceil(all.size() / (double) WATCH_PAGE_SIZE);
        page = Math.max(0, Math.min(page, totalPages - 1));
        int from = page * WATCH_PAGE_SIZE;
        int to = Math.min(from + WATCH_PAGE_SIZE, all.size());

        StringBuilder sb = new StringBuilder("⭐ <b>Мой список</b> (")
                .append(all.size()).append(" шт., стр. ")
                .append(page + 1).append("/").append(totalPages).append(")\n\n");

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (int i = from; i < to; i++) {
            WatchedItem w = all.get(i);
            sb.append(formatter.formatWatchEntry(w, i + 1)).append("\n\n");
            rows.add(List.of(
                    button("🔄 #" + (i + 1), "wl:ref:" + w.getId()),
                    button("✏️", "wl:edit:" + w.getId()),
                    button("🗑", "wl:del:" + w.getId())
            ));
        }
        List<InlineKeyboardButton> nav = new ArrayList<>();
        if (page > 0) nav.add(button("⬅️", "wl:list:" + (page - 1)));
        if (page < totalPages - 1) nav.add(button("➡️", "wl:list:" + (page + 1)));
        if (!nav.isEmpty()) rows.add(nav);

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        send(chatId, sb.toString(), markup);
    }

    // -------------------------------------------------------------- history

    private void sendHistoryPage(Long chatId, User user, int page) {
        Page<ParsedItem> pageData = historyService.history(user.getId(), page);
        if (pageData.getTotalElements() == 0) {
            send(chatId, "📭 История пуста. Пришлите ссылку на Vinted, чтобы начать.");
            return;
        }
        StringBuilder sb = new StringBuilder("📜 <b>История</b> (стр. ")
                .append(page + 1).append("/").append(pageData.getTotalPages()).append(")\n\n");
        int idx = page * HistoryService.PAGE_SIZE + 1;
        for (ParsedItem item : pageData.getContent()) {
            sb.append(formatter.formatHistoryEntry(item, idx++)).append("\n\n");
        }
        List<InlineKeyboardButton> row = new ArrayList<>();
        if (page > 0) row.add(button("⬅️ Назад", "hist:" + (page - 1)));
        if (page < pageData.getTotalPages() - 1) row.add(button("Вперёд ➡️", "hist:" + (page + 1)));
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(row.isEmpty() ? List.of() : List.of(row));
        send(chatId, sb.toString(), markup);
    }

    private void handleStats(Long chatId, User user) {
        long total = historyService.totalCount(user.getId());
        long watched = watchlistService.count(user.getId());
        long subs = subscriptionService.count(user.getId());
        OffsetDateTime earliest = historyService.earliest(user.getId());
        StringBuilder sb = new StringBuilder("📊 <b>Статистика</b>\n\n");
        sb.append("Всего спарсено: <b>").append(total).append("</b>\n");
        sb.append("Подписок на поиск: <b>").append(subs).append("</b>\n");
        sb.append("В списке товаров: <b>").append(watched).append("</b>\n");
        if (earliest != null) {
            long days = Math.max(1, ChronoUnit.DAYS.between(earliest.toInstant(), OffsetDateTime.now().toInstant()));
            sb.append("За период: <b>").append(days).append("</b> дн.\n");
            sb.append("В среднем: <b>").append(String.format("%.1f", (double) total / days)).append("</b>/день");
        } else {
            sb.append("\nВы ещё ничего не парсили.");
        }
        send(chatId, sb.toString());
    }

    // ------------------------------------------------------------- callbacks

    private void handleCallback(CallbackQuery cb) {
        String data = cb.getData();
        Long chatId = cb.getMessage().getChatId();
        User user = userService.registerOrGet(chatId, cb.getFrom().getUserName());
        if (data == null) { ack(cb, null); return; }

        try {
            if (data.equals("add:search")) {
                pending.put(chatId, new Pending(Pending.Type.ADD_SEARCH, null));
                send(chatId, addSearchPrompt());
                ack(cb, null);
            } else if (data.equals("add:item")) {
                pending.put(chatId, new Pending(Pending.Type.ADD_ITEM, null));
                send(chatId, addItemPrompt());
                ack(cb, null);
            } else if (data.equals("add:help")) {
                send(chatId, whereToGetLinkMessage());
                ack(cb, null);
            } else if (data.startsWith("hist:")) {
                sendHistoryPage(chatId, user, Integer.parseInt(data.substring(5)));
                ack(cb, null);
            } else if (data.startsWith("wl:list:")) {
                sendWatchlistPage(chatId, user, Integer.parseInt(data.substring(8)));
                ack(cb, null);
            } else if (data.startsWith("save:")) {
                handleSave(user, Long.parseLong(data.substring(5)), cb);
            } else if (data.startsWith("wl:ref:")) {
                handleRefresh(chatId, user, Long.parseLong(data.substring(7)), cb);
            } else if (data.startsWith("wl:edit:")) {
                pending.put(chatId, new Pending(Pending.Type.EDIT_WATCH_LABEL, Long.parseLong(data.substring(8))));
                send(chatId, "✏️ Отправьте новое название для этой записи (или /list чтобы отменить).");
                ack(cb, null);
            } else if (data.startsWith("wl:del:")) {
                boolean ok = watchlistService.delete(user.getId(), Long.parseLong(data.substring(7)));
                ack(cb, ok ? "Удалено" : "Не найдено");
                sendWatchlistPage(chatId, user, 0);
            } else if (data.startsWith("sub:list:")) {
                sendSubsPage(chatId, user, Integer.parseInt(data.substring(9)));
                ack(cb, null);
            } else if (data.startsWith("sub:chk:")) {
                handleSubCheckNow(chatId, user, Long.parseLong(data.substring(8)), cb);
            } else if (data.startsWith("sub:tgl:")) {
                Boolean active = subscriptionService.toggleActive(user.getId(), Long.parseLong(data.substring(8)));
                ack(cb, active == null ? "Не найдено" : (active ? "▶️ Возобновлено" : "⏸ На паузе"));
                sendSubsPage(chatId, user, 0);
            } else if (data.startsWith("sub:fast:")) {
                Boolean fast = subscriptionService.toggleFast(user.getId(), Long.parseLong(data.substring(9)));
                ack(cb, fast == null ? "Не найдено"
                        : (fast ? "⚡ Снайпер-режим: проверка каждые несколько секунд" : "🐢 Обычный режим"));
                sendSubsPage(chatId, user, 0);
            } else if (data.startsWith("sub:edit:")) {
                pending.put(chatId, new Pending(Pending.Type.EDIT_SUB_LABEL, Long.parseLong(data.substring(9))));
                send(chatId, "✏️ Отправьте новое название для подписки (или /subs чтобы отменить).");
                ack(cb, null);
            } else if (data.startsWith("sub:del:")) {
                boolean ok = subscriptionService.delete(user.getId(), Long.parseLong(data.substring(8)));
                ack(cb, ok ? "Подписка удалена" : "Не найдено");
                sendSubsPage(chatId, user, 0);
            } else {
                ack(cb, null);
            }
        } catch (Exception e) {
            log.error("Callback handling failed for data={}", data, e);
            ack(cb, "Ошибка");
        }
    }

    private void handleSave(User user, Long parsedItemId, CallbackQuery cb) {
        historyService.get(user.getId(), parsedItemId).ifPresentOrElse(pi -> {
            VintedItem snap = VintedItem.builder()
                    .url(pi.getVintedUrl()).title(pi.getTitle())
                    .price(pi.getPrice()).currency(pi.getCurrency()).build();
            WatchlistService.AddResult r =
                    watchlistService.add(user.getId(), pi.getVintedUrl(), null, snap);
            ack(cb, switch (r) {
                case ADDED -> "⭐ Добавлено в список";
                case ALREADY_EXISTS -> "Уже в списке";
                case LIMIT_REACHED -> "Список переполнен";
            });
        }, () -> ack(cb, "Запись не найдена"));
    }

    private void handleSubCheckNow(Long chatId, User user, Long subId, CallbackQuery cb) {
        var opt = subscriptionService.get(user.getId(), subId);
        if (opt.isEmpty()) { ack(cb, "Не найдено"); return; }
        SubscriptionLevel level = userService.subscriptionLevel(user.getId());
        if (!rateLimitService.tryAcquire(user.getId(), level)) {
            ack(cb, "Лимит запросов исчерпан");
            return;
        }
        ack(cb, "Проверяю…");
        try {
            int sent = monitorService.checkOne(opt.get());
            if (sent == 0) {
                send(chatId, "✅ Новых объявлений по этому фильтру пока нет.");
            }
        } catch (Exception e) {
            send(chatId, "❌ Не удалось проверить подписку. Попробуйте позже.");
            log.error("Manual check failed for sub {}", subId, e);
        }
    }

    private void handleRefresh(Long chatId, User user, Long watchId, CallbackQuery cb) {
        var opt = watchlistService.get(user.getId(), watchId);
        if (opt.isEmpty()) { ack(cb, "Не найдено"); return; }
        SubscriptionLevel level = userService.subscriptionLevel(user.getId());
        if (!rateLimitService.tryAcquire(user.getId(), level)) {
            ack(cb, "Лимит запросов исчерпан");
            return;
        }
        ack(cb, "Обновляю…");
        WatchedItem w = opt.get();
        try {
            VintedItem item = parserService.parseVintedUrl(w.getVintedUrl());
            historyService.save(user.getId(), item);
            watchlistService.applySnapshot(user.getId(), watchId, item);
            sendListingCard(SendTarget.chat(chatId), ListingCard.of(item), null, null);
        } catch (VintedParseException e) {
            send(chatId, userFacingError(e));
        } catch (Exception e) {
            send(chatId, "❌ Не удалось обновить запись.");
            log.error("Refresh failed for watchId={}", watchId, e);
        }
    }

    // ---------------------------------------------------------------- output

    private void send(Long chatId, String text) {
        send(SendTarget.chat(chatId), text, null);
    }

    private void send(Long chatId, String text, InlineKeyboardMarkup markup) {
        send(SendTarget.chat(chatId), text, markup);
    }

    private void send(SendTarget target, String text) {
        send(target, text, null);
    }

    private void send(SendTarget target, String text, InlineKeyboardMarkup markup) {
        trySend(target, text, markup);
    }

    /** Like {@link #send} but reports whether Telegram accepted the message. */
    private boolean trySend(SendTarget target, String text, InlineKeyboardMarkup markup) {
        SendMessage msg = new SendMessage();
        msg.setChatId(target.chatId().toString());
        if (target.messageThreadId() != null) {
            msg.setMessageThreadId(target.messageThreadId().intValue());
        }
        msg.setText(text);
        msg.setParseMode(ParseMode.HTML);
        msg.setDisableWebPagePreview(false);
        if (markup != null) msg.setReplyMarkup(markup);
        try {
            dispatch(msg);
            return true;
        } catch (TelegramApiException e) {
            log.error("Failed to send message to {}: {}", target.chatId(), e.getMessage());
            return false;
        }
    }

    /** Sends with the persistent reply-keyboard menu (private chats only). */
    private void sendMenu(Ctx ctx, String text) {
        if (ctx.group()) {
            send(ctx.reply(), text, null);
            return;
        }
        SendMessage msg = new SendMessage();
        msg.setChatId(ctx.chatId().toString());
        msg.setText(text);
        msg.setParseMode(ParseMode.HTML);
        msg.setDisableWebPagePreview(true);
        msg.setReplyMarkup(mainMenuKeyboard());
        try {
            dispatch(msg);
        } catch (TelegramApiException e) {
            log.error("Failed to send menu to {}: {}", ctx.chatId(), e.getMessage());
        }
    }

    private ReplyKeyboardMarkup mainMenuKeyboard() {
        KeyboardRow r1 = new KeyboardRow();
        r1.add(new KeyboardButton(BTN_SUBS));
        r1.add(new KeyboardButton(BTN_LIST));
        KeyboardRow r2 = new KeyboardRow();
        r2.add(new KeyboardButton(BTN_HISTORY));
        r2.add(new KeyboardButton(BTN_STATS));
        KeyboardRow r3 = new KeyboardRow();
        r3.add(new KeyboardButton(BTN_ADD));
        r3.add(new KeyboardButton(BTN_HELP));
        ReplyKeyboardMarkup kb = new ReplyKeyboardMarkup();
        kb.setKeyboard(List.of(r1, r2, r3));
        kb.setResizeKeyboard(true);
        kb.setIsPersistent(true);
        return kb;
    }

    /**
     * Sends a compact listing card: photo + caption (title & price) + a
     * "🔗 Перейти на Vinted" link button. If a photo is missing or Telegram
     * can't fetch it, falls back to a text message with the same caption/buttons.
     *
     * @param saveParsedItemId if non-null, adds a "⭐ В список" save button
     */
    private boolean sendListingCard(SendTarget target, ListingCard card, String header, Long saveParsedItemId) {
        String caption = formatter.cardCaption(card, header);
        InlineKeyboardMarkup markup = listingKeyboard(card.url(), saveParsedItemId);

        String photo = card.photoUrl();
        if (photo != null && !photo.isBlank()) {
            SendPhoto sp = new SendPhoto();
            sp.setChatId(target.chatId().toString());
            if (target.messageThreadId() != null) {
                sp.setMessageThreadId(target.messageThreadId().intValue());
            }
            sp.setPhoto(new InputFile(photo));
            sp.setCaption(caption);
            sp.setParseMode(ParseMode.HTML);
            sp.setReplyMarkup(markup);
            try {
                dispatch(sp);
                return true;
            } catch (TelegramApiException e) {
                // Telegram couldn't fetch the image — fall back to a text card.
                log.debug("sendPhoto failed ({}), falling back to text: {}", photo, e.getMessage());
            }
        }
        return trySend(target, caption, markup);
    }

    private InlineKeyboardMarkup listingKeyboard(String url, Long saveParsedItemId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (url != null && !url.isBlank()) {
            rows.add(List.of(urlButton("🔗 Перейти на Vinted", url)));
        }
        if (saveParsedItemId != null) {
            rows.add(List.of(button("⭐ В список", "save:" + saveParsedItemId)));
        }
        InlineKeyboardMarkup m = new InlineKeyboardMarkup();
        m.setKeyboard(rows);
        return m;
    }

    private InlineKeyboardButton urlButton(String text, String url) {
        InlineKeyboardButton b = new InlineKeyboardButton(text);
        b.setUrl(url);
        return b;
    }

    protected void dispatch(SendPhoto photo) throws TelegramApiException {
        super.execute(photo);
    }

    protected void dispatch(SendMessage msg) throws TelegramApiException {
        super.execute(msg);
    }

    protected void dispatch(AnswerCallbackQuery a) throws TelegramApiException {
        super.execute(a);
    }

    private void ack(CallbackQuery cb, String text) {
        AnswerCallbackQuery a = new AnswerCallbackQuery();
        a.setCallbackQueryId(cb.getId());
        if (text != null) a.setText(text);
        try {
            dispatch(a);
        } catch (TelegramApiException e) {
            log.debug("answerCallbackQuery failed: {}", e.getMessage());
        }
    }

    private InlineKeyboardButton button(String text, String data) {
        InlineKeyboardButton b = new InlineKeyboardButton(text);
        b.setCallbackData(data);
        return b;
    }

    // ----------------------------------------------------------------- utils

    /** Extracts distinct Vinted URLs from arbitrary text. */
    public static List<String> extractUrls(String text) {
        List<String> urls = new ArrayList<>();
        if (text == null) return urls;
        Matcher m = URL_PATTERN.matcher(text);
        while (m.find()) {
            String u = m.group();
            if (!urls.contains(u)) urls.add(u);
        }
        return urls;
    }

    private String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String userFacingError(VintedParseException e) {
        return switch (e.getReason()) {
            case INVALID_URL -> "⚠️ Это не похоже на ссылку объявления Vinted.";
            case NOT_FOUND -> "🔍 Объявление не найдено или удалено.";
            case BLOCKED -> "🛡️ Vinted временно ограничил доступ (анти-бот проверка). "
                    + "Подождите немного и попробуйте снова.";
            case TIMEOUT -> "⏱️ Превышено время ожидания. Попробуйте ещё раз.";
            default -> "❌ Не удалось спарсить объявление. Попробуйте позже.";
        };
    }

    private String welcomeMessage(Ctx ctx) {
        if (ctx.group()) {
            return """
                    👋 <b>Привет! Я слежу за Vinted.</b>

                    Добавьте поиск командой <code>/search ссылка</code> или просто
                    пришлите ссылку в чат (нужен выключенный режим приватности —
                    см. /setup).

                    Если включены <b>Темы</b> и у меня есть право их создавать,
                    под каждый поиск заведу отдельную подтему 🧵.
                    Настройка: /setup · Подписки: /subs · Справка: /help""";
        }
        return """
                👋 <b>Привет! Я бот-охотник по Vinted.</b>

                • Ссылка на <b>объявление</b> → карточка с ценой, размером, брендом.
                • Ссылка на <b>поиск с фильтрами</b> → свежие объявления
                  + подписка: новые присылаю автоматически 🔔 (в течение секунд).

                Нажмите <b>«➕ Добавить»</b> в меню ниже 👇 — я подскажу каждый шаг.
                Или сразу пришлите ссылку. Справка: /help""";
    }

    private String helpMessage() {
        return """
                ℹ️ <b>Как пользоваться</b>

                <b>Проще всего:</b> нажмите «➕ Добавить» в меню и следуйте
                подсказкам. Или сразу пришлите ссылку.

                <b>🔔 Отслеживание поиска (главное).</b>
                Настройте фильтры на Vinted, сортировку «Сначала новые»,
                скопируйте ссылку <code>…/catalog?search_text=…</code> и пришлите.
                Покажу свежее и буду присылать новое в течение секунд.
                Управление: /subs — 🔄 проверить · ⏸/▶️ пауза · ✏️ имя · 🗑 удалить.

                <b>👗 Объявление.</b> Ссылка <code>…/items/123…</code> → карточка.
                Список сохранённого: /list.

                <b>👥 В группах.</b> Наберите /setup в группе — пришлю пошаговую
                настройку (темы под каждый поиск).

                <b>Команды:</b> /add · /subs · /list · /history · /stats · /setup
                <b>Подписок:</b> без ограничений.""";
    }

    private String whereToGetLinkMessage() {
        return """
                ❓ <b>Где взять ссылку на поиск</b>

                <b>В приложении Vinted:</b>
                1. Откройте поиск, задайте фильтры (бренд, размер, цена…).
                2. Сортировка → «Сначала новые».
                3. Меню (⋯) → «Поделиться» / «Скопировать ссылку».

                <b>В браузере (удобнее):</b> откройте vinted → примените фильтры →
                просто скопируйте адрес из адресной строки. Пример:
                <code>https://www.vinted.de/catalog?search_text=nike&amp;order=newest_first</code>

                Затем пришлите ссылку сюда 👇""";
    }

    private String groupSetupMessage(boolean forum) {
        return """
                👥 <b>Настройка бота в группе</b>

                Чтобы я работал в группе и создавал темы под каждый поиск:

                <b>1. Сделайте меня админом.</b> Настройки группы → Администраторы →
                добавить @""" + esc(botProperties.getUsername()) + """
                . Включите право <b>«Управление темами» (Manage Topics)</b>.

                <b>2. Включите «Темы» (Topics)</b> в настройках группы —
                тогда под каждый поиск появится отдельная подтема 🧵.

                <b>3. ВАЖНО — режим приватности.</b> Чтобы я видел ссылки,
                отправленные в чат, владелец бота должен один раз в @BotFather:
                <code>/setprivacy</code> → выбрать бота → <b>Disable</b>.
                Иначе добавляйте поиск командой: <code>/search ссылка</code>
                (команды я вижу всегда).

                Готово! Пришлите в группу ссылку на поиск Vinted или
                <code>/search ссылка</code>. Управление: /subs""";
    }
}
