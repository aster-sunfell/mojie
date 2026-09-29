#!/bin/sh
set -eu

repo_root=$(CDPATH= cd "$(dirname "$0")/.." && pwd)
env_file="$repo_root/deploy/.env"

if [ ! -f "$env_file" ]; then
  printf 'Missing %s\n' "$env_file" >&2
  exit 1
fi

cd "$repo_root/backend"
mvn -B -ntp -DskipTests package

set -a
. "$env_file"
set +a

exec java -jar target/mojie-agent-core-0.1.0-SNAPSHOT-exec.jar
