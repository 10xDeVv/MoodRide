#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  rollback_prod.sh [--tag <image-tag>]

If --tag is omitted, the script rolls back to the previous deployment env snapshot.

Environment variables:
  MOODRIDE_DIR (default: /opt/moodride)
  COMPOSE_FILE (default: docker-compose.prod.yml)
  ENV_FILE     (default: .env.prod)
EOF
}

require_file() {
  local file="$1"
  if [ ! -f "$file" ]; then
    echo "Required file not found: $file" >&2
    exit 1
  fi
}

set_env_var() {
  local key="$1"
  local value="$2"
  local file="$3"
  if grep -q "^${key}=" "$file"; then
    sed -i "s|^${key}=.*|${key}=${value}|" "$file"
  else
    printf '%s=%s\n' "$key" "$value" >> "$file"
  fi
}

ROLLBACK_TAG=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --tag)
      ROLLBACK_TAG="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

MOODRIDE_DIR=${MOODRIDE_DIR:-/opt/moodride}
COMPOSE_FILE=${COMPOSE_FILE:-docker-compose.prod.yml}
ENV_FILE=${ENV_FILE:-.env.prod}

cd "$MOODRIDE_DIR"
require_file "$COMPOSE_FILE"
require_file "$ENV_FILE"
mkdir -p .deploy/releases

if [ -n "$ROLLBACK_TAG" ]; then
  set_env_var "IMAGE_TAG" "$ROLLBACK_TAG" "$ENV_FILE"
  echo "Rolling back to explicit image tag: $ROLLBACK_TAG"
else
  latest_backup="$(ls -1t .deploy/releases/*.env.backup 2>/dev/null | head -n 1 || true)"
  if [ -z "$latest_backup" ]; then
    echo "No env backup found in .deploy/releases and no --tag provided." >&2
    exit 1
  fi
  echo "Restoring previous env snapshot: $latest_backup"
  cp "$latest_backup" "$ENV_FILE"
fi

docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" pull
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" up -d --remove-orphans

echo "Rollback completed."
