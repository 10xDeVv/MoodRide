#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  cat <<'EOF'
Usage:
  deploy_scenic_release.sh --asset <path-to-scenic-release-tar-gz> \
    --asset-sha256 <published-sha256> --scoring-version <expected-version>

Environment variables:
  MOODRIDE_DIR                   (default: /opt/moodride)
  COMPOSE_FILE                   (default: docker-compose.prod.yml)
  ENV_FILE                       (default: .env.prod)
  HEALTHCHECK_TIMEOUT_SECONDS    (default: 180)
  ROUTE_SMOKE_TIMEOUT_SECONDS    (default: 300)
EOF
}

ASSET_PATH=""
ASSET_SHA256=""
EXPECTED_SCORING_VERSION=""

while [ "$#" -gt 0 ]; do
  case "$1" in
    --asset)
      [ "$#" -ge 2 ] || { echo "--asset requires a value." >&2; exit 1; }
      ASSET_PATH="$2"
      shift 2
      ;;
    --asset-sha256)
      [ "$#" -ge 2 ] || { echo "--asset-sha256 requires a value." >&2; exit 1; }
      ASSET_SHA256="$2"
      shift 2
      ;;
    --scoring-version)
      [ "$#" -ge 2 ] || { echo "--scoring-version requires a value." >&2; exit 1; }
      EXPECTED_SCORING_VERSION="$2"
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
if ! printf '%s' "$ASSET_SHA256" | grep -Eq '^[0-9a-f]{64}$'; then
  echo "--asset-sha256 is required and must be 64 lowercase hexadecimal characters." >&2
  exit 1
fi
actual_asset_sha256="$(sha256sum "$ASSET_PATH")"
actual_asset_sha256="${actual_asset_sha256%% *}"
if [ "$actual_asset_sha256" != "$ASSET_SHA256" ]; then
  echo "Scenic release asset checksum does not match the published SHA-256 sidecar." >&2
  exit 1
fi
case "$EXPECTED_SCORING_VERSION" in
  *$'\r'*|*$'\n'*)
    echo "--scoring-version must be a single line." >&2
    exit 1
    ;;
esac
if ! printf '%s' "$EXPECTED_SCORING_VERSION" | grep -Eq '^3\.7([._+-][A-Za-z0-9][A-Za-z0-9._+-]{0,45})?$'; then
  echo "--scoring-version is required and must identify a signed 3.7 release." >&2
  exit 1
fi

MOODRIDE_DIR=${MOODRIDE_DIR:-/opt/moodride}
COMPOSE_FILE=${COMPOSE_FILE:-docker-compose.prod.yml}
ENV_FILE=${ENV_FILE:-.env.prod}
DRAIN_TIMEOUT_SECONDS=${DRAIN_TIMEOUT_SECONDS:-1800}
DRAIN_POLL_SECONDS=${DRAIN_POLL_SECONDS:-5}
HEALTHCHECK_TIMEOUT_SECONDS=${HEALTHCHECK_TIMEOUT_SECONDS:-180}
HEALTHCHECK_POLL_SECONDS=${HEALTHCHECK_POLL_SECONDS:-5}
ROUTE_SMOKE_TIMEOUT_SECONDS=${ROUTE_SMOKE_TIMEOUT_SECONDS:-300}

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
for value_name in \
  DRAIN_TIMEOUT_SECONDS DRAIN_POLL_SECONDS HEALTHCHECK_TIMEOUT_SECONDS \
  HEALTHCHECK_POLL_SECONDS ROUTE_SMOKE_TIMEOUT_SECONDS; do
  value="${!value_name}"
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

