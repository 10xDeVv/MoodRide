#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
container="wayward-recovery-failure-test-${BASHPID:-$$}"
temp_dir="$(mktemp -d)"
DOCKER_BIN="${DOCKER_BIN:-}"
if [ -z "$DOCKER_BIN" ]; then
  if [ -x "/mnt/c/Program Files/Docker/Docker/resources/bin/docker.exe" ]; then
    DOCKER_BIN="/mnt/c/Program Files/Docker/Docker/resources/bin/docker.exe"
  elif command -v docker >/dev/null 2>&1; then
    DOCKER_BIN="$(command -v docker)"
  else
    echo "Docker CLI is required for the recovery failure test." >&2
    exit 1
  fi
fi

docker() {
  "$DOCKER_BIN" "$@"
}

cleanup_test() {
  docker rm -f "$container" >/dev/null 2>&1 || true
  rm -rf "$temp_dir"
}
trap cleanup_test EXIT

fail() {
  echo "$*" >&2
  return 1
}

compose_env() {
  local ignored_env="$1"
  shift
  [ "$1" = "exec" ] && [ "$2" = "-T" ] && [ "$3" = "postgres" ] \
    || fail "Unexpected compose invocation in recovery test: $*"
  shift 3
  docker exec -i "$container" "$@"
}
psql_query_db() {
  local env_file="$1"
  local database="$2"
  local sql="$3"
  compose_env "$env_file" exec -T postgres \
    psql --username "$POSTGRES_USER" --dbname "$database" \
      --no-psqlrc --quiet --tuples-only --no-align --set ON_ERROR_STOP=1 \
      --command "$sql"
}

POSTGRES_USER=postgres
POSTGRES_DB=moodride_current
source "$SCRIPT_DIR/database_recovery.sh"

docker run -d --name "$container" -e POSTGRES_PASSWORD=test-password postgres:15-alpine >/dev/null
for _ in $(seq 1 60); do
  if docker exec "$container" pg_isready --username postgres >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
docker exec "$container" pg_isready --username postgres >/dev/null

docker exec "$container" createdb --username postgres --owner postgres "$POSTGRES_DB"
docker exec "$container" psql --username postgres --dbname "$POSTGRES_DB" \
  --set ON_ERROR_STOP=1 --command "CREATE TABLE public.availability_probe (value text PRIMARY KEY); INSERT INTO public.availability_probe VALUES ('current-still-available');" >/dev/null

backup="$temp_dir/injected-restore-failure.dump"
printf 'deliberately not a PostgreSQL archive\n' > "$backup"
cksum < "$backup" > "${backup}.cksum"
printf 'expected-history\n' > "$temp_dir/history"
printf 'expected-catalog\n' > "$temp_dir/catalog"

if create_validated_recovery_database ignored.env "$backup" moodride_recovery_candidate \
    "$temp_dir/history" "$temp_dir/catalog"; then
  fail "Failure-injected pg_restore unexpectedly succeeded."
fi

probe="$(docker exec "$container" psql --username postgres --dbname "$POSTGRES_DB" \
  --no-psqlrc --quiet --tuples-only --no-align --set ON_ERROR_STOP=1 \
  --command "SELECT value FROM public.availability_probe;")"
probe="$(printf '%s' "$probe" | tr -d '[:space:]')"
[ "$probe" = "current-still-available" ] \
  || fail "Current database became unavailable after scratch restore failure."

database_names="$(docker exec "$container" psql --username postgres --dbname postgres \
  --no-psqlrc --quiet --tuples-only --no-align \
  --command "SELECT datname FROM pg_catalog.pg_database WHERE datname IN ('$POSTGRES_DB', 'moodride_recovery_candidate') ORDER BY datname;")"
printf '%s\n' "$database_names" | grep -Fx "$POSTGRES_DB" >/dev/null \
  || fail "Current database name was removed after scratch restore failure."
printf '%s\n' "$database_names" | grep -Fx moodride_recovery_candidate >/dev/null \
  || fail "Partial scratch database was not retained for diagnosis."

