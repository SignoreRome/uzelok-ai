ALTER TABLE source_item ADD COLUMN IF NOT EXISTS material_hash text;

CREATE UNIQUE INDEX IF NOT EXISTS source_item_tenant_material_uniq
    ON source_item (tenant_id, material_hash) WHERE material_hash IS NOT NULL;

ALTER TABLE pending_action DROP CONSTRAINT IF EXISTS pending_action_action_check;
ALTER TABLE pending_action ADD CONSTRAINT pending_action_action_check
    CHECK (action IN ('SAVE','REGENERATE','INBOX','CANCEL','RETRY','CONTINUE_OCR','DUP_REGEN'));
