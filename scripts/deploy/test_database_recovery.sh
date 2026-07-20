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
backup_sync_log="$temp_dir/backup-sync.log"
eval "$(declare -f sync_recovery_path | sed '1s/sync_recovery_path/original_sync_recovery_path/')"
sync_recovery_path() {
  printf '%s\n' "$1" >> "$backup_sync_log"
  original_sync_recovery_path "$@"
}

create_recovery_backup ignored.env "$valid_backup"
sync_recovery_path() {
  original_sync_recovery_path "$@"
}
[ -s "$valid_backup" ] && [ -s "${valid_backup}.cksum" ] \
  || fail "Recovery backup publication did not create a dump and checksum pair."
verify_recovery_backup "$valid_backup" \
  || fail "Published recovery backup dump and checksum do not match."
[ "$(wc -l < "$backup_sync_log" | tr -d '[:space:]')" = "3" ] \
  || fail "Recovery backup did not sync both staged artifacts and their parent directory."
grep -E '/\.valid\.dump\.dump\.tmp\.[^/]+$' "$backup_sync_log" >/dev/null \
  || fail "Recovery backup dump was not synced from a unique temporary path."
grep -E '/\.valid\.dump\.cksum\.tmp\.[^/]+$' "$backup_sync_log" >/dev/null \
  || fail "Recovery backup checksum was not synced from a unique temporary path."
grep -Fx "$temp_dir" "$backup_sync_log" >/dev/null \
  || fail "Recovery backup parent directory was not synced after pair publication."
for staged_backup_artifact in \
    "$temp_dir"/.valid.dump.dump.tmp.* \
    "$temp_dir"/.valid.dump.cksum.tmp.*; do
  [ ! -e "$staged_backup_artifact" ] \
    || fail "Recovery backup left a staged artifact after durable pair publication."
done
create_validated_recovery_database ignored.env "$valid_backup" moodride_valid_recovery \
  "$valid_history" "$valid_catalog"
precondition_prior_oid="$(database_oid ignored.env "$POSTGRES_DB")"
precondition_recovery_oid="$(database_oid ignored.env moodride_valid_recovery)"
valid_checksum="$(cat "${valid_backup}.cksum")"
printf '%s\n' 'invalid-checksum-fixture' > "${valid_backup}.cksum"
if promote_validated_recovery_database ignored.env moodride_valid_recovery \
    moodride_precondition_quarantine "$valid_backup" "$valid_history" "$valid_catalog"; then
  fail "Recovery promotion accepted a mismatched backup checksum."
fi
printf '%s\n' "$valid_checksum" > "${valid_backup}.cksum"
precondition_failure_state="$(docker exec "$container" psql --username postgres --dbname postgres \
  --no-psqlrc --quiet --tuples-only --no-align --set ON_ERROR_STOP=1 \
  --command "
    SELECT CASE WHEN
      EXISTS (
        SELECT 1 FROM pg_catalog.pg_database
        WHERE datname = '$POSTGRES_DB'
          AND oid::text = '$precondition_prior_oid'
          AND datallowconn
      )
      AND EXISTS (
        SELECT 1 FROM pg_catalog.pg_database
        WHERE datname = 'moodride_valid_recovery'
          AND oid::text = '$precondition_recovery_oid'
          AND datallowconn
      )
      AND NOT EXISTS (
        SELECT 1 FROM pg_catalog.pg_database
        WHERE datname = 'moodride_precondition_quarantine'
      )
      THEN 'precondition-failure-ok'
      ELSE 'precondition-failure-divergent'
    END;
  ")"
[ "$(printf '%s' "$precondition_failure_state" | tr -d '[:space:]')" = "precondition-failure-ok" ] \
  || fail "Failed recovery precondition changed database identities or connectivity."

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

eval "$(declare -f run_database_rename | sed '1s/run_database_rename/original_run_database_rename/')"

promotion_backup="$temp_dir/promotion-failure.dump"
promotion_history="$temp_dir/promotion-failure.history"
promotion_catalog="$temp_dir/promotion-failure.catalog"
capture_flyway_history ignored.env "$promotion_history"
capture_database_catalog ignored.env "$POSTGRES_DB" "$promotion_catalog"
create_recovery_backup ignored.env "$promotion_backup"
create_validated_recovery_database ignored.env "$promotion_backup" moodride_promotion_failure_recovery \
  "$promotion_history" "$promotion_catalog"
promotion_prior_oid="$(database_oid ignored.env "$POSTGRES_DB")"
promotion_recovery_oid="$(database_oid ignored.env moodride_promotion_failure_recovery)"
docker exec "$container" psql --username postgres --dbname "$POSTGRES_DB" \
  --set ON_ERROR_STOP=1 \
  --command "CREATE TABLE public.promotion_restore_guard (value text PRIMARY KEY); INSERT INTO public.promotion_restore_guard VALUES ('exact-original-restored');" >/dev/null

