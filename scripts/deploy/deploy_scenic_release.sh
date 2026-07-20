#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  deploy_scenic_release.sh --asset <path-to-scenic-release-tar-gz> --scoring-version <expected-version>

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
asset_dir="$(cd -- "$(dirname -- "$ASSET_PATH")" && pwd -P)"
ASSET_PATH="$asset_dir/$(basename -- "$ASSET_PATH")"
if ! printf '%s' "$EXPECTED_SCORING_VERSION" | grep -Eq '^3\.7([._+-][A-Za-z0-9][A-Za-z0-9._+-]*)?$'; then
  echo "--scoring-version is required and must identify a signed 3.7 release." >&2
  exit 1
fi

MOODRIDE_DIR=${MOODRIDE_DIR:-/opt/moodride}
COMPOSE_FILE=${COMPOSE_FILE:-docker-compose.prod.yml}
ENV_FILE=${ENV_FILE:-.env.prod}
DRAIN_TIMEOUT_SECONDS=${DRAIN_TIMEOUT_SECONDS:-1800}
DRAIN_POLL_SECONDS=${DRAIN_POLL_SECONDS:-5}

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

if [ -z "${POSTGRES_DB:-}" ] || [ -z "${POSTGRES_USER:-}" ] || [ -z "${REDIS_PASSWORD:-}" ]; then
  echo "POSTGRES_DB, POSTGRES_USER, and REDIS_PASSWORD must be set in $ENV_FILE" >&2
  exit 1
fi
for value_name in DRAIN_TIMEOUT_SECONDS DRAIN_POLL_SECONDS; do
  eval "value=\${$value_name}"
  case "$value" in
    ''|*[!0-9]*|0)
      echo "$value_name must be a positive integer." >&2
      exit 1
      ;;
  esac
done

get_env_var() {
  local key="$1"
  grep -E "^${key}=" "$ENV_FILE" | tail -n 1 | cut -d'=' -f2- || true
}


compose_env() {
  docker compose --project-directory "$MOODRIDE_DIR" -f "$COMPOSE_FILE" --env-file "$ENV_FILE" "$@"
}


service_running() {
  local service="$1"
  local container_id
  container_id="$(compose_env ps -q "$service")"
  [ -n "$container_id" ] \
    && [ "$(docker inspect --format '{{.State.Running}}' "$container_id")" = "true" ]
}


active_job_count() {
  compose_env exec -T postgres \
    psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
      --no-psqlrc --quiet --tuples-only --no-align --set ON_ERROR_STOP=1 \
      --command "SELECT COUNT(*) FROM route_jobs WHERE status IN ('QUEUED', 'PROCESSING', 'PRIMARY_READY');" \
    | tr -d '[:space:]'
}


wait_for_worker_drain() {
  local deadline count
  deadline=$((SECONDS + DRAIN_TIMEOUT_SECONDS))
  while :; do
    count="$(active_job_count)"
    case "$count" in
      ''|*[!0-9]*)
        echo "Could not establish the active route-job count during scenic drain." >&2
        return 1
        ;;
    esac
    if [ "$count" -eq 0 ]; then
      return 0
    fi
    if [ "$SECONDS" -ge "$deadline" ]; then
      echo "Timed out draining $count active route jobs before scenic cutover." >&2
      return 1
    fi
    sleep "$DRAIN_POLL_SECONDS"
  done
}


evict_scenic_anchor_cache_namespaces() {
  compose_env exec -T -e REDISCLI_AUTH="$REDIS_PASSWORD" redis sh -ceu '
    redis-cli PING | grep -Fx PONG >/dev/null
    for pattern in \
      "scenicTiles::*" \
      "scenic:tile:*" \
      "roadSegments::*" \
      "segment:anchor:*"; do
      redis-cli --scan --pattern "$pattern" |
        xargs -r -n 500 redis-cli UNLINK >/dev/null
    done
  '
}

set_env_var_atomically() {
  local key="$1"
  local value="$2"
  local temp="${ENV_FILE}.scenic.tmp"
  cp -p "$ENV_FILE" "$temp"
  if grep -q "^${key}=" "$temp"; then
    sed -i "s|^${key}=.*|${key}=${value}|" "$temp"
  else
    printf '%s=%s\n' "$key" "$value" >> "$temp"
  fi
  chmod 600 "$temp"
  mv "$temp" "$ENV_FILE"
}

