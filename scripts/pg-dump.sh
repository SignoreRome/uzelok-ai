#!/usr/bin/env bash
# pg_dump базы uzelok через kubectl exec. Хранение 7 дней, пустой дамп — ошибка.
set -euo pipefail

KUBECONFIG="${KUBECONFIG:-$HOME/.kube/config}"
export KUBECONFIG

backup_dir="/mnt/hdd/backups/uzelok"
log_file="$backup_dir/backup.log"
timestamp="$(date -u +%Y%m%d-%H%M)"
target="$backup_dir/uzelok-$timestamp.sql.gz"

mkdir -p "$backup_dir"

if ! kubectl exec -n infra-shared postgres-0 -- \
        pg_dump -U postgres -d uzelok --no-owner | gzip -9 > "$target"; then
    rm -f "$target"
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) pg_dump FAILED" >> "$log_file"
    exit 1
fi

size="$(stat -c %s "$target")"
if [[ "$size" -lt 1024 ]]; then
    rm -f "$target"
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) pg_dump FAILED empty size=$size" >> "$log_file"
    exit 1
fi

find "$backup_dir" -maxdepth 1 -type f -name 'uzelok-*.sql.gz' -mtime +7 -delete

echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) pg_dump OK file=$(basename "$target") size=$size" >> "$log_file"
