package com.example.vintedbot.bot;

import com.example.vintedbot.config.BotProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

/**
 * Registers the long-polling bot once the context is ready. Skips registration
 * (with a clear warning) when no BOT_TOKEN is configured so the app can still
 * boot for tests / DB migrations.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BotRegistrar {

    private final BotProperties botProperties;
    private final VintedTelegramBot bot;

    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        if (botProperties.getToken() == null || botProperties.getToken().isBlank()) {
            log.warn("BOT_TOKEN is not set — Telegram polling is DISABLED. "
                    + "Set the BOT_TOKEN environment variable to enable the bot.");
            return;
        }
        try {
            TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
            api.registerBot(bot);
            bot.registerCommands();
            log.info("Telegram bot registered and polling as @{}", bot.getBotUsername());
        } catch (Exception e) {
            log.error("Failed to register Telegram bot", e);
        }
    }
}
