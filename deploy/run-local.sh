#!/usr/bin/env bash
# Runs the bot locally, loading env from .env in the project root.
# Used by the launchd/systemd wrappers and handy for manual runs.
set -euo pipefail
cd "$(dirname "$0")/.."

# Load .env if present
if [[ -f .env ]]; then
  set -a; source .env; set +a
fi

: "${DB_URL:=jdbc:postgresql://localhost:5432/vinted}"
: "${DB_USER:=vinted}"
: "${DB_PASSWORD:=vinted}"
: "${IMAGE_CACHE_DIR:=/tmp/vinted_images}"
# Auto-detect Chrome on macOS for the Selenium fallback.
if [[ -z "${CHROME_BINARY_PATH:-}" && -x "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" ]]; then
  export CHROME_BINARY_PATH="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
fi
export DB_URL DB_USER DB_PASSWORD IMAGE_CACHE_DIR

JAR=$(ls target/vinted-telegram-bot-*.jar 2>/dev/null | head -1)
if [[ -z "$JAR" ]]; then
  echo "Building jar first..."; ./mvnw -q package -DskipTests 2>/dev/null || mvn -q package -DskipTests
  JAR=$(ls target/vinted-telegram-bot-*.jar | head -1)
fi

exec java -jar "$JAR"