promotion_rename_failure_attempts=0
promotion_restore_failure_attempts=0
inject_ambiguous_initial_quarantine_once=1
run_database_rename() {
  if [ "$2" = "$POSTGRES_DB" ] \
     && [ "$3" = "moodride_promotion_failure_quarantine" ] \
     && [ "$inject_ambiguous_initial_quarantine_once" -eq 1 ]; then
    inject_ambiguous_initial_quarantine_once=0
    original_run_database_rename "$@" || return 1
    return 1
  fi
  if [ "$2" = "moodride_promotion_failure_recovery" ] \
     && [ "$3" = "$POSTGRES_DB" ]; then
    promotion_rename_failure_attempts=$((promotion_rename_failure_attempts + 1))
    return 1
  fi
  if [ "$2" = "moodride_promotion_failure_quarantine" ] \
     && [ "$3" = "$POSTGRES_DB" ]; then
    promotion_restore_failure_attempts=$((promotion_restore_failure_attempts + 1))
    return 1
  fi
  original_run_database_rename "$@"
}
if promote_validated_recovery_database ignored.env moodride_promotion_failure_recovery \
    moodride_promotion_failure_quarantine "$promotion_backup" \
    "$promotion_history" "$promotion_catalog"; then
  fail "Promotion rename failure fixture unexpectedly succeeded."
fi
[ "$promotion_rename_failure_attempts" -eq 3 ] \
  || fail "Promotion rename failure did not exhaust its ordinary retry path."
[ "$promotion_restore_failure_attempts" -eq 3 ] \
  || fail "Original database restoration did not exhaust its ordinary retry path."
[ "$inject_ambiguous_initial_quarantine_once" -eq 0 ] \
  || fail "Ambiguous initial quarantine result was not injected."
promotion_failure_state="$(docker exec "$container" psql --username postgres --dbname postgres \
  --no-psqlrc --quiet --tuples-only --no-align --set ON_ERROR_STOP=1 \
  --command "
    SELECT CASE WHEN
      EXISTS (
        SELECT 1 FROM pg_catalog.pg_database
        WHERE datname = '$POSTGRES_DB'
          AND oid::text = '$promotion_prior_oid'
          AND datallowconn
      )
      AND EXISTS (
        SELECT 1 FROM pg_catalog.pg_database
        WHERE datname = 'moodride_promotion_failure_recovery'
          AND oid::text = '$promotion_recovery_oid'
          AND datallowconn
      )
      AND NOT EXISTS (
        SELECT 1 FROM pg_catalog.pg_database
        WHERE datname = 'moodride_promotion_failure_quarantine'
      )
      AND NOT EXISTS (
        SELECT 1 FROM pg_catalog.pg_database
        WHERE datname = '$POSTGRES_DB'
          AND oid::text = '$promotion_recovery_oid'
      )
      THEN 'promotion-rename-rollback-ok'
      ELSE 'promotion-rename-rollback-divergent'
    END;
  ")"
[ "$(printf '%s' "$promotion_failure_state" | tr -d '[:space:]')" = "promotion-rename-rollback-ok" ] \
  || fail "Promotion rename failure did not restore the exact database identities and connectivity."
promotion_restore_probe="$(docker exec "$container" psql --username postgres --dbname "$POSTGRES_DB" \
  --no-psqlrc --quiet --tuples-only --no-align --set ON_ERROR_STOP=1 \
  --command "SELECT value FROM public.promotion_restore_guard;")"
[ "$(printf '%s' "$promotion_restore_probe" | tr -d '[:space:]')" = "exact-original-restored" ] \
  || fail "Promotion rename failure did not return the exact original database to service."

initial_quarantine_failure_attempts=0
run_database_rename() {
  if [ "$2" = "$POSTGRES_DB" ] \
     && [ "$3" = "moodride_initial_quarantine_failure" ]; then
    initial_quarantine_failure_attempts=$((initial_quarantine_failure_attempts + 1))
    return 1
  fi
  original_run_database_rename "$@"
}
if promote_validated_recovery_database ignored.env moodride_promotion_failure_recovery \
    moodride_initial_quarantine_failure "$promotion_backup" \
    "$promotion_history" "$promotion_catalog"; then
  fail "Initial quarantine rename failure fixture unexpectedly succeeded."
fi
[ "$initial_quarantine_failure_attempts" -eq 3 ] \
  || fail "Initial quarantine rename failure did not exhaust its ordinary retry path."