POSTGRES_DB=moodride_valid_current
docker exec "$container" createdb --username postgres --owner postgres "$POSTGRES_DB"
docker exec -i "$container" psql --username postgres --dbname "$POSTGRES_DB" \
  --set ON_ERROR_STOP=1 >/dev/null <<'SQL'
CREATE TABLE public.flyway_schema_history (
    installed_rank integer PRIMARY KEY,
    version varchar(50),
    description varchar(200) NOT NULL,
    type varchar(20) NOT NULL,
    script varchar(1000) NOT NULL,
    checksum integer,
    success boolean NOT NULL
);
INSERT INTO public.flyway_schema_history
VALUES (38, '38', 'add road segment stable identity', 'SQL',
        'V38__add_road_segment_stable_identity.sql', 1443186875, true);
CREATE TABLE public.route_jobs (status varchar(32) NOT NULL);
CREATE TABLE public.routes (job_id uuid NOT NULL, route_profile varchar(64));
CREATE TABLE public.restored_probe (value text PRIMARY KEY);
INSERT INTO public.restored_probe VALUES ('validated-replacement');
SQL

valid_backup="$temp_dir/valid.dump"
valid_history="$temp_dir/valid.history"
valid_catalog="$temp_dir/valid.catalog"
capture_flyway_history ignored.env "$valid_history"
capture_database_catalog ignored.env "$POSTGRES_DB" "$valid_catalog"
validate_release_invariants ignored.env "$POSTGRES_DB"
create_recovery_backup ignored.env "$valid_backup"
create_validated_recovery_database ignored.env "$valid_backup" moodride_valid_recovery \
  "$valid_history" "$valid_catalog"

docker exec "$container" psql --username postgres --dbname "$POSTGRES_DB" \
  --set ON_ERROR_STOP=1 --command "CREATE TABLE public.candidate_only (value integer);" >/dev/null
promote_validated_recovery_database ignored.env moodride_valid_recovery moodride_valid_quarantine \
  "$valid_backup" "$valid_history" "$valid_catalog"

canonical_probe="$(docker exec "$container" psql --username postgres --dbname "$POSTGRES_DB" \
  --no-psqlrc --quiet --tuples-only --no-align --set ON_ERROR_STOP=1 \
  --command "SELECT value FROM public.restored_probe;")"
canonical_probe="$(printf '%s' "$canonical_probe" | tr -d '[:space:]')"
[ "$canonical_probe" = "validated-replacement" ] \
  || fail "Validated replacement was not promoted under the canonical database name."
canonical_candidate_table="$(docker exec "$container" psql --username postgres --dbname "$POSTGRES_DB" \
  --no-psqlrc --quiet --tuples-only --no-align --set ON_ERROR_STOP=1 \
  --command "SELECT to_regclass('public.candidate_only') IS NULL;")"
[ "$(printf '%s' "$canonical_candidate_table" | tr -d '[:space:]')" = "t" ] \
  || fail "Candidate database remained canonical after recovery promotion."
quarantined_candidate_table="$(docker exec "$container" psql --username postgres --dbname moodride_valid_quarantine \
  --no-psqlrc --quiet --tuples-only --no-align --set ON_ERROR_STOP=1 \
  --command "SELECT to_regclass('public.candidate_only') IS NOT NULL;")"
[ "$(printf '%s' "$quarantined_candidate_table" | tr -d '[:space:]')" = "t" ] \
  || fail "Previous candidate database was not preserved in quarantine."

rename_backup="$temp_dir/rename-failure.dump"
rename_history="$temp_dir/rename-failure.history"
rename_catalog="$temp_dir/rename-failure.catalog"
capture_flyway_history ignored.env "$rename_history"
capture_database_catalog ignored.env "$POSTGRES_DB" "$rename_catalog"
create_recovery_backup ignored.env "$rename_backup"
create_validated_recovery_database ignored.env "$rename_backup" moodride_rename_recovery \
  "$rename_history" "$rename_catalog"
docker exec "$container" psql --username postgres --dbname "$POSTGRES_DB" \
  --set ON_ERROR_STOP=1 \
  --command "CREATE TABLE public.rename_restore_guard (value text PRIMARY KEY); INSERT INTO public.rename_restore_guard VALUES ('old-db-restored');" >/dev/null
