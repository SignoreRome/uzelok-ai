ALTER TABLE processing_job ADD COLUMN IF NOT EXISTS draft_version int NOT NULL DEFAULT 0;
ALTER TABLE processing_job ADD COLUMN IF NOT EXISTS force_inbox boolean NOT NULL DEFAULT false;
CREATE INDEX IF NOT EXISTS pending_action_job_idx ON pending_action (job_id);
