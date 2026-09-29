#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
compose=(docker compose -f "$repo_root/deploy/compose.yaml" -p deploy)

for service in postgres nginx; do
  container_id=$("${compose[@]}" ps --all --quiet "$service")
  if [[ -z "$container_id" ]]; then
    printf '%s: 未创建\n' "$service"
    continue
  fi

  state=$(docker inspect --format '{{.State.Status}}{{if .State.Health}} ({{.State.Health.Status}}){{end}}' "$container_id")
  printf '%s: %s\n' "$service" "$state"
done
