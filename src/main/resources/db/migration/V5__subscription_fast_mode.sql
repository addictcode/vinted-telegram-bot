-- Snipe mode: subscriptions polled at a much shorter interval than the default.
ALTER TABLE search_subscriptions ADD COLUMN fast BOOLEAN NOT NULL DEFAULT FALSE;