docker exec "$container" createdb --username postgres --owner postgres moodride_rename_quarantine_failed

eval "$(declare -f validate_recovery_database | sed '1s/validate_recovery_database/original_validate_recovery_database/')"
eval "$(declare -f run_database_rename | sed '1s/run_database_rename/original_run_database_rename/')"
inject_post_promotion_validation_failure=1
inject_failed_candidate_rename_once=1
validate_recovery_database() {
  if [ "$2" = "$POSTGRES_DB" ] && [ "$inject_post_promotion_validation_failure" -eq 1 ]; then
    inject_post_promotion_validation_failure=0
    return 1
  fi
  original_validate_recovery_database "$@"
}
run_database_rename() {
  case "$2|$3|$inject_failed_candidate_rename_once" in
    "$POSTGRES_DB|moodride_rename_quarantine_failed_1|1")
      inject_failed_candidate_rename_once=0
      return 1
      ;;
  esac
  original_run_database_rename "$@"
}
if promote_validated_recovery_database ignored.env moodride_rename_recovery \
    moodride_rename_quarantine "$rename_backup" "$rename_history" "$rename_catalog"; then
  fail "Post-promotion validation failure fixture unexpectedly succeeded."
fi
rename_restore_probe="$(docker exec "$container" psql --username postgres --dbname "$POSTGRES_DB" \
  --no-psqlrc --quiet --tuples-only --no-align --set ON_ERROR_STOP=1 \
  --command "SELECT value FROM public.rename_restore_guard;")"
[ "$(printf '%s' "$rename_restore_probe" | tr -d '[:space:]')" = "old-db-restored" ] \
  || fail "Prior quarantined database was not restored after injected candidate rename failure."
failed_candidate_exists="$(docker exec "$container" psql --username postgres --dbname postgres \
  --no-psqlrc --quiet --tuples-only --no-align --set ON_ERROR_STOP=1 \
  --command "SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_database WHERE datname = 'moodride_rename_quarantine_failed_1');")"
[ "$(printf '%s' "$failed_candidate_exists" | tr -d '[:space:]')" = "t" ] \
  || fail "Failed promoted candidate was not preserved after rename retry."

persistent_backup="$temp_dir/persistent-rename-failure.dump"
persistent_history="$temp_dir/persistent-rename-failure.history"
persistent_catalog="$temp_dir/persistent-rename-failure.catalog"
capture_flyway_history ignored.env "$persistent_history"
capture_database_catalog ignored.env "$POSTGRES_DB" "$persistent_catalog"
create_recovery_backup ignored.env "$persistent_backup"
create_validated_recovery_database ignored.env "$persistent_backup" moodride_persist_recovery \
  "$persistent_history" "$persistent_catalog"
docker exec "$container" psql --username postgres --dbname "$POSTGRES_DB" \
  --set ON_ERROR_STOP=1 \
  --command "CREATE TABLE public.persistent_rename_restore_guard (value text PRIMARY KEY); INSERT INTO public.persistent_rename_restore_guard VALUES ('prior-db-restored');" >/dev/null

inject_post_promotion_validation_failure=1
persistent_rename_failure_attempts=0
validate_recovery_database() {
  if [ "$2" = "$POSTGRES_DB" ] && [ "$inject_post_promotion_validation_failure" -eq 1 ]; then
    inject_post_promotion_validation_failure=0
    return 1
  fi
  original_validate_recovery_database "$@"
}
run_database_rename() {
  if [ "$2" = "$POSTGRES_DB" ] \
     && [ "$3" = "moodride_persist_quarantine_failed" ]; then
    persistent_rename_failure_attempts=$((persistent_rename_failure_attempts + 1))
    return 1
  fi
  original_run_database_rename "$@"
}
if promote_validated_recovery_database ignored.env moodride_persist_recovery \
    moodride_persist_quarantine "$persistent_backup" \
    "$persistent_history" "$persistent_catalog"; then
  fail "Persistent failed-candidate rename fixture unexpectedly succeeded."
fi
[ "$persistent_rename_failure_attempts" -eq 3 ] \
  || fail "Persistent failed-candidate rename did not exhaust the ordinary retry path."
