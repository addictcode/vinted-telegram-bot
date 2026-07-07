-- Capture the item colour (Vinted exposes it as a first-class attribute).
ALTER TABLE parsed_items ADD COLUMN color VARCHAR(128);

-- Watchlist: links the user saves to keep and refresh on demand.
CREATE TABLE watched_items (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    vinted_url      TEXT        NOT NULL,
    label           VARCHAR(255),          -- user-editable name/note
    last_title      VARCHAR(512),
    last_price      DOUBLE PRECISION,
    last_currency   VARCHAR(8),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_checked_at TIMESTAMPTZ,
    active          BOOLEAN     NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_watched_items_user_id ON watched_items (user_id);
-- A user cannot save the same URL twice.
CREATE UNIQUE INDEX uq_watched_user_url ON watched_items (user_id, vinted_url);
