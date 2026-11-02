CREATE TABLE IF NOT EXISTS onboarding_session (
    chat_id    bigint PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenant(id),
    step       text NOT NULL CHECK (step IN ('AWAITING_PATH')),
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
