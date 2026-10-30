CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE tenant (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    slug        text NOT NULL UNIQUE,
    name        text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE app_user (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid NOT NULL REFERENCES tenant(id),
    telegram_user_id  bigint NOT NULL UNIQUE,
    display_name      text,
    created_at        timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE telegram_account (
    chat_id     bigint PRIMARY KEY,
    user_id     uuid NOT NULL REFERENCES app_user(id),
    tenant_id   uuid NOT NULL REFERENCES tenant(id),
    allowed     boolean NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE knowledge_base (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid NOT NULL REFERENCES tenant(id),
    kind              text NOT NULL CHECK (kind IN ('EXTERNAL_OBSIDIAN','INTERNAL_DIR')),
    root_path         text NOT NULL,
    git_remote        text,       -- только EXTERNAL_OBSIDIAN; сервис это поле не использует
    branch            text,       -- только EXTERNAL_OBSIDIAN; справочно, сервис не переключает ветки
    taxonomy_relpath  text NOT NULL DEFAULT '40_Knowledge/_meta/taxonomy.yaml',
    inbox_relpath     text NOT NULL DEFAULT '40_Knowledge/_Inbox',
    active            boolean NOT NULL DEFAULT true,
    created_at        timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id)
);

CREATE TABLE telegram_update (
    update_id    bigint PRIMARY KEY,
    chat_id      bigint,
    update_type  text NOT NULL,
    received_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE source_item (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            uuid NOT NULL REFERENCES tenant(id),
    knowledge_base_id    uuid NOT NULL REFERENCES knowledge_base(id),
    chat_id              bigint NOT NULL,
    telegram_message_id  bigint NOT NULL,
    kind                 text NOT NULL CHECK (kind IN ('TEXT','PHOTO','DOCUMENT','TOPIC')),
    media_group_id       text,
    text_body            text,
    created_at           timestamptz NOT NULL DEFAULT now(),
    UNIQUE (chat_id, telegram_message_id)
);

-- Вложения: один source_item → N файлов (альбом из нескольких фото).
CREATE TABLE source_attachment (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source_item_id       uuid NOT NULL REFERENCES source_item(id) ON DELETE CASCADE,
    tenant_id            uuid NOT NULL REFERENCES tenant(id),
    position             int NOT NULL,
    telegram_file_id     text,
    file_path            text,
    mime                 text,
    byte_size            bigint,
    sha256               text,
    created_at           timestamptz NOT NULL DEFAULT now(),
    UNIQUE (source_item_id, position)
);

CREATE TABLE processing_job (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          uuid NOT NULL REFERENCES tenant(id),
    source_item_id     uuid NOT NULL UNIQUE REFERENCES source_item(id),
    knowledge_base_id  uuid NOT NULL REFERENCES knowledge_base(id),
    status             text NOT NULL,
    failed_stage       text,
    attempt_count      int NOT NULL DEFAULT 0,
    next_run_at        timestamptz NOT NULL DEFAULT now(),
    lease_until        timestamptz,
    status_message_id  bigint,
    error_code         text,
    error_message      text,
    confirmed_at       timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CHECK (status IN ('RECEIVED','EXTRACTING','DRAFTING','AWAITING_CONFIRM',
                      'WRITING','DONE','REJECTED','CANCELLED','FAILED'))
);

CREATE INDEX processing_job_queue_idx ON processing_job (status, next_run_at);

CREATE TABLE job_artifact (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id          uuid NOT NULL REFERENCES processing_job(id) ON DELETE CASCADE,
    -- каскад оставлен намеренно: сервис в MVP не удаляет job, а аудит живёт в audit_event;
    -- если появится удаление задач, каскад пересмотреть (иначе артефакты уедут вместе с job)
    tenant_id       uuid NOT NULL REFERENCES tenant(id),
    kind            text NOT NULL CHECK (kind IN ('ORIGINAL','EXTRACTION','DRAFT_JSON','RENDERED_MD','LINT_REPORT')),
    version         int NOT NULL DEFAULT 1,
    sha256          text,
    storage_path    text,
    inline_text     text,
    model           text,
    prompt_version  text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (job_id, kind, version)
);

CREATE TABLE note (
    id                 uuid PRIMARY KEY,
    tenant_id          uuid NOT NULL REFERENCES tenant(id),
    knowledge_base_id  uuid NOT NULL REFERENCES knowledge_base(id),
    job_id             uuid REFERENCES processing_job(id),
    type               text NOT NULL,
    title              text NOT NULL,
    domain             text,
    topic              text,
    file_name          text NOT NULL,
    rel_path           text NOT NULL,
    content_hash       text NOT NULL,
    git_commit_sha     text,       -- NULL: коммит делает внешний sync, сервис его не знает
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    UNIQUE (knowledge_base_id, file_name)
);

CREATE TABLE note_version (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    note_id        uuid NOT NULL REFERENCES note(id) ON DELETE CASCADE,
    tenant_id      uuid NOT NULL REFERENCES tenant(id),
    version_no     int NOT NULL,
    markdown       text NOT NULL,
    content_hash   text NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (note_id, version_no)
);

CREATE TABLE llm_call (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid NOT NULL REFERENCES tenant(id),
    job_id            uuid REFERENCES processing_job(id),
    step              text NOT NULL CHECK (step IN ('EXTRACT','DRAFT','REGENERATE')),
    provider          text NOT NULL,
    model             text NOT NULL,
    prompt_version    text NOT NULL,
    request_key       text NOT NULL UNIQUE,
    input_tokens      int,
    output_tokens     int,
    cost_usd          numeric(10,6),
    latency_ms        int,
    status            text NOT NULL CHECK (status IN ('SUCCESS','FAILED')),
    created_at        timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE pending_action (
    token       text PRIMARY KEY,
    job_id      uuid NOT NULL REFERENCES processing_job(id) ON DELETE CASCADE,
    tenant_id   uuid NOT NULL REFERENCES tenant(id),
    action      text NOT NULL CHECK (action IN ('SAVE','REGENERATE','INBOX','CANCEL','RETRY','CONTINUE_OCR')),
    chat_id     bigint NOT NULL,
    message_id  bigint,
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz
);

CREATE TABLE audit_event (
    id           bigserial PRIMARY KEY,
    tenant_id    uuid REFERENCES tenant(id),
    chat_id      bigint,
    action       text NOT NULL,
    entity_type  text,
    entity_id    uuid,
    detail       jsonb,
    created_at   timestamptz NOT NULL DEFAULT now()
);

-- Начальные данные для владельца (заполнить при деплое, не в миграции схемы)
-- INSERT INTO tenant (slug, name) VALUES ('roman', 'Roman');
-- INSERT INTO app_user (tenant_id, telegram_user_id, display_name) …
-- INSERT INTO telegram_account (chat_id, user_id, tenant_id, allowed) …