psql_query() {
  local sql="$1"
  compose_env exec -T postgres \
    psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
      --no-psqlrc --quiet --tuples-only --no-align --set ON_ERROR_STOP=1 \
      --command "$sql"
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

run_internal_healthcheck() {
  local name="$1"
  local service="$2"
  local url="$3"
  local deadline
  deadline=$((SECONDS + HEALTHCHECK_TIMEOUT_SECONDS))
  while :; do
    if compose_env exec -T "$service" \
        wget -q -T 20 -O /dev/null "$url" >/dev/null 2>&1; then
      echo "$name readiness passed."
      return 0
    fi
    if [ "$SECONDS" -ge "$deadline" ]; then
      echo "$name readiness failed after ${HEALTHCHECK_TIMEOUT_SECONDS}s." >&2
      return 1
    fi
    sleep "$HEALTHCHECK_POLL_SECONDS"
  done
}

SCENIC_SYNTHETIC_USER_ID="00000000-0000-4000-8000-000000000037"
SCENIC_SMOKE_JOB_ID=""

run_route_smoke_inner() {
  local payload response status deadline saw_primary primary_ready_recorded route_count
  psql_query \
    "DELETE FROM route_jobs WHERE user_id = '${SCENIC_SYNTHETIC_USER_ID}'::uuid;" \
    >/dev/null
  payload="{\"userId\":\"${SCENIC_SYNTHETIC_USER_ID}\",\"lat\":45.9636,\"lng\":-66.6431,\"timeBudgetMinutes\":30,\"vibes\":[\"countryside\"],\"routeMode\":\"drive\"}"
  if ! response="$(compose_env exec -T route-api \
      wget -q -T 30 -O - --header='Content-Type: application/json' \
        --post-data="$payload" http://127.0.0.1:8080/api/routes 2>/dev/null)"; then
    echo "Focused scenic route smoke was not accepted by route-api." >&2
    return 1
  fi
  SCENIC_SMOKE_JOB_ID="$(psql_query \
    "SELECT id FROM route_jobs
     WHERE user_id = '${SCENIC_SYNTHETIC_USER_ID}'::uuid
     ORDER BY submitted_at DESC LIMIT 1;")"
  SCENIC_SMOKE_JOB_ID="$(printf '%s' "$SCENIC_SMOKE_JOB_ID" | tr -d '[:space:]')"
  printf '%s' "$SCENIC_SMOKE_JOB_ID" | grep -Eq '^[0-9a-fA-F-]{36}$' || {
    echo "Focused scenic route smoke did not persist a UUID job." >&2
    return 1
  }
  printf '%s' "$response" | grep -q '"jobId"' || {
    echo "Focused scenic route smoke response did not contain a jobId." >&2
    return 1
  }

  saw_primary=0
  deadline=$((SECONDS + ROUTE_SMOKE_TIMEOUT_SECONDS))
  while :; do
    status="$(psql_query \
      "SELECT status FROM route_jobs WHERE id = '${SCENIC_SMOKE_JOB_ID}'::uuid;")"
    status="$(printf '%s' "$status" | tr -d '[:space:]')"
    case "$status" in
      PRIMARY_READY)
        saw_primary=1
        ;;
      COMPLETED)
        break
        ;;
      QUEUED|PROCESSING)
        ;;
      FAILED|TIMEOUT|'')
        echo "Focused scenic route smoke ended without completion (status: ${status:-missing})." >&2
        return 1
        ;;
      *)
        echo "Focused scenic route smoke returned unexpected status: $status" >&2
        return 1
        ;;
    esac
    if [ "$SECONDS" -ge "$deadline" ]; then
      echo "Focused scenic route smoke timed out in status $status." >&2
      return 1
    fi
    sleep 1
  done

  if [ "$saw_primary" -ne 1 ]; then
    primary_ready_recorded="$(psql_query \
      "SELECT CASE WHEN primary_ready_at IS NOT NULL THEN 1 ELSE 0 END
       FROM route_jobs WHERE id = '${SCENIC_SMOKE_JOB_ID}'::uuid;")"
    primary_ready_recorded="$(printf '%s' "$primary_ready_recorded" | tr -d '[:space:]')"
    [ "$primary_ready_recorded" = "1" ] || {
      echo "Focused scenic route smoke did not prove progressive PRIMARY_READY publication." >&2
      return 1
    }
  fi
  route_count="$(psql_query \
    "SELECT COUNT(*) FROM routes WHERE job_id = '${SCENIC_SMOKE_JOB_ID}'::uuid;")"
  route_count="$(printf '%s' "$route_count" | tr -d '[:space:]')"
  case "$route_count" in
    ''|*[!0-9]*)
      echo "Focused scenic route smoke could not count persisted routes." >&2
      return 1
      ;;
  esac
  [ "$route_count" -gt 0 ] || {
    echo "Focused scenic route smoke completed without a persisted route." >&2
    return 1
  }
}

