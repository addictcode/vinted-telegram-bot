# Как держать бота онлайн 24/7 (бесплатно)

Телеграм-бот на long-polling должен работать **постоянно** — значит нужен хост,
который не «засыпает». Ниже — варианты от самого надёжного бесплатного до
запуска на своём компьютере. Файлы-заготовки лежат в папке `deploy/`.

Парсинг теперь работает на **лёгком HTTP (Jsoup)** — браузер Chrome не
обязателен, поэтому бот помещается даже в 256 МБ RAM.

---

## Вариант 1 (рекомендуется): Oracle Cloud «Always Free» — бесплатно навсегда

Oracle даёт **бесплатную навсегда** ARM-машину (до 4 ядер / 24 ГБ RAM). Этого
с огромным запасом хватит на бот + PostgreSQL + Chrome-фолбэк. Нужна карта
для верификации личности (деньги не списываются на Always Free).

1. Зарегистрируйся: https://www.oracle.com/cloud/free/ → выбери регион рядом.
2. Создай ВМ: **Compute → Instances → Create**.
   - Shape: **Ampere (ARM) VM.Standard.A1.Flex**, 1–2 OCPU, 6 ГБ RAM (в пределах Always Free).
   - Image: **Ubuntu 22.04**.
   - Скачай приватный SSH-ключ.
   - В **Networking** разреши только SSH (входящий трафик боту не нужен).
3. Подключись и поставь Docker:
   ```bash
   ssh -i key.pem ubuntu@<IP>
   sudo apt update && sudo apt install -y docker.io docker-compose-plugin git
   sudo usermod -aG docker $USER && newgrp docker
   sudo systemctl enable --now docker
   ```
4. Залей проект и запусти:
   ```bash
   sudo mkdir -p /opt/vinted-telegram-bot && sudo chown $USER /opt/vinted-telegram-bot
   git clone <твой-репозиторий> /opt/vinted-telegram-bot   # или scp -r папку
   cd /opt/vinted-telegram-bot
   cp .env.example .env && nano .env      # впиши свежий BOT_TOKEN и BOT_USERNAME
   docker compose up -d --build
   ```
5. Автозапуск после перезагрузки уже обеспечен: в `docker-compose.yml` стоит
   `restart: unless-stopped`, а Docker включён в автозапуск. Для управления
   как сервисом можно использовать `deploy/vinted-bot.service` (systemd).

Логи: `docker compose logs -f bot`. Обновление: `git pull && docker compose up -d --build`.

---

## Вариант 2: Fly.io (лёгкий образ без Chrome) + бесплатный Postgres

Подходит, если не хочешь возиться с ВМ. Нужна карта. Датацентр-IP иногда
ловит анти-бот проверку Vinted — тогда задай `VINTED_PROXY` (см. ниже).

1. Бесплатный Postgres: заведи базу на **Neon** (neon.tech) или **Supabase** —
   получишь строку подключения (`postgresql://user:pass@host/db`).
2. Установи flyctl, затем из папки `deploy/`:
   ```bash
   fly launch --no-deploy --dockerfile ../Dockerfile.slim
   fly secrets set BOT_TOKEN=xxx BOT_USERNAME=yourbot \
       DB_URL="jdbc:postgresql://<host>/<db>?sslmode=require" \
       DB_USER=<user> DB_PASSWORD=<pass> SELENIUM_ENABLED=false
   fly deploy
   ```
   Заготовка конфига: `deploy/fly.toml` (одна всегда-живая машина, 512 МБ).

Аналогично работают **Koyeb** и **Railway** (используй `Dockerfile.slim`).

---

## Вариант 3: свой Mac / мини-ПК (0 ₽, но должен быть включён)

Бот уже собран локально. Чтобы он переживал перезагрузки и падения — поставь
launchd-агент (файл `deploy/com.vinted.bot.plist`, пути внутри уже прописаны):

```bash
cp deploy/com.vinted.bot.plist ~/Library/LaunchAgents/
launchctl load ~/Library/LaunchAgents/com.vinted.bot.plist
```

Минус: Mac должен быть включён и не спать. Чтобы не засыпал —
`caffeinate -s ./deploy/run-local.sh`. На Raspberry Pi / любом Linux используй
`deploy/vinted-bot.service`.

---

## Что выбрать

| Хост | Всегда онлайн | Реально бесплатно | Нужна карта | Chrome-фолбэк |
|---|---|---|---|---|
| **Oracle Cloud Free** | ✅ | ✅ навсегда | да (без списаний) | ✅ |
| Fly.io / Koyeb + Neon | ✅ | ✅ (лимиты) | да | ❌ (HTTP-only) |
| Свой Mac / Pi | пока включён | ✅ | нет | ✅ |

**Вывод:** для «включил и забыл, бесплатно навсегда» — **Oracle Cloud Always Free**.

---

## Если Vinted начал блокировать (анти-бот на датацентр-IP)

Symptom: сообщения «🛡️ Vinted временно ограничил доступ». Датацентр-адреса
DataDome режет чаще, чем домашние. Решения:
- задать прокси в `.env`: `VINTED_PROXY=http://user:pass@host:port`
  (резидентный прокси надёжнее всего);
- на Oracle-ВМ оставить `SELENIUM_ENABLED=true` — иногда браузер проходит там,
  где чистый HTTP не прошёл;
- не частить: лимит free 10/час и задержки 2–5 с уже снижают риск.