ROAD_REVISION_BEFORE="$(get_env_var MOODRIDE_ROAD_DATASET_REVISION)"
ROAD_FINGERPRINT_BEFORE="$(get_env_var MOODRIDE_ROAD_DATASET_FINGERPRINT)"
ROAD_SCHEMA_BEFORE="$(get_env_var MOODRIDE_ROAD_ANCHOR_CACHE_SCHEMA)"
for identity in "$ROAD_REVISION_BEFORE" "$ROAD_FINGERPRINT_BEFORE" "$ROAD_SCHEMA_BEFORE"; do
  [ -n "$identity" ] || {
    echo "Road dataset revision, fingerprint, and anchor schema must be persisted before scenic cutover." >&2
    exit 1
  }
done
printf '%s' "$ROAD_FINGERPRINT_BEFORE" | grep -Eq '^[0-9a-f]{64}$' || {
  echo "Persisted road dataset fingerprint is not a sha256 value." >&2
  exit 1
}

release_root="$PWD/.deploy/scenic-releases"
mkdir -p "$release_root"
extract_dir="$(mktemp -d "$release_root/scenic-release.XXXXXXXX")"
cleanup_extract_dir() {
  if [ -n "${extract_dir:-}" ] && [ -d "$extract_dir" ]; then
    rm -rf -- "$extract_dir"
  fi
}
trap cleanup_extract_dir EXIT

echo "Extracting scenic release: $ASSET_PATH"
tar -xzf "$ASSET_PATH" -C "$extract_dir"

csv_file="$extract_dir/scenic_score_tiles_updates.csv"
if [ ! -f "$csv_file" ]; then
  echo "Missing required file in scenic asset: scenic_score_tiles_updates.csv" >&2
  exit 1
fi

header="$(head -n 1 "$csv_file" | tr -d '\r')"
for required_column in \
  h3_index scenic_score water_score green_score elevation_score solitude_score \
  curve_score poi_score park_score overture_poi_score building_density_score \
  darkness_score urban_penalty_score road_stress_score natural_land_use \
  elevation_variance last_scored scoring_version water_visibility_score \
  water_crossing_score coastal_road_score tree_canopy_score scenic_poi_score \
  viewpoint_score bridge_coastal_score; do
  printf '%s\n' "$header" | tr ',' '\n' | tr -d '"' | grep -Fx "$required_column" >/dev/null || {
    echo "Scenic 3.7 asset is missing required column: $required_column" >&2
    exit 1
  }
done

echo "Copying scenic CSV into postgres container"
docker compose --project-directory "$MOODRIDE_DIR" -f "$COMPOSE_FILE" --env-file "$ENV_FILE" cp "$csv_file" postgres:/tmp/scenic_score_tiles_updates.csv

for service in caddy route-api route-worker postgres redis; do
  service_running "$service" || {
    echo "Required pre-cutover service is not running: $service" >&2
    exit 1
  }
done

echo "Closing public ingress while the old route-api and worker drain."
compose_env stop caddy
wait_for_worker_drain
echo "Stopping the old route-api and drained route-worker before changing scenic identity."
compose_env stop route-api route-worker

echo "Applying scenic score updates"
docker compose --project-directory "$MOODRIDE_DIR" -f "$COMPOSE_FILE" --env-file "$ENV_FILE" exec -T postgres \
  psql -v ON_ERROR_STOP=1 -v expected_scoring_version="$EXPECTED_SCORING_VERSION" \
       -U "$POSTGRES_USER" -d "$POSTGRES_DB" <<'SQL'
SELECT set_config('moodride.expected_scoring_version', :'expected_scoring_version', false);
BEGIN;
ALTER TABLE public.scenic_score_tiles
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
        FROM pg_temp.scenic_release_updates
        WHERE scoring_version IS DISTINCT FROM
              current_setting('moodride.expected_scoring_version')
    )
END AS mismatched_count
\gset

SELECT (:mismatched_count::integer <> 0) AS has_mismatched_rows
\gset

\if :has_mismatched_rows
\echo Scenic release scoring_version mismatch detected.
\echo Expected: :expected_scoring_version
\echo Mismatched rows: :mismatched_count
SELECT 1 / 0;
\endif

