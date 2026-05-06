#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  deploy_data_release.sh --asset <path-to-data-tar-gz> --dataset <dataset-basename>

Environment variables:
  MOODRIDE_DIR (default: /opt/moodride)
  COMPOSE_FILE (default: docker-compose.prod.yml)
  ENV_FILE     (default: .env.prod)
EOF
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

ASSET_PATH=""
DATASET_BASENAME=""

while [ "$#" -gt 0 ]; do
  case "$1" in
    --asset)
      ASSET_PATH="${2:-}"
      shift 2
      ;;
    --dataset)
      DATASET_BASENAME="${2:-}"
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

if [ -z "$ASSET_PATH" ] || [ -z "$DATASET_BASENAME" ]; then
  echo "--asset and --dataset are required." >&2
  usage
  exit 1
fi

if [ ! -f "$ASSET_PATH" ]; then
  echo "Asset not found: $ASSET_PATH" >&2
  exit 1
fi

MOODRIDE_DIR=${MOODRIDE_DIR:-/opt/moodride}
COMPOSE_FILE=${COMPOSE_FILE:-docker-compose.prod.yml}
ENV_FILE=${ENV_FILE:-.env.prod}

cd "$MOODRIDE_DIR"
if [ ! -f "$COMPOSE_FILE" ]; then
  echo "Compose file not found: $COMPOSE_FILE" >&2
  exit 1
fi
if [ ! -f "$ENV_FILE" ]; then
  echo "Env file not found: $ENV_FILE" >&2
  exit 1
fi
mkdir -p data/osrm .deploy/data-releases

release_id="$(date -u +"%Y%m%dT%H%M%SZ")-${DATASET_BASENAME}"
extract_dir=".deploy/data-releases/$release_id"
mkdir -p "$extract_dir"

echo "Extracting data release: $ASSET_PATH"
tar -xzf "$ASSET_PATH" -C "$extract_dir"

if [ ! -f "$extract_dir/${DATASET_BASENAME}.osrm" ]; then
  echo "Missing required file in asset: ${DATASET_BASENAME}.osrm" >&2
  exit 1
fi

echo "Copying dataset files into /opt/moodride/data/osrm"
cp "$extract_dir/${DATASET_BASENAME}.osrm"* data/osrm/

set_env_var "OSRM_DATASET_BASENAME" "$DATASET_BASENAME" "$ENV_FILE"

echo "Restarting OSRM and route-worker with dataset ${DATASET_BASENAME}"
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" up -d osrm route-worker

echo "Data release deployed successfully."
