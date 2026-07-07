-- Where a subscription delivers: a private chat, or a group + forum topic.
-- Nullable chat_id falls back to the owner's private chat (existing rows).
ALTER TABLE search_subscriptions ADD COLUMN chat_id           BIGINT;
ALTER TABLE search_subscriptions ADD COLUMN message_thread_id BIGINT;
ALTER TABLE search_subscriptions ADD COLUMN chat_title        VARCHAR(255);

-- Backfill existing subscriptions to their owner's private chat.
UPDATE search_subscriptions s
   SET chat_id = (SELECT u.chat_id FROM users u WHERE u.id = s.user_id)
 WHERE chat_id IS NULL;

CREATE INDEX idx_search_subs_chat ON search_subscriptions (chat_id);
