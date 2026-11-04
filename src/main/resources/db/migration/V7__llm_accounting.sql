ALTER TABLE llm_call
    ADD COLUMN IF NOT EXISTS slot text,
    ADD COLUMN IF NOT EXISTS requested_model text,
    ADD COLUMN IF NOT EXISTS fallback_reason text,
    ADD COLUMN IF NOT EXISTS cache_read_tokens int NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS cache_write_tokens int NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS reasoning_tokens int NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS llm_call_created_idx ON llm_call (created_at);
CREATE INDEX IF NOT EXISTS llm_call_slot_created_idx ON llm_call (slot, created_at);
