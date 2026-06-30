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

if ! head -n 1 "$csv_file" | grep -q 'water_visibility_score'; then
  compatible_csv="$extract_dir/scenic_score_tiles_updates.v33-compatible.csv"
  awk 'NR == 1 { print $0 ",water_visibility_score,water_crossing_score,coastal_road_score,tree_canopy_score,scenic_poi_score,viewpoint_score,bridge_coastal_score"; next } { print $0 ",0.0,0.0,0.0,0.0,0.0,0.0,0.0" }' "$csv_file" > "$compatible_csv"
  csv_file="$compatible_csv"
elif ! head -n 1 "$csv_file" | grep -q 'tree_canopy_score'; then
  compatible_csv="$extract_dir/scenic_score_tiles_updates.v34-compatible.csv"
  awk 'NR == 1 { print $0 ",tree_canopy_score,scenic_poi_score,viewpoint_score,bridge_coastal_score"; next } { print $0 ",0.0,0.0,0.0,0.0" }' "$csv_file" > "$compatible_csv"
  csv_file="$compatible_csv"
elif ! head -n 1 "$csv_file" | grep -q 'scenic_poi_score'; then
  compatible_csv="$extract_dir/scenic_score_tiles_updates.v35-compatible.csv"
  awk 'NR == 1 { print $0 ",scenic_poi_score,viewpoint_score,bridge_coastal_score"; next } { print $0 ",0.0,0.0,0.0" }' "$csv_file" > "$compatible_csv"
  csv_file="$compatible_csv"
elif ! head -n 1 "$csv_file" | grep -q 'viewpoint_score'; then
  compatible_csv="$extract_dir/scenic_score_tiles_updates.v36-compatible.csv"
  awk 'NR == 1 { print $0 ",viewpoint_score,bridge_coastal_score"; next } { print $0 ",0.0,0.0" }' "$csv_file" > "$compatible_csv"
  csv_file="$compatible_csv"
elif ! head -n 1 "$csv_file" | grep -q 'bridge_coastal_score'; then
  compatible_csv="$extract_dir/scenic_score_tiles_updates.v37-compatible.csv"
  awk 'NR == 1 { print $0 ",bridge_coastal_score"; next } { print $0 ",0.0" }' "$csv_file" > "$compatible_csv"
  csv_file="$compatible_csv"
fi

echo "Copying scenic CSV into postgres container"
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" cp "$csv_file" postgres:/tmp/scenic_score_tiles_updates.csv

echo "Applying scenic score updates"
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" exec -T postgres \
  psql -v ON_ERROR_STOP=1 -v expected_scoring_version="$EXPECTED_SCORING_VERSION" \
       -U "$POSTGRES_USER" -d "$POSTGRES_DB" <<'SQL'
ALTER TABLE scenic_score_tiles
    ADD COLUMN IF NOT EXISTS overture_poi_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS building_density_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS darkness_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS urban_penalty_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS road_stress_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS water_visibility_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS water_crossing_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS coastal_road_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS tree_canopy_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS scenic_poi_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS viewpoint_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS bridge_coastal_score DOUBLE PRECISION NOT NULL DEFAULT 0.0;

CREATE TEMP TABLE scenic_release_updates (
    h3_index VARCHAR(15) PRIMARY KEY,
    scenic_score DOUBLE PRECISION,
    water_score DOUBLE PRECISION,
    green_score DOUBLE PRECISION,
    elevation_score DOUBLE PRECISION,
    solitude_score DOUBLE PRECISION,
    curve_score DOUBLE PRECISION,
    poi_score DOUBLE PRECISION,
    park_score DOUBLE PRECISION,
    overture_poi_score DOUBLE PRECISION,
    building_density_score DOUBLE PRECISION,
    darkness_score DOUBLE PRECISION,
    urban_penalty_score DOUBLE PRECISION,
    road_stress_score DOUBLE PRECISION,
    natural_land_use DOUBLE PRECISION,
    elevation_variance DOUBLE PRECISION,
    last_scored TIMESTAMP,
    scoring_version VARCHAR(80),
    water_visibility_score DOUBLE PRECISION,
    water_crossing_score DOUBLE PRECISION,
    coastal_road_score DOUBLE PRECISION,
    tree_canopy_score DOUBLE PRECISION,
    scenic_poi_score DOUBLE PRECISION,
    viewpoint_score DOUBLE PRECISION,
    bridge_coastal_score DOUBLE PRECISION
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
    park_score,
    overture_poi_score,
    building_density_score,
    darkness_score,
    urban_penalty_score,
    road_stress_score,
    natural_land_use,
    elevation_variance,
    last_scored,
    scoring_version,
    water_visibility_score,
    water_crossing_score,
    coastal_road_score,
    tree_canopy_score,
    scenic_poi_score,
    viewpoint_score,
    bridge_coastal_score
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

SELECT (:mismatched_count::integer <> 0) AS has_mismatched_rows
\gset

\if :has_mismatched_rows
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
        park_score = COALESCE(u.park_score, sst.park_score),
        overture_poi_score = COALESCE(u.overture_poi_score, sst.overture_poi_score),
        building_density_score = COALESCE(u.building_density_score, sst.building_density_score),
        darkness_score = COALESCE(u.darkness_score, sst.darkness_score),
        urban_penalty_score = COALESCE(u.urban_penalty_score, sst.urban_penalty_score),
        road_stress_score = COALESCE(u.road_stress_score, sst.road_stress_score),
        water_visibility_score = COALESCE(u.water_visibility_score, sst.water_visibility_score),
        water_crossing_score = COALESCE(u.water_crossing_score, sst.water_crossing_score),
        coastal_road_score = COALESCE(u.coastal_road_score, sst.coastal_road_score),
        tree_canopy_score = COALESCE(u.tree_canopy_score, sst.tree_canopy_score),
        scenic_poi_score = COALESCE(u.scenic_poi_score, sst.scenic_poi_score),
        viewpoint_score = COALESCE(u.viewpoint_score, sst.viewpoint_score),
        bridge_coastal_score = COALESCE(u.bridge_coastal_score, sst.bridge_coastal_score),
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
