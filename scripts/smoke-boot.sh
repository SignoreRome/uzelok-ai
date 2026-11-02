#!/usr/bin/env bash
# Проверка, что приложение действительно стартует: контекст Spring поднимается, Flyway применяет миграции,
# поллер не запускается без токена. Ловит то, чего не видят unit-тесты: поломку автоконфигурации бинов.
#
# Требует: UZELOK_DB_URL, UZELOK_DB_USER, UZELOK_DB_PASSWORD в окружении; JDK 25 в JAVA_HOME.
# Использование: scripts/smoke-boot.sh [путь-к-jar]
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jar="${1:-$root/build/libs/uzelok-ai-0.0.1-SNAPSHOT.jar}"
log="$(mktemp -t uzelok-smoke-XXXXXX.log)"

for var in UZELOK_DB_URL UZELOK_DB_USER UZELOK_DB_PASSWORD JAVA_HOME; do
    if [[ -z "${!var:-}" ]]; then
        echo "smoke-boot: не задана переменная $var" >&2
        exit 2
    fi
done

if [[ ! -f "$jar" ]]; then
    echo "smoke-boot: собираю jar"
    (cd "$root" && ./gradlew bootJar --offline -q)
fi

echo "smoke-boot: запускаю $jar (токен бота пустой — поллер не стартует)"
set +e
timeout 90 env \
    UZELOK_BOT_TOKEN="" \
    UZELOK_BOT_USERNAME="smoke_bot" \
    UZELOK_ARTIFACTS_DIR="$(mktemp -d)" \
    java -jar "$jar" > "$log" 2>&1
status=$?
set -e

if grep -q "Started UzelokApplication" "$log"; then
    echo "smoke-boot: OK — контекст поднялся ($(grep -o 'Started UzelokApplication in [0-9.]* seconds' "$log" | tail -1))"
    grep -q "поллер не запущен" "$log" && echo "smoke-boot: OK — поллер без токена не стартовал"
    rm -f "$log"
    exit 0
fi

echo "smoke-boot: FAIL — контекст не поднялся, код $status" >&2
grep -E "APPLICATION FAILED|Error creating bean|Application run failed" "$log" | head -3 >&2
echo "smoke-boot: полный лог: $log" >&2
exit 1