SELECT (
    NOT EXISTS (SELECT 1 FROM pg_temp.scenic_release_updates)
    OR EXISTS (
        SELECT 1
        FROM pg_temp.scenic_release_updates updates
        LEFT JOIN public.scenic_score_tiles current_tiles USING (h3_index)
        WHERE current_tiles.h3_index IS NULL
    )
    OR EXISTS (
        SELECT 1
        FROM public.scenic_score_tiles current_tiles
        LEFT JOIN pg_temp.scenic_release_updates updates USING (h3_index)
        WHERE updates.h3_index IS NULL
    )
) AS has_incomplete_coverage
\gset

\if :has_incomplete_coverage
\echo Scenic 3.7 release does not cover exactly the complete production tile set.
SELECT 1 / 0;
\endif

WITH updated AS (
    UPDATE public.scenic_score_tiles sst
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
        scoring_version = u.scoring_version
    FROM pg_temp.scenic_release_updates u
    WHERE sst.h3_index = u.h3_index
    RETURNING 1
)
SELECT (SELECT COUNT(*) FROM updated) = (SELECT COUNT(*) FROM pg_temp.scenic_release_updates)
       AS updated_complete_coverage
\gset

\if :updated_complete_coverage
\else
\echo Scenic 3.7 update did not mutate every release tile.
SELECT 1 / 0;
\endif

DO $$
DECLARE
    expected_version text := current_setting('moodride.expected_scoring_version');
BEGIN
    IF NOT EXISTS (SELECT 1 FROM public.scenic_score_tiles)
       OR EXISTS (
           SELECT 1 FROM public.scenic_score_tiles
           WHERE scoring_version IS DISTINCT FROM expected_version
       )
       OR (SELECT COUNT(DISTINCT btrim(scoring_version)) FROM public.scenic_score_tiles) <> 1
       OR (SELECT MIN(btrim(scoring_version)) FROM public.scenic_score_tiles) <> expected_version THEN
        RAISE EXCEPTION 'Scenic release did not produce complete coverage for signed version %',
            expected_version;
    END IF;
END $$;
COMMIT;
SQL

docker compose --project-directory "$MOODRIDE_DIR" -f "$COMPOSE_FILE" --env-file "$ENV_FILE" exec -T postgres \
  sh -lc "rm -f /tmp/scenic_score_tiles_updates.csv"

set_env_var_atomically MOODRIDE_SCENIC_SCORING_VERSION "$EXPECTED_SCORING_VERSION"
[ "$(get_env_var MOODRIDE_ROAD_DATASET_REVISION)" = "$ROAD_REVISION_BEFORE" ] \
  || { echo "Scenic release unexpectedly changed the road dataset revision." >&2; exit 1; }
[ "$(get_env_var MOODRIDE_ROAD_DATASET_FINGERPRINT)" = "$ROAD_FINGERPRINT_BEFORE" ] \
  || { echo "Scenic release unexpectedly changed the road dataset fingerprint." >&2; exit 1; }
[ "$(get_env_var MOODRIDE_ROAD_ANCHOR_CACHE_SCHEMA)" = "$ROAD_SCHEMA_BEFORE" ] \
  || { echo "Scenic release unexpectedly changed the road anchor cache schema." >&2; exit 1; }

echo "Clearing versioned and legacy scenic and road-anchor Redis namespaces"
evict_scenic_anchor_cache_namespaces

echo "Starting route-api and route-worker behind closed ingress with the signed scenic version"
compose_env up -d --no-deps --force-recreate route-api route-worker
sleep 5
for _ in $(seq 1 60); do
  if service_running route-api && service_running route-worker; then
    break
  fi
  sleep 1
done
service_running route-api || { echo "Updated route-api failed to start." >&2; exit 1; }
service_running route-worker || { echo "Updated route-worker failed to start." >&2; exit 1; }
for service in route-api route-worker; do
  container_id="$(compose_env ps -q "$service")"
  docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$container_id" \
    | grep -Fx "MOODRIDE_SCENIC_SCORING_VERSION=${EXPECTED_SCORING_VERSION}" >/dev/null \
    || { echo "$service did not start with the accepted scenic scoring identity." >&2; exit 1; }
done
compose_env up -d --no-deps caddy
service_running caddy || { echo "Ingress failed to reopen after scenic validation." >&2; exit 1; }

echo "Scenic release deployed successfully."