run_route_smoke() {
  SCENIC_SMOKE_JOB_ID=""
  run_route_smoke_inner || return 1
  psql_query \
    "DELETE FROM route_jobs
     WHERE id = '${SCENIC_SMOKE_JOB_ID}'::uuid
       AND user_id = '${SCENIC_SYNTHETIC_USER_ID}'::uuid;" \
    >/dev/null
  wait_for_worker_drain
  echo "Focused scenic route smoke proved PRIMARY_READY, completion, and a persisted route."
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

restore_scenic_snapshot() {
  compose_env cp "$SCENIC_SNAPSHOT" postgres:/tmp/scenic_score_tiles_rollback.csv \
    || return 1
  compose_env exec -T postgres \
    psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" <<'RESTORE_SQL'
BEGIN;
CREATE TEMP TABLE scenic_release_restore (
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
COPY scenic_release_restore (
    h3_index, scenic_score, water_score, green_score, elevation_score,
    solitude_score, curve_score, poi_score, park_score, overture_poi_score,
    building_density_score, darkness_score, urban_penalty_score,
    road_stress_score, natural_land_use, elevation_variance, last_scored,
    scoring_version, water_visibility_score, water_crossing_score,
    coastal_road_score, tree_canopy_score, scenic_poi_score, viewpoint_score,
    bridge_coastal_score
) FROM '/tmp/scenic_score_tiles_rollback.csv' WITH (FORMAT csv);

SELECT (
    NOT EXISTS (SELECT 1 FROM pg_temp.scenic_release_restore)
    OR EXISTS (
        SELECT 1 FROM pg_temp.scenic_release_restore restore
        LEFT JOIN public.scenic_score_tiles current_tiles USING (h3_index)
        WHERE current_tiles.h3_index IS NULL
    )
    OR EXISTS (
        SELECT 1 FROM public.scenic_score_tiles current_tiles
        LEFT JOIN pg_temp.scenic_release_restore restore USING (h3_index)
        WHERE restore.h3_index IS NULL
    )
) AS restore_coverage_invalid
\gset
\if :restore_coverage_invalid
\echo Scenic rollback snapshot no longer covers the exact production tile set.
SELECT 1 / 0;
\endif

WITH restored AS (
    UPDATE public.scenic_score_tiles sst
    SET scenic_score = restore.scenic_score,
        water_score = restore.water_score,
        green_score = restore.green_score,
        elevation_score = restore.elevation_score,
        solitude_score = restore.solitude_score,
        curve_score = restore.curve_score,
        poi_score = restore.poi_score,
        park_score = restore.park_score,
        overture_poi_score = restore.overture_poi_score,
        building_density_score = restore.building_density_score,
        darkness_score = restore.darkness_score,
        urban_penalty_score = restore.urban_penalty_score,
        road_stress_score = restore.road_stress_score,
        natural_land_use = restore.natural_land_use,
        elevation_variance = restore.elevation_variance,
        last_scored = restore.last_scored,
        scoring_version = restore.scoring_version,
        water_visibility_score = restore.water_visibility_score,
        water_crossing_score = restore.water_crossing_score,
        coastal_road_score = restore.coastal_road_score,
        tree_canopy_score = restore.tree_canopy_score,
        scenic_poi_score = restore.scenic_poi_score,
        viewpoint_score = restore.viewpoint_score,
        bridge_coastal_score = restore.bridge_coastal_score
    FROM pg_temp.scenic_release_restore restore
    WHERE sst.h3_index = restore.h3_index
    RETURNING 1
)
SELECT (SELECT COUNT(*) FROM restored) =
       (SELECT COUNT(*) FROM pg_temp.scenic_release_restore) AS restore_complete
\gset
\if :restore_complete
\else
\echo Scenic rollback did not restore every snapshotted tile.
SELECT 1 / 0;
\endif
COMMIT;
RESTORE_SQL
  compose_env exec -T postgres sh -ceu \
    'rm -f /tmp/scenic_score_tiles_rollback.csv /tmp/scenic_score_tiles_updates.csv' \
    || return 1
}

restore_previous_release() {
  local restored_env_temp="${ENV_FILE}.scenic.restore.tmp"
  local restored_scoring_version
  echo "Scenic promotion failed; closing ingress and restoring the previous release." >&2
  compose_env stop caddy >/dev/null 2>&1 || true
  cp -p "$PREDEPLOY_ENV" "$restored_env_temp" || return 1
  mv "$restored_env_temp" "$ENV_FILE" || return 1
  restored_scoring_version="$(get_env_var MOODRIDE_SCENIC_SCORING_VERSION)"
  if [ -n "$restored_scoring_version" ]; then
    export MOODRIDE_SCENIC_SCORING_VERSION="$restored_scoring_version"
  else
    unset MOODRIDE_SCENIC_SCORING_VERSION
  fi
  if [ "$DATABASE_MUTATION_ATTEMPTED" -eq 1 ]; then
    compose_env stop route-api route-worker >/dev/null 2>&1 || true
    psql_query \
      "DELETE FROM route_jobs WHERE user_id = '${SCENIC_SYNTHETIC_USER_ID}'::uuid;" \
      >/dev/null || return 1
    restore_scenic_snapshot || return 1
    evict_scenic_anchor_cache_namespaces || return 1
    compose_env up -d --no-deps --force-recreate route-api route-worker || return 1
  else
    compose_env exec -T postgres sh -ceu \
      'rm -f /tmp/scenic_score_tiles_updates.csv' || return 1
    compose_env up -d --no-deps route-api route-worker || return 1
  fi
  run_internal_healthcheck "Restored route-api" route-api \
    http://127.0.0.1:8080/actuator/health || return 1
  run_internal_healthcheck "Restored route-worker" route-worker \
    http://127.0.0.1:8081/actuator/health || return 1
  if [ "$DATABASE_MUTATION_ATTEMPTED" -eq 1 ]; then
    if ! run_route_smoke; then
      compose_env stop route-api route-worker >/dev/null 2>&1 || true
      psql_query \
        "DELETE FROM route_jobs WHERE user_id = '${SCENIC_SYNTHETIC_USER_ID}'::uuid;" \
        >/dev/null || true
      return 1
    fi
  fi
  if ! compose_env up -d --no-deps caddy; then
    compose_env stop caddy >/dev/null 2>&1 || true
    return 1
  fi
  if ! service_running caddy; then
    compose_env stop caddy >/dev/null 2>&1 || true
    return 1
  fi
  echo "Previous scenic database and healthy runtime were restored; ingress reopened." >&2
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
PREDEPLOY_ENV=""
SCENIC_SNAPSHOT=""
CUTOVER_STARTED=0
DEPLOY_SUCCEEDED=0
RECOVERY_SUCCEEDED=0
DATABASE_MUTATION_ATTEMPTED=0

finish_scenic_deploy() {
  local status=$?
  trap - EXIT
  set +e
  if [ "$status" -ne 0 ] && [ "$CUTOVER_STARTED" -eq 1 ]; then
    if restore_previous_release; then
      RECOVERY_SUCCEEDED=1
    else
      echo "Automatic scenic recovery failed. Ingress remains closed." >&2
      echo "Recover with the preserved env $PREDEPLOY_ENV and database snapshot $SCENIC_SNAPSHOT." >&2
    fi
  fi
  if [ -d "$extract_dir" ]; then
    rm -rf -- "$extract_dir"
  fi
  if [ "$DEPLOY_SUCCEEDED" -eq 1 ] \
      || [ "$CUTOVER_STARTED" -eq 0 ] \
      || [ "$RECOVERY_SUCCEEDED" -eq 1 ]; then
    [ -z "$PREDEPLOY_ENV" ] || rm -f -- "$PREDEPLOY_ENV"
    [ -z "$SCENIC_SNAPSHOT" ] || rm -f -- "$SCENIC_SNAPSHOT"
  fi
  exit "$status"
}
trap finish_scenic_deploy EXIT

archive_members_file="$extract_dir/archive-members.txt"
tar -tzf "$ASSET_PATH" > "$archive_members_file"
archive_member_count=0
archive_has_csv=0
archive_has_metadata=0
while IFS= read -r archive_member; do
  archive_member_count=$((archive_member_count + 1))
  case "$archive_member" in
    scenic_score_tiles_updates.csv) archive_has_csv=1 ;;
    metadata.json) archive_has_metadata=1 ;;
    *)
      echo "Scenic release archive contains an unexpected or unsafe member: $archive_member" >&2
      exit 1
      ;;
  esac