persistent_restore_probe="$(docker exec "$container" psql --username postgres --dbname "$POSTGRES_DB" \
  --no-psqlrc --quiet --tuples-only --no-align --set ON_ERROR_STOP=1 \
  --command "SELECT value FROM public.persistent_rename_restore_guard;")"
[ "$(printf '%s' "$persistent_restore_probe" | tr -d '[:space:]')" = "prior-db-restored" ] \
  || fail "Prior database was not restored after persistent failed-candidate rename failure."
persistent_database_state="$(docker exec "$container" psql --username postgres --dbname postgres \
  --no-psqlrc --quiet --tuples-only --no-align --set ON_ERROR_STOP=1 \
  --command "
    SELECT CASE WHEN
      EXISTS (
        SELECT 1 FROM pg_catalog.pg_database
        WHERE datname = '$POSTGRES_DB' AND datallowconn
      )
      AND EXISTS (
        SELECT 1 FROM pg_catalog.pg_database
        WHERE datname = 'moodride_persist_quarantine_failed' AND datallowconn
      )
      AND NOT EXISTS (
        SELECT 1 FROM pg_catalog.pg_database
        WHERE datname = 'moodride_persist_quarantine'
      )
      THEN 'persistent-rename-rollback-ok'
      ELSE 'persistent-rename-rollback-divergent'
    END;
  ")"
[ "$(printf '%s' "$persistent_database_state" | tr -d '[:space:]')" = "persistent-rename-rollback-ok" ] \
  || fail "Persistent failed-candidate rename did not restore the database-name invariant."
failed_persistent_candidate_guard="$(docker exec "$container" psql --username postgres \
  --dbname moodride_persist_quarantine_failed \
  --no-psqlrc --quiet --tuples-only --no-align --set ON_ERROR_STOP=1 \
  --command "SELECT to_regclass('public.persistent_rename_restore_guard') IS NULL;")"
[ "$(printf '%s' "$failed_persistent_candidate_guard" | tr -d '[:space:]')" = "t" ] \
  || fail "Failed recovery quarantine does not contain the promoted candidate."

validate_recovery_database() {
  original_validate_recovery_database "$@"
}
run_database_rename() {
  original_run_database_rename "$@"
}

docker exec -i "$container" psql --username postgres --dbname "$POSTGRES_DB" \
  --set ON_ERROR_STOP=1 >/dev/null <<'SQL'
ALTER TABLE public.route_jobs
    ADD COLUMN id uuid PRIMARY KEY,
    ADD COLUMN route_id uuid,
    ADD COLUMN submitted_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN started_at timestamp,
    ADD COLUMN completed_at timestamp;
ALTER TABLE public.routes
    ADD COLUMN id uuid,
    ADD COLUMN generated_at timestamp;
SQL
for migration in \
  V39__add_route_job_lifecycle_fencing.sql \
  V40__add_route_job_dispatch_outbox.sql \
  V41__add_route_job_terminal_event_outbox.sql; do
  docker exec -i "$container" psql --username postgres --dbname "$POSTGRES_DB" \
    --set ON_ERROR_STOP=1 < "$SCRIPT_DIR/../../services/route-api/src/main/resources/db/migration/$migration" >/dev/null
done
docker exec "$container" psql --username postgres --dbname "$POSTGRES_DB" \
  --set ON_ERROR_STOP=1 --command "
    INSERT INTO public.flyway_schema_history
      (installed_rank, version, description, type, script, checksum, success)
    VALUES
      (39, '39', 'add route job lifecycle fencing', 'SQL',
       'V39__add_route_job_lifecycle_fencing.sql', -2068215762, true),
      (40, '40', 'add route job dispatch outbox', 'SQL',
       'V40__add_route_job_dispatch_outbox.sql', -1969470883, true),
      (41, '41', 'add route job terminal event outbox', 'SQL',
       'V41__add_route_job_terminal_event_outbox.sql', -819117119, true);
  " >/dev/null
docker exec -i "$container" psql --username postgres --dbname "$POSTGRES_DB" \
  --set ON_ERROR_STOP=1 < "$SCRIPT_DIR/rollback_v41_v40_v39_to_v38.sql" >/dev/null
