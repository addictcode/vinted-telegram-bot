-- Saved catalog searches: the bot polls each active subscription and pushes
-- newly appeared listings to the user's chat.
CREATE TABLE search_subscriptions (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    catalog_url     TEXT        NOT NULL,
    label           VARCHAR(255),
    seen_item_ids   TEXT,               -- JSON array of recently seen item ids
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_checked_at TIMESTAMPTZ,
    active          BOOLEAN     NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_search_subs_user ON search_subscriptions (user_id);
-- One subscription per (user, normalized url).
CREATE UNIQUE INDEX uq_search_subs_user_url ON search_subscriptions (user_id, catalog_url);
