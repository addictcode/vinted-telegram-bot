#!/usr/bin/env bash
# Runs the bot locally, loading env from .env in the project root.
# Used by the launchd/systemd wrappers and handy for manual runs.
set -euo pipefail
cd "$(dirname "$0")/.."

# Load .env if present
if [[ -f .env ]]; then
  set -a; source .env; set +a
fi

: "${DB_URL:=jdbc:postgresql://localhost:${DB_HOST_PORT:-15432}/${DB_NAME:-vinted}}"
: "${DB_USER:=vinted}"
: "${DB_PASSWORD:=vinted}"
: "${IMAGE_CACHE_DIR:=/tmp/vinted_images}"
: "${SELENIUM_ENABLED:=false}"
: "${JAVA_OPTS:=-XX:MaxRAMPercentage=25 -XX:MaxMetaspaceSize=128m -XX:+ExitOnOutOfMemoryError}"
# Auto-detect Chrome on macOS for the Selenium fallback.
if [[ -z "${CHROME_BINARY_PATH:-}" && -x "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" ]]; then
  export CHROME_BINARY_PATH="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
fi
export DB_URL DB_USER DB_PASSWORD IMAGE_CACHE_DIR SELENIUM_ENABLED

# Spring Boot 3.2 / Lombok need JDK 17; a newer default `java` (e.g. Homebrew 27) breaks them.
JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export JAVA_HOME

JAR=$(ls target/vinted-telegram-bot-*.jar 2>/dev/null | head -1)
if [[ -z "$JAR" ]]; then
  echo "Building jar first..."; mvn -q package -DskipTests
  JAR=$(ls target/vinted-telegram-bot-*.jar | head -1)
fi

# caffeinate -i keeps the Mac from idle-sleeping while the bot runs (closing the lid on battery still sleeps).
exec /usr/bin/caffeinate -i "$JAVA_HOME/bin/java" $JAVA_OPTS -jar "$JAR"