initial_quarantine_failure_state="$(docker exec "$container" psql --username postgres --dbname postgres \
  --no-psqlrc --quiet --tuples-only --no-align --set ON_ERROR_STOP=1 \
  --command "
    SELECT CASE WHEN
      EXISTS (
        SELECT 1 FROM pg_catalog.pg_database
        WHERE datname = '$POSTGRES_DB'
          AND oid::text = '$promotion_prior_oid'
          AND datallowconn
      )
      AND EXISTS (
        SELECT 1 FROM pg_catalog.pg_database
        WHERE datname = 'moodride_promotion_failure_recovery'
          AND oid::text = '$promotion_recovery_oid'
          AND datallowconn
      )
      AND NOT EXISTS (
        SELECT 1 FROM pg_catalog.pg_database
        WHERE datname = 'moodride_initial_quarantine_failure'
      )
      THEN 'initial-quarantine-rollback-ok'
      ELSE 'initial-quarantine-rollback-divergent'
    END;
  ")"
[ "$(printf '%s' "$initial_quarantine_failure_state" | tr -d '[:space:]')" = "initial-quarantine-rollback-ok" ] \
  || fail "Initial quarantine failure changed the original database identity, name, or connectivity."
run_database_rename() {
  original_run_database_rename "$@"
}

rename_backup="$temp_dir/rename-failure.dump"
rename_history="$temp_dir/rename-failure.history"
rename_catalog="$temp_dir/rename-failure.catalog"
capture_flyway_history ignored.env "$rename_history"
capture_database_catalog ignored.env "$POSTGRES_DB" "$rename_catalog"
create_recovery_backup ignored.env "$rename_backup"
create_validated_recovery_database ignored.env "$rename_backup" moodride_rename_recovery \
  "$rename_history" "$rename_catalog"
rename_prior_oid="$(database_oid ignored.env "$POSTGRES_DB")"
rename_recovery_oid="$(database_oid ignored.env moodride_rename_recovery)"
docker exec "$container" psql --username postgres --dbname "$POSTGRES_DB" \
  --set ON_ERROR_STOP=1 \
  --command "CREATE TABLE public.rename_restore_guard (value text PRIMARY KEY); INSERT INTO public.rename_restore_guard VALUES ('old-db-restored');" >/dev/null
docker exec "$container" createdb --username postgres --owner postgres moodride_rename_quarantine_failed

