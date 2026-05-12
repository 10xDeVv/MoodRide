#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  deploy_scenic_release.sh --asset <path-to-scenic-release-tar-gz> [--scoring-version <expected-version>]

Environment variables:
  MOODRIDE_DIR (default: /opt/moodride)
  COMPOSE_FILE (default: docker-compose.prod.yml)
  ENV_FILE     (default: .env.prod)
EOF
}

ASSET_PATH=""
EXPECTED_SCORING_VERSION=""

while [ "$#" -gt 0 ]; do
  case "$1" in
    --asset)
      ASSET_PATH="${2:-}"
      shift 2
      ;;
    --scoring-version)
      EXPECTED_SCORING_VERSION="${2:-}"
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

if [ -z "$ASSET_PATH" ]; then
  echo "--asset is required." >&2
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

set -a
source "$ENV_FILE"
set +a

if [ -z "${POSTGRES_DB:-}" ] || [ -z "${POSTGRES_USER:-}" ]; then
  echo "POSTGRES_DB and POSTGRES_USER must be set in $ENV_FILE" >&2
  exit 1
fi

mkdir -p .deploy/scenic-releases
release_id="$(date -u +"%Y%m%dT%H%M%SZ")"
extract_dir=".deploy/scenic-releases/$release_id"
mkdir -p "$extract_dir"

echo "Extracting scenic release: $ASSET_PATH"
tar -xzf "$ASSET_PATH" -C "$extract_dir"

csv_file="$extract_dir/scenic_score_tiles_updates.csv"
if [ ! -f "$csv_file" ]; then
  echo "Missing required file in scenic asset: scenic_score_tiles_updates.csv" >&2
  exit 1
fi

echo "Copying scenic CSV into postgres container"
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" cp "$csv_file" postgres:/tmp/scenic_score_tiles_updates.csv

echo "Applying scenic score updates"
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" exec -T postgres \
  psql -v ON_ERROR_STOP=1 -v expected_scoring_version="$EXPECTED_SCORING_VERSION" \
       -U "$POSTGRES_USER" -d "$POSTGRES_DB" <<'SQL'
CREATE TEMP TABLE scenic_release_updates (
    h3_index VARCHAR(15) PRIMARY KEY,
    scenic_score DOUBLE PRECISION,
    water_score DOUBLE PRECISION,
    green_score DOUBLE PRECISION,
    elevation_score DOUBLE PRECISION,
    solitude_score DOUBLE PRECISION,
    curve_score DOUBLE PRECISION,
    poi_score DOUBLE PRECISION,
    natural_land_use DOUBLE PRECISION,
    elevation_variance DOUBLE PRECISION,
    last_scored TIMESTAMP,
    scoring_version VARCHAR(80)
);

COPY scenic_release_updates (
    h3_index,
    scenic_score,
    water_score,
    green_score,
    elevation_score,
    solitude_score,
    curve_score,
    poi_score,
    natural_land_use,
    elevation_variance,
    last_scored,
    scoring_version
) FROM '/tmp/scenic_score_tiles_updates.csv' WITH (FORMAT csv, HEADER true);

SELECT CASE
    WHEN nullif(:'expected_scoring_version', '') IS NULL THEN 0
    ELSE (
        SELECT COUNT(*)
        FROM scenic_release_updates
        WHERE scoring_version <> :'expected_scoring_version'
    )
END AS mismatched_count
\gset

\if :mismatched_count != 0
\echo Scenic release scoring_version mismatch detected.
\echo Expected: :expected_scoring_version
\echo Mismatched rows: :mismatched_count
\quit 3
\endif

WITH updated AS (
    UPDATE scenic_score_tiles sst
    SET scenic_score = u.scenic_score,
        water_score = u.water_score,
        green_score = u.green_score,
        elevation_score = u.elevation_score,
        solitude_score = u.solitude_score,
        curve_score = u.curve_score,
        poi_score = u.poi_score,
        natural_land_use = u.natural_land_use,
        elevation_variance = u.elevation_variance,
        last_scored = COALESCE(u.last_scored, CURRENT_TIMESTAMP),
        scoring_version = COALESCE(u.scoring_version, sst.scoring_version)
    FROM scenic_release_updates u
    WHERE sst.h3_index = u.h3_index
    RETURNING 1
)
SELECT COUNT(*) AS updated_tiles FROM updated;
SQL

docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" exec -T postgres \
  sh -lc "rm -f /tmp/scenic_score_tiles_updates.csv"

echo "Restarting route-api and route-worker to clear hot caches"
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" up -d route-api route-worker

echo "Scenic release deployed successfully."
