#!/usr/bin/env bash
# Печатает идемпотентный SQL, который заводит владельца базы: tenant, app_user, telegram_account,
# knowledge_base. Значения подставляются из аргументов, поэтому запуск воспроизводим и не зависит
# от ручных правок в БД.
#
# Использование:
#   scripts/seed-user.sh --chat-id 123456789 --user-id 987654321 --slug roman --name "Роман" \
#       --vault /path/to/vault
#   scripts/seed-user.sh --chat-id 123456789 --user-id 987654321 --slug guest --name "Гость"
#       (без --vault: доступ есть, базы знаний ещё нет — бот ответит «База знаний не подключена»)
#   scripts/seed-user.sh ... | kubectl exec -i -n infra-shared postgres-0 -- \
#       env PGPASSWORD=... psql -U ... -d uzelok
#
# --chat-id        chat_id личного чата с ботом (число)
# --user-id        telegram user id владельца (число)
# --vault          абсолютный путь к базе знаний на диске сервера (необязателен)
# --slug           слаг тенанта (по умолчанию owner)
# --name           отображаемое имя (по умолчанию Owner)
# --kind           EXTERNAL_OBSIDIAN (по умолчанию) или INTERNAL_DIR
set -euo pipefail

chat_id=""; user_id=""; vault=""; slug="owner"; name="Owner"; kind="EXTERNAL_OBSIDIAN"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --chat-id) chat_id="$2"; shift 2 ;;
        --user-id) user_id="$2"; shift 2 ;;
        --vault) vault="$2"; shift 2 ;;
        --slug) slug="$2"; shift 2 ;;
        --name) name="$2"; shift 2 ;;
        --kind) kind="$2"; shift 2 ;;
        *) echo "неизвестный аргумент: $1" >&2; exit 2 ;;
    esac
done

[[ "$chat_id" =~ ^[0-9]+$ ]] || { echo "нужен числовой --chat-id" >&2; exit 2; }
[[ "$user_id" =~ ^[0-9]+$ ]] || { echo "нужен числовой --user-id" >&2; exit 2; }
if [[ -n "$vault" && "$vault" != /* ]]; then echo "нужен абсолютный --vault" >&2; exit 2; fi
[[ "$kind" == "EXTERNAL_OBSIDIAN" || "$kind" == "INTERNAL_DIR" ]] || { echo "неверный --kind" >&2; exit 2; }

quoted_vault=${vault//\'/\'\'}
vault_block=""
if [[ -n "$vault" ]]; then
    vault_block="
INSERT INTO knowledge_base (tenant_id, kind, root_path)
SELECT id, '$kind', '$quoted_vault' FROM tenant WHERE slug = '$slug'
ON CONFLICT (tenant_id) DO UPDATE SET root_path = EXCLUDED.root_path, kind = EXCLUDED.kind;
"
fi

cat <<SQL
BEGIN;

INSERT INTO tenant (slug, name) VALUES ('$slug', '$name')
ON CONFLICT (slug) DO NOTHING;

INSERT INTO app_user (tenant_id, telegram_user_id, display_name)
SELECT id, $user_id, '$name' FROM tenant WHERE slug = '$slug'
ON CONFLICT (telegram_user_id) DO NOTHING;

INSERT INTO telegram_account (chat_id, user_id, tenant_id, allowed)
SELECT $chat_id, au.id, t.id, true
FROM tenant t JOIN app_user au ON au.tenant_id = t.id
WHERE t.slug = '$slug' AND au.telegram_user_id = $user_id
ON CONFLICT (chat_id) DO UPDATE SET allowed = true, user_id = EXCLUDED.user_id;

${vault_block}
COMMIT;
SQL