eval "$(declare -f validate_recovery_database | sed '1s/validate_recovery_database/original_validate_recovery_database/')"
inject_post_promotion_validation_failure=1
inject_failed_candidate_rename_once=1
inject_ambiguous_promotion_once=1
validate_recovery_database() {
  if [ "$2" = "$POSTGRES_DB" ] && [ "$inject_post_promotion_validation_failure" -eq 1 ]; then
    inject_post_promotion_validation_failure=0
    return 1
  fi
  original_validate_recovery_database "$@"
}
run_database_rename() {
  if [ "$2" = "moodride_rename_recovery" ] \
     && [ "$3" = "$POSTGRES_DB" ] \
     && [ "$inject_ambiguous_promotion_once" -eq 1 ]; then
    inject_ambiguous_promotion_once=0
    original_run_database_rename "$@" || return 1
    return 1
  fi
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
[ "$inject_ambiguous_promotion_once" -eq 0 ] \
  || fail "Ambiguous recovery promotion result was not reconciled by database OID."
rename_restore_probe="$(docker exec "$container" psql --username postgres --dbname "$POSTGRES_DB" \
  --no-psqlrc --quiet --tuples-only --no-align --set ON_ERROR_STOP=1 \
  --command "SELECT value FROM public.rename_restore_guard;")"
[ "$(printf '%s' "$rename_restore_probe" | tr -d '[:space:]')" = "old-db-restored" ] \
  || fail "Prior quarantined database was not restored after injected candidate rename failure."
rename_failure_state="$(docker exec "$container" psql --username postgres --dbname postgres \
  --no-psqlrc --quiet --tuples-only --no-align --set ON_ERROR_STOP=1 \
  --command "
    SELECT CASE WHEN
      EXISTS (
        SELECT 1 FROM pg_catalog.pg_database
        WHERE datname = '$POSTGRES_DB'
          AND oid::text = '$rename_prior_oid'
          AND datallowconn
      )
      AND EXISTS (
        SELECT 1 FROM pg_catalog.pg_database
        WHERE datname = 'moodride_rename_quarantine_failed_1'
          AND oid::text = '$rename_recovery_oid'
          AND datallowconn
      )
      AND NOT EXISTS (
        SELECT 1 FROM pg_catalog.pg_database
        WHERE datname = 'moodride_rename_quarantine'
      )
      AND NOT EXISTS (
        SELECT 1 FROM pg_catalog.pg_database
        WHERE datname = '$POSTGRES_DB'
          AND oid::text = '$rename_recovery_oid'
      )
      THEN 'rename-rollback-ok'
      ELSE 'rename-rollback-divergent'
    END;
  ")"
[ "$(printf '%s' "$rename_failure_state" | tr -d '[:space:]')" = "rename-rollback-ok" ] \
  || fail "Injected candidate rename failure did not restore exact identities and connectivity."

persistent_backup="$temp_dir/persistent-rename-failure.dump"
persistent_history="$temp_dir/persistent-rename-failure.history"
persistent_catalog="$temp_dir/persistent-rename-failure.catalog"
capture_flyway_history ignored.env "$persistent_history"
capture_database_catalog ignored.env "$POSTGRES_DB" "$persistent_catalog"
create_recovery_backup ignored.env "$persistent_backup"
create_validated_recovery_database ignored.env "$persistent_backup" moodride_persist_recovery \
  "$persistent_history" "$persistent_catalog"
persistent_prior_oid="$(database_oid ignored.env "$POSTGRES_DB")"
persistent_recovery_oid="$(database_oid ignored.env moodride_persist_recovery)"
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
        WHERE datname = '$POSTGRES_DB'
          AND oid::text = '$persistent_prior_oid'
          AND datallowconn
      )
      AND EXISTS (
        SELECT 1 FROM pg_catalog.pg_database
        WHERE datname = 'moodride_persist_quarantine_failed'
          AND oid::text = '$persistent_recovery_oid'
          AND datallowconn
      )
      AND NOT EXISTS (
        SELECT 1 FROM pg_catalog.pg_database
        WHERE datname = 'moodride_persist_quarantine'
      )
      AND NOT EXISTS (
        SELECT 1 FROM pg_catalog.pg_database
        WHERE datname = '$POSTGRES_DB'
          AND oid::text = '$persistent_recovery_oid'
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

failclosed_backup="$temp_dir/failclosed-quarantine.dump"
failclosed_history="$temp_dir/failclosed-quarantine.history"
failclosed_catalog="$temp_dir/failclosed-quarantine.catalog"
capture_flyway_history ignored.env "$failclosed_history"
capture_database_catalog ignored.env "$POSTGRES_DB" "$failclosed_catalog"
create_recovery_backup ignored.env "$failclosed_backup"
create_validated_recovery_database ignored.env "$failclosed_backup" moodride_failclosed_recovery \
  "$failclosed_history" "$failclosed_catalog"
failclosed_prior_oid="$(database_oid ignored.env "$POSTGRES_DB")"
failclosed_recovery_oid="$(database_oid ignored.env moodride_failclosed_recovery)"
docker exec "$container" psql --username postgres --dbname "$POSTGRES_DB" \
  --set ON_ERROR_STOP=1 \
  --command "CREATE TABLE public.failclosed_restore_guard (value text PRIMARY KEY); INSERT INTO public.failclosed_restore_guard VALUES ('failclosed-original-restored');" >/dev/null

eval "$(declare -f reclaim_database_name | sed '1s/reclaim_database_name/original_reclaim_database_name/')"
inject_post_promotion_validation_failure=1
failclosed_rename_attempts=0
failclosed_reclaim_attempts=0
validate_recovery_database() {
  if [ "$2" = "$POSTGRES_DB" ] && [ "$inject_post_promotion_validation_failure" -eq 1 ]; then
    inject_post_promotion_validation_failure=0
    return 1
  fi
  original_validate_recovery_database "$@"
}
run_database_rename() {
  if [ "$2" = "$POSTGRES_DB" ] \
     && [ "$3" = "moodride_failclosed_quarantine_failed" ]; then
    failclosed_rename_attempts=$((failclosed_rename_attempts + 1))
    return 1
  fi
  original_run_database_rename "$@"
}
reclaim_database_name() {
  if [ "$2" = "$POSTGRES_DB" ] \
     && [ "$3" = "moodride_failclosed_quarantine_failed" ]; then
    failclosed_reclaim_attempts=$((failclosed_reclaim_attempts + 1))
    return 1
  fi
  original_reclaim_database_name "$@"
}
failclosed_log="$temp_dir/failclosed-quarantine.log"
if promote_validated_recovery_database ignored.env moodride_failclosed_recovery \
    moodride_failclosed_quarantine "$failclosed_backup" \
    "$failclosed_history" "$failclosed_catalog" >"$failclosed_log" 2>&1; then
  fail "Unrecoverable failed-candidate quarantine fixture unexpectedly succeeded."
fi
[ "$failclosed_rename_attempts" -eq 3 ] \
  || fail "Fail-closed candidate quarantine did not exhaust ordinary rename retries."
[ "$failclosed_reclaim_attempts" -eq 1 ] \
  || fail "Fail-closed candidate quarantine did not attempt the reclaim fallback."
grep -F "CRITICAL FAIL-CLOSED: the unvalidated recovery database could not be quarantined and remains at the canonical name with connections disabled" \
  "$failclosed_log" >/dev/null \
  || fail "Failed-candidate quarantine did not report its explicit fail-closed state."
failclosed_database_state="$(docker exec "$container" psql --username postgres --dbname postgres \
  --no-psqlrc --quiet --tuples-only --no-align --set ON_ERROR_STOP=1 \
  --command "
    SELECT CASE WHEN
      EXISTS (
        SELECT 1 FROM pg_catalog.pg_database
        WHERE datname = '$POSTGRES_DB'
          AND oid::text = '$failclosed_recovery_oid'
          AND NOT datallowconn
      )
      AND EXISTS (
        SELECT 1 FROM pg_catalog.pg_database
        WHERE datname = 'moodride_failclosed_quarantine'
          AND oid::text = '$failclosed_prior_oid'
          AND datallowconn
      )
      AND NOT EXISTS (
        SELECT 1 FROM pg_catalog.pg_database
        WHERE datname = 'moodride_failclosed_quarantine_failed'
      )
      THEN 'failclosed-quarantine-ok'
      ELSE 'failclosed-quarantine-divergent'
    END;
  ")"
[ "$(printf '%s' "$failclosed_database_state" | tr -d '[:space:]')" = "failclosed-quarantine-ok" ] \
  || fail "Failed-candidate quarantine did not disable canonical candidate connectivity."
if docker exec "$container" psql --username postgres --dbname "$POSTGRES_DB" \
    --no-psqlrc --quiet --tuples-only --no-align --set ON_ERROR_STOP=1 \
    --command "SELECT 1;" >/dev/null 2>&1; then
  fail "Unvalidated canonical recovery database still accepted connections."
fi

original_reclaim_database_name ignored.env "$POSTGRES_DB" \
  moodride_failclosed_quarantine_failed "$failclosed_recovery_oid"
restore_prior_database_to_canonical ignored.env \
  moodride_failclosed_quarantine "$failclosed_prior_oid"
verify_failed_promotion_rollback_invariant ignored.env \
  "$failclosed_prior_oid" "$failclosed_recovery_oid" \
  moodride_failclosed_quarantine moodride_failclosed_quarantine_failed \
  || fail "Fail-closed fixture cleanup did not restore exact database identities."
failclosed_restore_probe="$(docker exec "$container" psql --username postgres --dbname "$POSTGRES_DB" \
  --no-psqlrc --quiet --tuples-only --no-align --set ON_ERROR_STOP=1 \
  --command "SELECT value FROM public.failclosed_restore_guard;")"
[ "$(printf '%s' "$failclosed_restore_probe" | tr -d '[:space:]')" = "failclosed-original-restored" ] \
  || fail "Fail-closed fixture cleanup did not restore original database connectivity."
reclaim_database_name() {
  original_reclaim_database_name "$@"
}
eval "$(declare -f compose_env | sed '1s/compose_env/original_compose_env/')"
failclosed_stop_attempts=0
failclosed_kill_attempts=0
failclosed_stop_verification_file="$temp_dir/failclosed-stop-verifications"
printf '%s\n' 0 > "$failclosed_stop_verification_file"
compose_env() {
  local delegated_env="$1"
  local verification_count
  shift
  case "$*" in
    "stop postgres")
      failclosed_stop_attempts=$((failclosed_stop_attempts + 1))
      return 1
      ;;
    "kill postgres")
      failclosed_kill_attempts=$((failclosed_kill_attempts + 1))
      return 1
      ;;
    "ps --status running --quiet postgres")
      verification_count="$(cat "$failclosed_stop_verification_file")"
      verification_count=$((verification_count + 1))
      printf '%s\n' "$verification_count" > "$failclosed_stop_verification_file"
      if [ "$verification_count" -eq 1 ]; then
        printf '%s\n' 'still-running-postgres-container'
      fi
      return 0
      ;;
  esac
  original_compose_env "$delegated_env" "$@"
}
stop_database_service_fail_closed ignored.env
[ "$failclosed_stop_attempts" -eq 2 ] \
  && [ "$failclosed_kill_attempts" -eq 2 ] \
  && [ "$(cat "$failclosed_stop_verification_file")" -eq 2 ] \
  || fail "Fail-closed PostgreSQL shutdown returned before a stopped service was verified."