done < "$archive_members_file"
if [ "$archive_member_count" -ne 2 ] \
    || [ "$archive_has_csv" -ne 1 ] \
    || [ "$archive_has_metadata" -ne 1 ]; then
  echo "Scenic release archive must contain exactly the CSV and metadata.json." >&2
  exit 1
fi

echo "Extracting checksum-verified scenic release: $ASSET_PATH"
tar -xzf "$ASSET_PATH" -C "$extract_dir" -- \
  scenic_score_tiles_updates.csv metadata.json

csv_file="$extract_dir/scenic_score_tiles_updates.csv"
EXPECTED_CSV_HEADER="h3_index,scenic_score,water_score,green_score,elevation_score,solitude_score,curve_score,poi_score,park_score,overture_poi_score,building_density_score,darkness_score,urban_penalty_score,road_stress_score,natural_land_use,elevation_variance,last_scored,scoring_version,water_visibility_score,water_crossing_score,coastal_road_score,tree_canopy_score,scenic_poi_score,viewpoint_score,bridge_coastal_score"
IFS= read -r header < "$csv_file" || {
  echo "Scenic CSV is empty." >&2
  exit 1
}
header="${header%$'\r'}"
if [ "$header" != "$EXPECTED_CSV_HEADER" ]; then
  echo "Scenic CSV header must match the exact ordered 3.7 contract with no duplicates." >&2
  exit 1
