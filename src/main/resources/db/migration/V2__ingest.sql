CREATE UNIQUE INDEX IF NOT EXISTS source_item_media_group_uniq
    ON source_item (chat_id, media_group_id) WHERE media_group_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS source_item_chat_created_idx ON source_item (chat_id, created_at);