compose_env() {
  original_compose_env "$@"
}

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
      scoring_version varchar(80),
      water_visibility_score double precision,
      water_crossing_score double precision,
      coastal_road_score double precision,
      tree_canopy_score double precision,
      scenic_poi_score double precision,
      viewpoint_score double precision,
      bridge_coastal_score double precision
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
printf '{"scoringVersion":"3.7-relative-fixture"}\n' > "$relative_payload/metadata.json"
preflight_valid_csv="$relative_payload/valid.csv"
sed 's/3\.7-sql-fixture/3.7-relative-fixture/' "$scenic_csv" > "$preflight_valid_csv"
preflight_header="$(sed -n '1p' "$preflight_valid_csv")"
preflight_row="$(sed -n '2p' "$preflight_valid_csv")"

make_scenic_asset() {
  local asset_name="$1"
  local csv_source="$2"
  local asset_work="$relative_payload/${asset_name%.tar.gz}"
  mkdir -p "$asset_work"
  cp "$csv_source" "$asset_work/scenic_score_tiles_updates.csv"
  cp "$relative_payload/metadata.json" "$asset_work/metadata.json"
  tar -czf "$relative_caller/assets/$asset_name" \
    -C "$asset_work" scenic_score_tiles_updates.csv metadata.json
}