fi

LC_ALL=C awk -F, -v expected_version="$EXPECTED_SCORING_VERSION" '
  BEGIN {
    split("2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 19 20 21 22 23 24 25", score_indexes, " ")
    split("scenic_score water_score green_score elevation_score solitude_score curve_score poi_score park_score overture_poi_score building_density_score darkness_score urban_penalty_score road_stress_score natural_land_use elevation_variance water_visibility_score water_crossing_score coastal_road_score tree_canopy_score scenic_poi_score viewpoint_score bridge_coastal_score", score_names, " ")
  }
  NR == 1 { next }
  {
    sub(/\r$/, "", $NF)
    if (NF != 25) {
      printf "Scenic CSV row %d has %d columns; expected 25.\n", NR, NF > "/dev/stderr"
      bad = 1
      exit
    }
    if (length($1) != 15 || $1 !~ /^[0-9a-f]+$/) {
      printf "Scenic CSV row %d has an invalid h3_index.\n", NR > "/dev/stderr"
      bad = 1
      exit
    }
    if ($18 != expected_version) {
      printf "Scenic CSV row %d has an unexpected scoring_version.\n", NR > "/dev/stderr"
      bad = 1
      exit
    }
    for (score_number = 1; score_number <= 22; score_number++) {
      value = $(score_indexes[score_number])
      if (value == "") {
        continue
      }
      if (value !~ /^[-+]?([0-9]+([.][0-9]*)?|[.][0-9]+)([eE][-+]?[0-9]+)?$/) {
        printf "Scenic CSV row %d has non-finite or malformed %s.\n", NR, score_names[score_number] > "/dev/stderr"
        bad = 1
        exit
      }
      if ((value + 0) < 0 || (value + 0) > 1) {
        printf "Scenic CSV row %d has out-of-range %s; expected [0,1].\n", NR, score_names[score_number] > "/dev/stderr"
        bad = 1
        exit
      }
    }
    rows++
  }
  END {
    if (!bad && rows == 0) {
      print "Scenic CSV contains no data rows." > "/dev/stderr"
      exit 1
    }
    if (bad) {
      exit 1
    }
  }
' "$csv_file"

echo "Copying validated scenic CSV into postgres container"
compose_env cp "$csv_file" postgres:/tmp/scenic_score_tiles_updates.csv

for service in caddy route-api route-worker postgres redis; do
  service_running "$service" || {
    echo "Required pre-cutover service is not running: $service" >&2
    exit 1
  }
done

PREDEPLOY_ENV="$(mktemp "$release_root/predeploy-env.XXXXXXXX")"
cp -p "$ENV_FILE" "$PREDEPLOY_ENV"
SCENIC_SNAPSHOT="$(mktemp "$release_root/scenic-rollback.XXXXXXXX.csv")"
psql_query "COPY (
  SELECT h3_index, scenic_score, water_score, green_score, elevation_score,
         solitude_score, curve_score, poi_score, park_score, overture_poi_score,
         building_density_score, darkness_score, urban_penalty_score,
         road_stress_score, natural_land_use, elevation_variance, last_scored,
         scoring_version, water_visibility_score, water_crossing_score,
         coastal_road_score, tree_canopy_score, scenic_poi_score, viewpoint_score,
         bridge_coastal_score
  FROM public.scenic_score_tiles
  ORDER BY h3_index
) TO STDOUT WITH (FORMAT csv);" > "$SCENIC_SNAPSHOT"
[ -s "$SCENIC_SNAPSHOT" ] || {
  echo "Could not capture the pre-cutover scenic database snapshot." >&2
  exit 1
}


