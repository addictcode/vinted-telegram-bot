-- Users of the bot
CREATE TABLE users (
    id          BIGSERIAL PRIMARY KEY,
    chat_id     BIGINT      NOT NULL UNIQUE,
    username    VARCHAR(255),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_active   BOOLEAN     NOT NULL DEFAULT TRUE
);

-- History of parsed Vinted items
CREATE TABLE parsed_items (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    vinted_url  TEXT        NOT NULL,
    title       VARCHAR(512),
    price       DOUBLE PRECISION,
    currency    VARCHAR(8),
    brand       VARCHAR(255),
    size        VARCHAR(128),
    description TEXT,
    condition   VARCHAR(128),
    image_urls  TEXT,          -- JSON array of image URLs
    raw_json    TEXT,          -- raw scraped payload for debugging
    parsed_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_parsed_items_user_id   ON parsed_items (user_id);
CREATE INDEX idx_parsed_items_parsed_at ON parsed_items (parsed_at);

-- Subscription tiers
CREATE TABLE user_subscriptions (
    id                 BIGSERIAL PRIMARY KEY,
    user_id            BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    subscription_level VARCHAR(32) NOT NULL DEFAULT 'FREE',
    expires_at         TIMESTAMPTZ
);

CREATE INDEX idx_user_subscriptions_user_id ON user_subscriptions (user_id);