make_scenic_asset valid-scenic.tar.gz "$preflight_valid_csv"
printf '%s\n%s\n' \
  "${preflight_header/#h3_index,scenic_score/scenic_score,h3_index}" \
  "$preflight_row" > "$relative_payload/reordered.csv"
make_scenic_asset reordered-header.tar.gz "$relative_payload/reordered.csv"
printf '%s\n%s\n' \
  "${preflight_header/#h3_index,scenic_score/h3_index,h3_index}" \
  "$preflight_row" > "$relative_payload/duplicate.csv"
make_scenic_asset duplicate-header.tar.gz "$relative_payload/duplicate.csv"
sed '2s/,0\.9,/,NaN,/' "$preflight_valid_csv" > "$relative_payload/nan.csv"
make_scenic_asset nan-score.tar.gz "$relative_payload/nan.csv"
sed '2s/,0\.9,/,Infinity,/' "$preflight_valid_csv" > "$relative_payload/infinity.csv"
make_scenic_asset infinity-score.tar.gz "$relative_payload/infinity.csv"
sed '2s/,0\.9,/,1.0001,/' "$preflight_valid_csv" > "$relative_payload/out-of-range.csv"
make_scenic_asset out-of-range-score.tar.gz "$relative_payload/out-of-range.csv"
printf 'not a scenic release\n' > "$relative_payload/unrelated.txt"
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
chmod +x "$relative_bin/docker"
: > "$relative_docker_log"

run_scenic_asset_preflight() {
  local asset_name="$1"
  local expected_sha256="${2:-}"
  if [ -z "$expected_sha256" ]; then
    expected_sha256="$(sha256sum "$relative_caller/assets/$asset_name")"
    expected_sha256="${expected_sha256%% *}"
  fi
  (
    cd "$relative_caller"
    PATH="$relative_bin:$PATH" \
      SCENIC_DOCKER_LOG="$relative_docker_log" \
      MOODRIDE_DIR="$relative_project" \
      bash "$SCRIPT_DIR/deploy_scenic_release.sh" \
        --asset "assets/$asset_name" \
        --asset-sha256 "$expected_sha256" \
        --scoring-version 3.7-relative-fixture
  )
}

if run_scenic_asset_preflight valid-scenic.tar.gz \
    >"$relative_asset_fixture/valid.log" 2>&1; then
  fail "Scenic relative-asset preflight unexpectedly passed the injected Docker failure."
fi
[ "$(wc -l < "$relative_docker_log" | tr -d '[:space:]')" = "1" ] \
  || fail "Valid scenic preflight did not reach the isolated container copy."
grep -F " cp $relative_project/.deploy/scenic-releases/scenic-release." \
  "$relative_docker_log" >/dev/null \
  || fail "Scenic deploy did not copy the resolved relative asset from its unique extraction directory."

docker_calls_before="$(wc -l < "$relative_docker_log" | tr -d '[:space:]')"
if run_scenic_asset_preflight valid-scenic.tar.gz \
    0000000000000000000000000000000000000000000000000000000000000000 \
    >"$relative_asset_fixture/tampered.log" 2>&1; then
  fail "Checksum-tampered scenic asset unexpectedly passed preflight."
fi
grep -F "checksum does not match the published SHA-256 sidecar" \
  "$relative_asset_fixture/tampered.log" >/dev/null \
  || fail "Tampered scenic asset did not fail on its immutable checksum."