CUTOVER_STARTED=1
echo "Closing public ingress while the old route-api and worker drain."
compose_env stop caddy
wait_for_worker_drain
echo "Stopping the old route-api and drained route-worker before changing scenic identity."
compose_env stop route-api route-worker

DATABASE_MUTATION_ATTEMPTED=1
echo "Applying scenic score updates"
docker compose --project-directory "$MOODRIDE_DIR" -f "$COMPOSE_FILE" --env-file "$ENV_FILE" exec -T postgres \
  psql -v ON_ERROR_STOP=1 -v expected_scoring_version="$EXPECTED_SCORING_VERSION" \
       -U "$POSTGRES_USER" -d "$POSTGRES_DB" <<'SQL'
SELECT set_config('moodride.expected_scoring_version', :'expected_scoring_version', false);
BEGIN;

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

SELECT COUNT(*) AS invalid_score_count
FROM pg_temp.scenic_release_updates updates
CROSS JOIN LATERAL (
    VALUES
      ('scenic_score', updates.scenic_score),
      ('water_score', updates.water_score),
      ('green_score', updates.green_score),
      ('elevation_score', updates.elevation_score),
      ('solitude_score', updates.solitude_score),
      ('curve_score', updates.curve_score),
      ('poi_score', updates.poi_score),
      ('park_score', updates.park_score),
      ('overture_poi_score', updates.overture_poi_score),
      ('building_density_score', updates.building_density_score),
      ('darkness_score', updates.darkness_score),
      ('urban_penalty_score', updates.urban_penalty_score),
      ('road_stress_score', updates.road_stress_score),
      ('natural_land_use', updates.natural_land_use),
      ('elevation_variance', updates.elevation_variance),
      ('water_visibility_score', updates.water_visibility_score),
      ('water_crossing_score', updates.water_crossing_score),
      ('coastal_road_score', updates.coastal_road_score),
      ('tree_canopy_score', updates.tree_canopy_score),
      ('scenic_poi_score', updates.scenic_poi_score),
      ('viewpoint_score', updates.viewpoint_score),
      ('bridge_coastal_score', updates.bridge_coastal_score)
) AS scores(score_name, score_value)
WHERE score_value IS NOT NULL
  AND (
    score_value::text IN ('NaN', 'Infinity', '-Infinity')
    OR score_value < 0.0
    OR score_value > 1.0
  )
\gset

SELECT (:invalid_score_count::integer <> 0) AS has_invalid_scores
\gset
\if :has_invalid_scores
\echo Scenic release contains non-finite or out-of-range normalized scores.
\echo Invalid normalized scores: :invalid_score_count
SELECT 1 / 0;
\endif

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

compose_env exec -T postgres sh -ceu \
  'rm -f /tmp/scenic_score_tiles_updates.csv'

set_env_var_atomically MOODRIDE_SCENIC_SCORING_VERSION "$EXPECTED_SCORING_VERSION"
export MOODRIDE_SCENIC_SCORING_VERSION="$EXPECTED_SCORING_VERSION"
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
run_internal_healthcheck "Updated route-api" route-api \
  http://127.0.0.1:8080/actuator/health
run_internal_healthcheck "Updated route-worker" route-worker \
  http://127.0.0.1:8081/actuator/health
for service in route-api route-worker; do
  container_id="$(compose_env ps -q "$service")"
  docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$container_id" \
    | grep -Fx "MOODRIDE_SCENIC_SCORING_VERSION=${EXPECTED_SCORING_VERSION}" >/dev/null \
    || { echo "$service did not start with the accepted scenic scoring identity." >&2; exit 1; }
done
run_route_smoke
if service_running caddy; then
  echo "Ingress unexpectedly reopened before scenic behavioral readiness completed." >&2
  exit 1
fi
compose_env up -d --no-deps caddy
service_running caddy || { echo "Ingress failed to reopen after scenic validation." >&2; exit 1; }

DEPLOY_SUCCEEDED=1
echo "Scenic release deployed successfully."
