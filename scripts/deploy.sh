#!/usr/bin/env bash
# Сборка и раскатка uzelok в k3s. Секреты не создаёт и не читает.
set -euo pipefail

TAG="${TAG:-0.1.0}"
KUBECONFIG="${KUBECONFIG:-$HOME/.kube/config}"
export KUBECONFIG

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
image="uzelok:$TAG"
tarball="$HOME/.hermes/cache/scratch/uzelok-$TAG.tar"
lint_source="$HOME/.hermes/scripts/agora_lint.py"

mkdir -p "$(dirname "$tarball")"

"$repo_root/gradlew" -p "$repo_root" bootJar
podman build -t "$image" "$repo_root"
podman save -o "$tarball" "$image"
sudo k3s ctr images import "$tarball"

kubectl create configmap uzelok-lint -n uzelok \
    --from-file=agora_lint.py="$lint_source" \
    --dry-run=client -o yaml | kubectl apply -f -

kubectl apply \
    -f "$repo_root/deploy/k8s/namespace.yaml" \
    -f "$repo_root/deploy/k8s/pvc.yaml" \
    -f "$repo_root/deploy/k8s/networkpolicy.yaml" \
    -f "$repo_root/deploy/k8s/deployment.yaml"

kubectl -n uzelok rollout status deploy/uzelok --timeout=120s