for malformed_case in \
  "reordered-header.tar.gz|header must match the exact ordered" \
  "duplicate-header.tar.gz|header must match the exact ordered" \
  "nan-score.tar.gz|non-finite or malformed scenic_score" \
  "infinity-score.tar.gz|non-finite or malformed scenic_score" \
  "out-of-range-score.tar.gz|out-of-range scenic_score" \
  "incomplete-scenic.tar.gz|unexpected or unsafe member"; do
  IFS='|' read -r malformed_asset expected_error <<EOF
$malformed_case
EOF
  malformed_log="$relative_asset_fixture/${malformed_asset}.log"
  if run_scenic_asset_preflight "$malformed_asset" >"$malformed_log" 2>&1; then
    fail "Malformed scenic asset unexpectedly passed preflight: $malformed_asset"
  fi
  grep -F "$expected_error" "$malformed_log" >/dev/null \
    || fail "Malformed scenic asset did not report its contract failure: $malformed_asset"
done
[ "$(wc -l < "$relative_docker_log" | tr -d '[:space:]')" = "$docker_calls_before" ] \
  || fail "Tampered or malformed scenic bytes reached Docker before rejection."
for extracted_dir in "$relative_project/.deploy/scenic-releases"/scenic-release.*; do
  [ ! -e "$extracted_dir" ] \
    || fail "Scenic deploy left a disposable extraction directory behind."
done

readiness_fixture="$temp_dir/scenic-readiness-rollback"
readiness_project="$readiness_fixture/project"
readiness_bin="$readiness_fixture/bin"
readiness_state="$readiness_fixture/state"
readiness_payload="$readiness_fixture/payload"
readiness_log="$readiness_fixture/docker.log"
mkdir -p "$readiness_project" "$readiness_bin" "$readiness_state" "$readiness_payload"
cat > "$readiness_project/.env.prod" <<'ENV'
POSTGRES_DB=scenic_fixture
POSTGRES_USER=postgres
REDIS_PASSWORD=scenic-fixture-password
MOODRIDE_SCENIC_SCORING_VERSION=3.6-healthy
MOODRIDE_ROAD_DATASET_REVISION=scenic-fixture-road
MOODRIDE_ROAD_DATASET_FINGERPRINT=0000000000000000000000000000000000000000000000000000000000000000
MOODRIDE_ROAD_ANCHOR_CACHE_SCHEMA=scenic-fixture-anchor
ENV
: > "$readiness_project/docker-compose.prod.yml"
sed 's/3\.7-sql-fixture/3.7-readiness-fixture/' "$scenic_csv" \
  > "$readiness_payload/scenic_score_tiles_updates.csv"
printf '{"scoringVersion":"3.7-readiness-fixture"}\n' \
  > "$readiness_payload/metadata.json"
readiness_asset="$readiness_fixture/scenic-readiness.tar.gz"
tar -czf "$readiness_asset" -C "$readiness_payload" \
  scenic_score_tiles_updates.csv metadata.json
readiness_asset_sha256="$(sha256sum "$readiness_asset")"
readiness_asset_sha256="${readiness_asset_sha256%% *}"
printf 'running\n' > "$readiness_state/caddy"
printf '0\n' > "$readiness_state/psql-heredocs"
: > "$readiness_log"

cat > "$readiness_bin/docker" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
{
  printf '%s' "$*"
  printf '\n'
} | tr '\n' ' ' >> "$SCENIC_DOCKER_LOG"
printf '\n' >> "$SCENIC_DOCKER_LOG"
args="$*"
if [ "${1:-}" = "inspect" ]; then
  container_id="${!#}"
  if printf '%s' "$args" | grep -F "Config.Env" >/dev/null; then
    scenic_version="$(grep '^MOODRIDE_SCENIC_SCORING_VERSION=' \
      "$SCENIC_PROJECT/.env.prod" | cut -d= -f2-)"
    printf 'MOODRIDE_SCENIC_SCORING_VERSION=%s\n' "$scenic_version"
    exit 0
  fi
  if [ "$container_id" = "fixture-caddy" ]; then
    [ "$(cat "$SCENIC_STATE/caddy")" = "running" ] && printf 'true\n' || printf 'false\n'
  else
    printf 'true\n'
  fi
  exit 0
fi

if [ "${1:-}" != "compose" ]; then
  exit 99