rollback_marker="$(docker exec "$container" psql --username postgres --dbname "$POSTGRES_DB" \
  --no-psqlrc --quiet --tuples-only --no-align --set ON_ERROR_STOP=1 \
  --command "
    SELECT CASE WHEN
      (SELECT COUNT(*) FROM public.flyway_schema_history WHERE version = '38') = 1
      AND NOT EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE version IN ('39', '40', '41'))
      AND to_regclass('public.route_job_dispatches') IS NULL
      AND to_regclass('public.route_job_terminal_events') IS NULL
      THEN 'rollback-sql-ok' ELSE 'rollback-sql-divergent' END;
  ")"
[ "$(printf '%s' "$rollback_marker" | tr -d '[:space:]')" = "rollback-sql-ok" ] \
  || fail "Coordinated V41/V40/V39 rollback SQL did not retain the V38 baseline."

scenic_sql="$temp_dir/deploy-scenic-release.sql"
sed -n "/<<'SQL'/,/^SQL$/p" "$SCRIPT_DIR/deploy_scenic_release.sh" \
  | sed '1d;$d' > "$scenic_sql"
[ -s "$scenic_sql" ] || fail "Could not extract the deployed scenic SQL heredoc."
docker exec "$container" psql --username postgres --dbname "$POSTGRES_DB" \
  --set ON_ERROR_STOP=1 --command "
    CREATE TABLE public.scenic_score_tiles (
      h3_index varchar(15) PRIMARY KEY,
      scenic_score double precision,
      water_score double precision,
      green_score double precision,
      elevation_score double precision,
      solitude_score double precision,
      curve_score double precision,
      poi_score double precision,
      park_score double precision,
      overture_poi_score double precision,
      building_density_score double precision,
      darkness_score double precision,
      urban_penalty_score double precision,
      road_stress_score double precision,
      natural_land_use double precision,
      elevation_variance double precision,
      last_scored timestamp,
      scoring_version varchar(80)
    );
    INSERT INTO public.scenic_score_tiles (h3_index, scenic_score, scoring_version)
    VALUES ('8928308280fffff', 0.1, '3.6-control');
  " >/dev/null
scenic_csv="$temp_dir/scenic_score_tiles_updates.csv"
cat > "$scenic_csv" <<'CSV'
h3_index,scenic_score,water_score,green_score,elevation_score,solitude_score,curve_score,poi_score,park_score,overture_poi_score,building_density_score,darkness_score,urban_penalty_score,road_stress_score,natural_land_use,elevation_variance,last_scored,scoring_version,water_visibility_score,water_crossing_score,coastal_road_score,tree_canopy_score,scenic_poi_score,viewpoint_score,bridge_coastal_score
8928308280fffff,0.9,0.1,0.2,0.3,0.4,0.5,0.6,0.7,0.8,0.1,0.2,0.0,0.1,0.8,0.2,,3.7-sql-fixture,0.3,0.4,0.5,0.6,0.7,0.8,0.9
CSV

relative_asset_fixture="$temp_dir/scenic-relative-asset"
relative_project="$relative_asset_fixture/project"
relative_caller="$relative_asset_fixture/caller"
relative_bin="$relative_asset_fixture/bin"
relative_payload="$relative_asset_fixture/payload"
relative_docker_log="$relative_asset_fixture/docker.log"
mkdir -p "$relative_project" "$relative_caller/assets" "$relative_bin" "$relative_payload"
cp "$scenic_csv" "$relative_payload/scenic_score_tiles_updates.csv"
printf 'not a scenic release\n' > "$relative_payload/unrelated.txt"
tar -czf "$relative_caller/assets/valid-scenic.tar.gz" \
  -C "$relative_payload" scenic_score_tiles_updates.csv
tar -czf "$relative_caller/assets/incomplete-scenic.tar.gz" \
  -C "$relative_payload" unrelated.txt
