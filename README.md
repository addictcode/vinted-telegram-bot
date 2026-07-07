# 🛍️ Vinted Telegram Bot

**A Telegram bot that watches Vinted for you.** Drop it a search filter and it
pushes new listings the moment they appear — near real-time, no polling by
hand. Drop it a single item link and it parses a clean card (price, size,
brand, condition, colour, photos). Works in DMs and in group chats, with a
dedicated forum **topic per saved search**.

![Java](https://img.shields.io/badge/Java-17-orange?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-6DB33F?logo=springboot&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?logo=postgresql&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-ready-2496ED?logo=docker&logoColor=white)
![License](https://img.shields.io/badge/license-MIT-informational)

> ⚠️ **Personal / educational use.** This talks to Vinted's public pages and
> its internal (undocumented) JSON API — there is no official public API.
> Scraping may fall outside Vinted's Terms of Service; keep request volume
> reasonable and don't redistribute scraped data. Not affiliated with Vinted.

---

## Why this exists

Good Vinted finds disappear in minutes. Refreshing a search by hand doesn't
scale — this bot does the refreshing, at near-real-time speed, and only
bothers you when something new actually shows up.

## ✨ Features

**🔔 Search subscriptions (the main feature)**
Send a catalog/filter link (`…/catalog?search_text=…&order=newest_first`) and
the bot:
1. shows the 3 freshest listings immediately,
2. subscribes to that exact filter,
3. polls Vinted's lightweight internal JSON API every **20 seconds** (not the
   full HTML page — ~40 KB vs ~8 MB) and pushes new matches within seconds,
4. falls back to HTML scraping if the API is briefly unavailable, and backs
   off automatically if it detects an anti-bot challenge.

Manage subscriptions with inline buttons: 🔄 check now · ⏸/▶️ pause/resume ·
✏️ rename · 🗑 delete — up to 10 per user.

**👗 Single-item parsing**
Paste one or several `…/items/…` links (batched, up to 10 per message) and get
a formatted card each: title, price, brand, size, condition, colour,
description, photos. One tap saves it to your **watchlist** (`/list`), with
its own refresh/rename/delete buttons.

**👥 Groups & forum topics**
Add the bot to a group. If the group has **Topics** enabled and the bot has
the *Manage Topics* right, every saved search gets its **own subtopic** — the
whole group stays organized instead of one noisy feed. Full group setup guide:
[`GROUPS.md`](GROUPS.md).

**🧭 Guided UX**
A persistent reply-keyboard menu (➕ Добавить · 🔔 Подписки · ⭐ Список ·
📜 История · 📊 Статистика · ℹ️ Помощь) plus the native Telegram `/` command
menu — no need to memorize commands. `/add` walks you through adding a search
or an item step by step.

**⚙️ Everything else**
- `/history` (paginated) · `/clear_history` · `/stats`
- Rate limiting: 10 requests/hour on the free tier, unlimited on premium
  (subscription-level scaffolding already in the data model)
- Browser-like headers, User-Agent rotation, randomized delays, retries with
  an HTML fallback, real anti-bot-challenge detection
- Structured logging (console + rolling file), graceful shutdown
- Runs in ~256 MB RAM — headless Chrome is optional
  (`SELENIUM_ENABLED=false`), primary parsing is plain HTTP (Jsoup)

---

## Tech stack

Spring Boot 3 · PostgreSQL + Flyway migrations · [TelegramBots](https://github.com/rubenlagus/TelegramBots)
Java library · Jsoup (HTTP/HTML) · Selenium (optional headless-Chrome
fallback) · Lombok · Jackson

---

## Project layout

```
src/main/java/com/example/vintedbot
├── VintedBotApplication.java
├── bot/          VintedTelegramBot (all commands/callbacks), BotRegistrar
├── config/       properties, WebDriver factory, Jackson config
├── dto/          VintedItem, CatalogItemSummary, SendTarget
├── model/        User, ParsedItem, WatchedItem, SearchSubscription, …
├── repository/   Spring Data JPA repositories
├── service/      parser, catalog API client, search monitor, history,
│                 watchlist, rate limiter, message formatter
└── util/         UserAgentRotator
src/main/resources
├── application.yml
├── logback-spring.xml
└── db/migration/           Flyway schema (V1–V4)
```

---

## Quick start (Docker — recommended)

Chrome/chromedriver are baked into the default image; the `Dockerfile.slim`
variant skips them entirely (HTTP-only parsing, smaller footprint).

```bash
cp .env.example .env
# edit .env: BOT_TOKEN (from @BotFather) and BOT_USERNAME
docker compose up --build
```

Starts PostgreSQL + the bot; Flyway migrates the schema on boot.

## Run locally (without Docker)

Requirements: JDK 17+, Maven, PostgreSQL. Chrome is optional (only needed if
`SELENIUM_ENABLED=true`; WebDriverManager resolves the driver automatically).

```bash
createdb vinted

export BOT_TOKEN=your-fresh-botfather-token
export BOT_USERNAME=your_bot_username
export DB_URL=jdbc:postgresql://localhost:5432/vinted
export DB_USER=vinted
export DB_PASSWORD=vinted

mvn spring-boot:run
```

Without `BOT_TOKEN` the app still boots and runs migrations, just without
Telegram polling — handy for CI or DB-only work.

## Deploying to stay online 24/7 (free options)

See [`DEPLOY.md`](DEPLOY.md) for Oracle Cloud Always Free, Fly.io/Koyeb +
Neon Postgres, and local/launchd setups.

## Setting it up in a group

See [`GROUPS.md`](GROUPS.md) — covers Telegram's bot **privacy mode** (the
usual reason "the bot doesn't see messages in my group"), admin rights for
forum topics, and a step-by-step checklist.

---

## Configuration

All settings live in `application.yml`, overridable via environment variables:

| Variable | Default | Purpose |
|---|---|---|
| `BOT_TOKEN` | — | BotFather token (**required** to poll) |
| `BOT_USERNAME` | `vinted_parser_bot` | Bot username |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | local postgres | Database |
| `SELENIUM_ENABLED` | `true` | Headless-Chrome fallback on/off |
| `CHROME_BINARY_PATH` / `CHROME_DRIVER_PATH` | auto | Chrome/driver paths |
| `MONITOR_INTERVAL_MS` | `20000` | Search-subscription poll interval |
| `VINTED_PROXY` | — | Optional HTTP/SOCKS proxy for scaling |
| `IMAGE_CACHE_DIR` | `/tmp/vinted_images` | Image cache directory |

Rate limit: `rate-limit.free-requests-per-hour` (default `10`).
Parser tuning: `vinted.parser.*` (timeouts, retry count, delay range).

---

## Tests

```bash
mvn test
```

53 tests: unit tests for the parser (URL validation, price parsing, JSON-LD/OG
extraction, catalog API response parsing) and rate limiter, plus full
bot-flow integration tests (real Spring Data JPA layer + a captured Telegram
send seam) covering single/batch parsing, watchlists, search subscriptions,
pause/resume, forum-topic creation, and the guided add-menu.

Live smoke tests against real Vinted URLs are opt-in (disabled by default —
they hit the network):

```bash
VINTED_TEST_URL=https://www.vinted.com/items/123456789 mvn test
VINTED_CATALOG_TEST_URL='https://www.vinted.de/catalog?search_text=nike&order=newest_first' mvn test
```

---

## Security notes

- **Never commit your bot token.** It's read from the `BOT_TOKEN` env var;
  `.env` is git-ignored. If a token leaks, revoke it in @BotFather immediately.
- The rate limiter is in-memory — back it with Redis for multi-instance deploys.
- Consider the legal/ToS implications before running this at scale.

---

## Example: a search-subscription push

```
🔔 Новое объявление по подписке «Поиск: nike air»

👗 Nike Air Force's
💰 $60 · 🏷️ Nike · 📦 42 · ⭐ Good
🔗 Перейти на Vinted
```

## License

MIT — see [LICENSE](LICENSE).
