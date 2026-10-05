#!/usr/bin/env bash
# Installs/updates the bot as a macOS launchd agent (free mode: runs on this Mac).
#
#   deploy/install-macos.sh     # build jar, copy runtime files, (re)start the agent
#
# launchd agents may not read ~/Desktop or ~/Documents (macOS privacy protection),
# so the runtime copy lives in ~/Library/Application Support/vinted-bot. Postgres
# runs in Docker: `docker compose up -d postgres` (published on 127.0.0.1:15432).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$HOME/Library/Application Support/vinted-bot"
PLIST="$HOME/Library/LaunchAgents/com.vinted.bot.plist"
cd "$ROOT"

export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
mvn -q package -DskipTests

mkdir -p "$APP/deploy" "$APP/target" "$APP/logs"
rm -f "$APP"/target/*.jar
cp target/vinted-telegram-bot-*.jar "$APP/target/"
cp deploy/run-local.sh "$APP/deploy/run-local.sh"
install -m 600 .env "$APP/.env"

sed "s#/Users/dmitrijsajbakov/Desktop/vinted-telegram-bot#$APP#g" deploy/com.vinted.bot.plist > "$PLIST"
launchctl bootout "gui/$(id -u)/com.vinted.bot" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"
echo "Started. Logs: tail -f \"$APP/logs/vinted-bot.log\""