cat > "$relative_project/.env.prod" <<'ENV'
POSTGRES_DB=scenic_fixture
POSTGRES_USER=postgres
REDIS_PASSWORD=scenic-fixture-password
MOODRIDE_ROAD_DATASET_REVISION=scenic-fixture-road
MOODRIDE_ROAD_DATASET_FINGERPRINT=0000000000000000000000000000000000000000000000000000000000000000
MOODRIDE_ROAD_ANCHOR_CACHE_SCHEMA=scenic-fixture-anchor
ENV
: > "$relative_project/docker-compose.prod.yml"
cat > "$relative_bin/docker" <<'SH'
#!/usr/bin/env sh
printf '%s\n' "$*" >> "$SCENIC_DOCKER_LOG"
exit 73
SH
cat > "$relative_bin/date" <<'SH'
#!/usr/bin/env sh
printf '%s\n' '20260101T000000Z'
SH
chmod +x "$relative_bin/docker" "$relative_bin/date"
: > "$relative_docker_log"

run_scenic_asset_preflight() {
  local asset_name="$1"
  (
    cd "$relative_caller"
    PATH="$relative_bin:$PATH" \
      SCENIC_DOCKER_LOG="$relative_docker_log" \
      MOODRIDE_DIR="$relative_project" \
      bash "$SCRIPT_DIR/deploy_scenic_release.sh" \
        --asset "assets/$asset_name" \
        --scoring-version 3.7-relative-fixture
  )
}

if run_scenic_asset_preflight valid-scenic.tar.gz \
    >"$relative_asset_fixture/valid.log" 2>&1; then
  fail "Scenic relative-asset preflight unexpectedly passed the injected Docker failure."
fi
[ "$(wc -l < "$relative_docker_log" | tr -d '[:space:]')" = "1" ] \
  || fail "Scenic deploy did not resolve a relative asset before changing directories."
grep -F " cp $relative_project/.deploy/scenic-releases/scenic-release." \
  "$relative_docker_log" >/dev/null \
  || fail "Scenic deploy did not copy from its unique extraction directory."

if run_scenic_asset_preflight incomplete-scenic.tar.gz \
    >"$relative_asset_fixture/incomplete.log" 2>&1; then
  fail "Incomplete scenic asset unexpectedly passed preflight."
fi
grep -F "Missing required file in scenic asset: scenic_score_tiles_updates.csv" \
  "$relative_asset_fixture/incomplete.log" >/dev/null \
  || fail "A stale scenic extraction contaminated the next preflight."
[ "$(wc -l < "$relative_docker_log" | tr -d '[:space:]')" = "1" ] \
  || fail "A stale scenic CSV reached Docker during the next preflight."
for extracted_dir in "$relative_project/.deploy/scenic-releases"/scenic-release.*; do
  [ ! -e "$extracted_dir" ] \
    || fail "Scenic deploy left a disposable extraction directory behind."
done
docker exec -i "$container" sh -c 'cat > /tmp/scenic_score_tiles_updates.csv' < "$scenic_csv"
docker exec -i "$container" psql --username postgres --dbname "$POSTGRES_DB" \
  --set ON_ERROR_STOP=1 --set expected_scoring_version=3.7-sql-fixture \
  < "$scenic_sql" >/dev/null
scenic_success_state="$(docker exec "$container" psql --username postgres --dbname "$POSTGRES_DB" \
  --no-psqlrc --quiet --tuples-only --no-align --set ON_ERROR_STOP=1 \
  --command "
    SELECT CASE WHEN
      EXISTS (
        SELECT 1
        FROM public.scenic_score_tiles
        WHERE h3_index = '8928308280fffff'
          AND scenic_score = 0.9
          AND scoring_version = '3.7-sql-fixture'
      )
      AND (
        SELECT COUNT(*)
        FROM pg_catalog.pg_attribute
        WHERE attrelid = 'public.scenic_score_tiles'::regclass
          AND attname IN (
            'overture_poi_score', 'building_density_score', 'darkness_score',
            'urban_penalty_score', 'road_stress_score', 'water_visibility_score',
            'water_crossing_score', 'coastal_road_score', 'tree_canopy_score',
            'scenic_poi_score', 'viewpoint_score', 'bridge_coastal_score'
          )
          AND NOT attisdropped
      ) = 12
      THEN 'scenic-success-ok' ELSE 'scenic-success-divergent' END;
  ")"
