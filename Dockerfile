# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q clean package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Chromium + driver for headless Selenium
RUN apt-get update \
    && apt-get install -y --no-install-recommends chromium-browser chromium-chromedriver ca-certificates fonts-liberation \
    && rm -rf /var/lib/apt/lists/*

ENV CHROME_BINARY_PATH=/usr/bin/chromium-browser \
    CHROME_DRIVER_PATH=/usr/bin/chromedriver \
    IMAGE_CACHE_DIR=/tmp/vinted_images

COPY --from=build /app/target/vinted-telegram-bot-*.jar app.jar

# JVM handles SIGTERM -> Spring graceful shutdown
STOPSIGNAL SIGTERM

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