fi
case "$args" in
  *" ps -q "*)
    service="${args##* ps -q }"
    printf 'fixture-%s\n' "$service"
    ;;
  *" stop "*)
    if printf '%s' "$args" | grep -F "caddy" >/dev/null; then
      printf 'stopped\n' > "$SCENIC_STATE/caddy"
    fi
    ;;
  *" up "*" caddy")
    printf 'running\n' > "$SCENIC_STATE/caddy"
    ;;
  *" up "*)
    ;;
  *" cp "*)
    ;;
  *" exec -T postgres psql "*)
    case "$args" in
      *"COPY ("*"TO STDOUT"*)
        printf '%s\n' '8928308280fffff,0.2,0.1,0.2,0.3,0.4,0.5,0.6,0.7,0.8,0.1,0.2,0.0,0.1,0.8,0.2,,3.6-healthy,0.3,0.4,0.5,0.6,0.7,0.8,0.9'
        ;;
      *"SELECT COUNT(*) FROM route_jobs WHERE status IN"*)
        printf '0\n'
        ;;
      *"SELECT id FROM route_jobs"*)
        printf '11111111-1111-4111-8111-111111111111\n'
        ;;
      *"SELECT status FROM route_jobs"*)
        printf 'COMPLETED\n'
        ;;
      *"primary_ready_at IS NOT NULL"*)
        printf '1\n'
        ;;
      *"SELECT COUNT(*) FROM routes"*)
        printf '1\n'
        ;;
      *"--command"*)
        ;;
      *)
        cat >/dev/null
        heredoc_count="$(cat "$SCENIC_STATE/psql-heredocs")"
        printf '%s\n' "$((heredoc_count + 1))" > "$SCENIC_STATE/psql-heredocs"
        ;;
    esac
    ;;
  *" exec -T route-api wget "*)
    if printf '%s' "$args" | grep -F -- "--post-data=" >/dev/null; then
      printf '{"jobId":"11111111-1111-4111-8111-111111111111"}\n'
    elif grep -Fx 'MOODRIDE_SCENIC_SCORING_VERSION=3.7-readiness-fixture' \
        "$SCENIC_PROJECT/.env.prod" >/dev/null; then
      exit 28
    fi
    ;;
  *" exec -T route-worker wget "*)
    ;;
  *" exec -T "*" redis "*)
    ;;
  *" exec -T postgres sh "*)
    ;;
  *)
    exit 98
    ;;
esac
SH
chmod +x "$readiness_bin/docker"

if PATH="$readiness_bin:$PATH" \
    SCENIC_DOCKER_LOG="$readiness_log" \
    SCENIC_PROJECT="$readiness_project" \
    SCENIC_STATE="$readiness_state" \
    MOODRIDE_DIR="$readiness_project" \
    DRAIN_TIMEOUT_SECONDS=2 \
    DRAIN_POLL_SECONDS=1 \
    HEALTHCHECK_TIMEOUT_SECONDS=1 \
    HEALTHCHECK_POLL_SECONDS=1 \
    ROUTE_SMOKE_TIMEOUT_SECONDS=2 \
    bash "$SCRIPT_DIR/deploy_scenic_release.sh" \
      --asset "$readiness_asset" \
      --asset-sha256 "$readiness_asset_sha256" \
      --scoring-version 3.7-readiness-fixture \
      >"$readiness_fixture/deploy.log" 2>&1; then
  fail "Readiness-injected scenic promotion unexpectedly succeeded."
fi
grep -F "Previous scenic database and healthy runtime were restored; ingress reopened." \
  "$readiness_fixture/deploy.log" >/dev/null \
  || fail "Readiness failure did not report successful rollback and reopen."
grep -E ' cp .*/scenic-rollback\.[A-Za-z0-9]+\.csv postgres:/tmp/scenic_score_tiles_rollback\.csv' \
  "$readiness_log" >/dev/null \
  || fail "Readiness failure did not restore the pre-cutover scenic database snapshot."
grep -Fx 'MOODRIDE_SCENIC_SCORING_VERSION=3.6-healthy' \
  "$readiness_project/.env.prod" >/dev/null \
  || fail "Readiness failure did not restore the exact prior runtime environment."
[ "$(cat "$readiness_state/caddy")" = "running" ] \
  || fail "Readiness failure stranded ingress closed after healthy rollback."
[ "$(cat "$readiness_state/psql-heredocs")" = "2" ] \
  || fail "Readiness fixture did not execute candidate mutation and snapshot restoration."
smoke_log_line="$(grep -n -- '--post-data=' "$readiness_log" | tail -n 1 | cut -d: -f1)"
reopen_log_line="$(grep -n ' up -d --no-deps caddy' "$readiness_log" | tail -n 1 | cut -d: -f1)"
case "$smoke_log_line:$reopen_log_line" in
  *[!0-9:]*|:*|*:) fail "Could not establish readiness-smoke/reopen ordering." ;;
esac
[ "$smoke_log_line" -lt "$reopen_log_line" ] \
  || fail "Ingress reopened before the restored route smoke completed."
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
