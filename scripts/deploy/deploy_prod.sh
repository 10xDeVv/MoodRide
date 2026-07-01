#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  deploy_prod.sh --tag <image-tag> [--namespace <ghcr-namespace>]

Environment variables:
  MOODRIDE_DIR    (default: /opt/moodride)
  COMPOSE_FILE    (default: docker-compose.prod.yml)
  ENV_FILE        (default: .env.prod)
  HEALTHCHECK_URL (default: https://usewayward.app/api/scenic-regions?lat=45.94&lng=-66.63&radius=1)
  HEALTHCHECK_TIMEOUT_SECONDS (default: 360)
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

get_env_var() {
  local key="$1"
  local file="$2"
  grep -E "^${key}=" "$file" | tail -n 1 | cut -d'=' -f2- || true
}

run_healthcheck() {
  local url="$1"
  local timeout_seconds="$2"
  local start_ts
  start_ts=$(date +%s)

  while true; do
    local code=""
    code=$(curl -s -o /dev/null -w "%{http_code}" "$url" || true)
    if [ "$code" = "200" ]; then
      echo "Healthcheck passed: $url"
      return 0
    fi

    local now
    now=$(date +%s)
    if [ $((now - start_ts)) -ge "$timeout_seconds" ]; then
      echo "Healthcheck failed after ${timeout_seconds}s (last HTTP ${code:-n/a}): $url" >&2
      return 1
    fi

    sleep 5
  done
}

IMAGE_TAG=""
GHCR_NAMESPACE=""

while [ "$#" -gt 0 ]; do
  case "$1" in
    --tag)
      IMAGE_TAG="${2:-}"
      shift 2
      ;;
    --namespace)
      GHCR_NAMESPACE="${2:-}"
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

if [ -z "$IMAGE_TAG" ]; then
  echo "--tag is required." >&2
  usage
  exit 1
fi

MOODRIDE_DIR=${MOODRIDE_DIR:-/opt/moodride}
COMPOSE_FILE=${COMPOSE_FILE:-docker-compose.prod.yml}
ENV_FILE=${ENV_FILE:-.env.prod}
HEALTHCHECK_URL=${HEALTHCHECK_URL:-https://usewayward.app/api/scenic-regions?lat=45.94&lng=-66.63&radius=1}
HEALTHCHECK_TIMEOUT_SECONDS=${HEALTHCHECK_TIMEOUT_SECONDS:-360}

cd "$MOODRIDE_DIR"
require_file "$COMPOSE_FILE"
require_file "$ENV_FILE"

mkdir -p .deploy/releases
release_id="$(date -u +"%Y%m%dT%H%M%SZ")-${IMAGE_TAG}"
backup_env=".deploy/releases/${release_id}.env.backup"
cp "$ENV_FILE" "$backup_env"

previous_tag="$(get_env_var IMAGE_TAG "$ENV_FILE")"
if [ -z "$previous_tag" ]; then
  previous_tag="unknown"
fi

set_env_var "IMAGE_TAG" "$IMAGE_TAG" "$ENV_FILE"
if [ -n "$GHCR_NAMESPACE" ]; then
  set_env_var "GHCR_NAMESPACE" "$GHCR_NAMESPACE" "$ENV_FILE"
fi

echo "Pulling images for tag: $IMAGE_TAG"
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" pull

echo "Launching stack"
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" up -d --remove-orphans
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" up -d --force-recreate caddy

if ! run_healthcheck "$HEALTHCHECK_URL" "$HEALTHCHECK_TIMEOUT_SECONDS"; then
  echo "Rolling back to previous env snapshot: $backup_env"
  cp "$backup_env" "$ENV_FILE"
  docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" pull
  docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" up -d --remove-orphans
  docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" up -d --force-recreate caddy
  echo "Rollback completed. Previous image tag: $previous_tag" >&2
  exit 1
fi

echo "Deployment completed with image tag: $IMAGE_TAG"