[ "$(printf '%s' "$scenic_success_state" | tr -d '[:space:]')" = "scenic-success-ok" ] \
  || fail "Actual scenic deploy SQL did not persist its 3.7 identity and complete DDL."

docker exec "$container" psql --username postgres --dbname "$POSTGRES_DB" \
  --set ON_ERROR_STOP=1 --command "
    UPDATE public.scenic_score_tiles
    SET scenic_score = 0.41, scoring_version = '3.6-covered-before-failure'
    WHERE h3_index = '8928308280fffff';
    INSERT INTO public.scenic_score_tiles (h3_index, scenic_score, scoring_version)
    VALUES ('8928308280bffff', 0.23, '3.6-uncovered-before-failure');
    ALTER TABLE public.scenic_score_tiles DROP COLUMN bridge_coastal_score;
  " >/dev/null

scenic_failure_state() {
  docker exec "$container" psql --username postgres --dbname "$POSTGRES_DB" \
    --no-psqlrc --quiet --tuples-only --no-align --set ON_ERROR_STOP=1 \
    --command "
      SELECT jsonb_build_object(
        'rows', (
          SELECT jsonb_agg(to_jsonb(tiles) ORDER BY tiles.h3_index)
          FROM public.scenic_score_tiles tiles
        ),
        'schema', (
          SELECT jsonb_agg(
            jsonb_build_object(
              'name', attributes.attname,
              'type', pg_catalog.format_type(attributes.atttypid, attributes.atttypmod),
              'not_null', attributes.attnotnull,
              'default', pg_catalog.pg_get_expr(defaults.adbin, defaults.adrelid)
            )
            ORDER BY attributes.attnum
          )
          FROM pg_catalog.pg_attribute attributes
          LEFT JOIN pg_catalog.pg_attrdef defaults
            ON defaults.adrelid = attributes.attrelid
           AND defaults.adnum = attributes.attnum
          WHERE attributes.attrelid = 'public.scenic_score_tiles'::regclass
            AND attributes.attnum > 0
            AND NOT attributes.attisdropped
        )
      )::text;
    "
}

scenic_state_before_failure="$(scenic_failure_state)"
if docker exec -i "$container" psql --username postgres --dbname "$POSTGRES_DB" \
    --set ON_ERROR_STOP=1 --set expected_scoring_version=3.7-sql-fixture \
    < "$scenic_sql" >/dev/null 2>&1; then
  fail "Actual scenic deploy SQL accepted incomplete 3.7 tile coverage."
fi
scenic_state_after_failure="$(scenic_failure_state)"
[ "$scenic_state_after_failure" = "$scenic_state_before_failure" ] \
  || fail "Failed scenic coverage validation changed data rows, scoring versions, or table DDL."

scenic_rollback_state="$(docker exec "$container" psql --username postgres --dbname "$POSTGRES_DB" \
  --no-psqlrc --quiet --tuples-only --no-align --set ON_ERROR_STOP=1 \
  --command "
    SELECT CASE WHEN
      (SELECT COUNT(*) FROM public.scenic_score_tiles) = 2
      AND EXISTS (
        SELECT 1 FROM public.scenic_score_tiles
        WHERE h3_index = '8928308280fffff'
          AND scenic_score = 0.41
          AND scoring_version = '3.6-covered-before-failure'
      )
      AND EXISTS (
        SELECT 1 FROM public.scenic_score_tiles
        WHERE h3_index = '8928308280bffff'
          AND scenic_score = 0.23
          AND scoring_version = '3.6-uncovered-before-failure'
      )
      AND NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_attribute
        WHERE attrelid = 'public.scenic_score_tiles'::regclass
          AND attname = 'bridge_coastal_score'
          AND NOT attisdropped
      )
      THEN 'scenic-rollback-ok' ELSE 'scenic-rollback-divergent' END;
  ")"
[ "$(printf '%s' "$scenic_rollback_state" | tr -d '[:space:]')" = "scenic-rollback-ok" ] \
  || fail "Failed scenic coverage validation did not preserve both rows and pre-run DDL."

echo "restore-failure-safety-ok"
