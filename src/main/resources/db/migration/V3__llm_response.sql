ALTER TABLE llm_call ADD COLUMN IF NOT EXISTS response_text text;
CREATE INDEX IF NOT EXISTS llm_call_tenant_created_idx ON llm_call (tenant_id, created_at);
