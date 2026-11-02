#!/usr/bin/env bash
# Бэкап внутренних баз uzelok (каталог vaults) через kubectl exec. Хранение 7 дней.
set -euo pipefail

KUBECONFIG="${KUBECONFIG:-$HOME/.kube/config}"
export KUBECONFIG

backup_dir="/mnt/hdd/backups/uzelok"
log_file="$backup_dir/backup.log"
timestamp="$(date -u +%Y%m%d-%H%M)"
target="$backup_dir/vaults-$timestamp.tar.gz"

mkdir -p "$backup_dir"

if ! kubectl exec -n uzelok deploy/uzelok -- test -d /var/lib/uzelok/vaults; then
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) backup-vaults SKIPPED no vaults dir" >> "$log_file"
    exit 0
fi

if ! kubectl exec -n uzelok deploy/uzelok -- \
        tar -C /var/lib/uzelok -czf - vaults > "$target"; then
    rm -f "$target"
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) backup-vaults FAILED" >> "$log_file"
    exit 1
fi

find "$backup_dir" -maxdepth 1 -type f -name 'vaults-*.tar.gz' -mtime +7 -delete

echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) backup-vaults OK file=$(basename "$target") size=$(stat -c %s "$target")" >> "$log_file"
