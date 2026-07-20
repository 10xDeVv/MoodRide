#!/usr/bin/env bash
# Shared PostgreSQL backup validation and non-destructive database promotion helpers.
# The caller supplies compose_env, fail, POSTGRES_USER, and POSTGRES_DB.

capture_flyway_history_db() {
  local env_file="$1"
  local database="$2"
  local output_file="$3"
  psql_query_db "$env_file" "$database" "
    SELECT installed_rank::text || '|' || COALESCE(version, '') || '|' ||
           description || '|' || type || '|' || script || '|' ||
           COALESCE(checksum::text, '') || '|' || success::text
    FROM public.flyway_schema_history
    ORDER BY installed_rank;
  " > "$output_file"
  [ -s "$output_file" ] || fail "Flyway history snapshot is empty for database $database."
}

capture_flyway_history() {
  capture_flyway_history_db "$1" "$POSTGRES_DB" "$2"
}

capture_database_catalog() {
  local env_file="$1"
  local database="$2"
  local output_file="$3"
  psql_query_db "$env_file" "$database" "
    WITH catalog_entry AS (
      SELECT 'relation'::text AS object_kind,
             n.nspname || '.' || c.relname AS object_name,
             c.relkind::text || '|' || c.relpersistence::text AS definition
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
      WHERE n.nspname = 'public'
        AND c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
      UNION ALL
      SELECT 'column',
             cols.table_schema || '.' || cols.table_name || '.' || cols.column_name,
             cols.ordinal_position::text || '|' || cols.data_type || '|' ||
             cols.udt_schema || '.' || cols.udt_name || '|' || cols.is_nullable || '|' ||
             COALESCE(cols.column_default, '') || '|' || cols.is_identity || '|' ||
             cols.is_generated || '|' || COALESCE(cols.generation_expression, '')
      FROM information_schema.columns cols
      WHERE cols.table_schema = 'public'
      UNION ALL
      SELECT 'constraint',
             n.nspname || '.' || c.relname || '.' || con.conname,
             con.contype::text || '|' || con.convalidated::text || '|' ||
             pg_get_constraintdef(con.oid, true)
      FROM pg_constraint con
      JOIN pg_class c ON c.oid = con.conrelid
      JOIN pg_namespace n ON n.oid = c.relnamespace
      WHERE n.nspname = 'public'
      UNION ALL
      SELECT 'index', schemaname || '.' || indexname, indexdef
      FROM pg_indexes
      WHERE schemaname = 'public'
      UNION ALL
      SELECT 'trigger',
             n.nspname || '.' || c.relname || '.' || t.tgname,
             pg_get_triggerdef(t.oid, true)
      FROM pg_trigger t
      JOIN pg_class c ON c.oid = t.tgrelid
      JOIN pg_namespace n ON n.oid = c.relnamespace
      WHERE n.nspname = 'public' AND NOT t.tgisinternal
    )
    SELECT object_kind || '|' || object_name || '|' || definition
    FROM catalog_entry
    ORDER BY object_kind, object_name, definition;
  " > "$output_file"
  [ -s "$output_file" ] || fail "Catalog snapshot is empty for database $database."
}

validate_release_invariants() {
  local env_file="$1"
  local database="$2"
  local result
  result="$(psql_query_db "$env_file" "$database" "
DO \$\$
DECLARE
    rank38 INTEGER;
    rank39 INTEGER;
    rank40 INTEGER;
    rank41 INTEGER;
    v39_count INTEGER;
    v40_count INTEGER;
    v41_count INTEGER;
    v39_column_count INTEGER;
    v39_index_count INTEGER;
    v40_column_count INTEGER := 0;
    v40_constraint_count INTEGER := 0;
    v41_column_count INTEGER := 0;
    v41_constraint_count INTEGER := 0;
BEGIN
    IF (SELECT COUNT(*) FROM public.flyway_schema_history
        WHERE version = '38' AND description = 'add road segment stable identity'
          AND checksum = 1443186875 AND success IS TRUE) <> 1 THEN
        RAISE EXCEPTION 'Release lineage failed: production V38 road identity migration is absent or divergent';
    END IF;
    SELECT installed_rank INTO rank38
    FROM public.flyway_schema_history
    WHERE version = '38' AND description = 'add road segment stable identity'
      AND checksum = 1443186875 AND success IS TRUE;

    IF EXISTS (
        SELECT 1 FROM public.flyway_schema_history
        WHERE version IN ('39', '40', '41')
          AND (
            success IS NOT TRUE
            OR (version = '39' AND (description <> 'add route job lifecycle fencing' OR checksum <> -2068215762))
            OR (version = '40' AND (description <> 'add route job dispatch outbox' OR checksum <> -1969470883))
            OR (version = '41' AND (description <> 'add route job terminal event outbox' OR checksum <> -819117119))
          )
    ) THEN
        RAISE EXCEPTION 'Release lineage failed: V39/V40/V41 description or checksum mismatch';
    END IF;

    SELECT COUNT(*) FILTER (WHERE version = '39' AND success IS TRUE),
           COUNT(*) FILTER (WHERE version = '40' AND success IS TRUE),
           COUNT(*) FILTER (WHERE version = '41' AND success IS TRUE)
    INTO v39_count, v40_count, v41_count
    FROM public.flyway_schema_history;
    IF NOT (
        (v39_count = 0 AND v40_count = 0 AND v41_count = 0)
        OR (v39_count = 1 AND v40_count = 1 AND v41_count = 1)
    ) THEN
        RAISE EXCEPTION 'Release lineage failed: partial or duplicate V39/V40/V41 history';
    END IF;

    IF v39_count = 1 THEN
        SELECT installed_rank INTO rank39 FROM public.flyway_schema_history WHERE version = '39' AND success IS TRUE;
        SELECT installed_rank INTO rank40 FROM public.flyway_schema_history WHERE version = '40' AND success IS TRUE;
        SELECT installed_rank INTO rank41 FROM public.flyway_schema_history WHERE version = '41' AND success IS TRUE;
        IF rank38 >= rank39 OR rank39 >= rank40 OR rank40 >= rank41 THEN
            RAISE EXCEPTION 'Release lineage failed: V38/V39/V40/V41 order is divergent';
        END IF;
    END IF;

    IF EXISTS (SELECT 1 FROM public.route_jobs WHERE status IN ('QUEUED', 'PROCESSING', 'PRIMARY_READY')) THEN
        RAISE EXCEPTION 'Recovery invariant failed: active route jobs exist';
    END IF;

    SELECT COUNT(*) INTO v39_column_count
    FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = 'route_jobs'
      AND column_name IN ('primary_ready_at', 'state_revision', 'option_revision', 'option_count',
                          'options_complete', 'lease_token', 'lease_expires_at', 'row_version');
    SELECT (CASE WHEN to_regclass('public.ux_routes_job_profile') IS NULL THEN 0 ELSE 1 END)
         + (CASE WHEN to_regclass('public.idx_route_jobs_active_lease') IS NULL THEN 0 ELSE 1 END)
    INTO v39_index_count;

    IF (v39_count = 1 AND (v39_column_count <> 8 OR v39_index_count <> 2))
       OR (v39_count = 0 AND (v39_column_count <> 0 OR v39_index_count <> 0)) THEN
        RAISE EXCEPTION 'Recovery invariant failed: V39 schema/history drift';
    END IF;
    IF v39_count = 1 AND EXISTS (
        SELECT 1 FROM public.routes WHERE route_profile IS NOT NULL
        GROUP BY job_id, route_profile HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'Recovery invariant failed: duplicate persisted route profile exists';
    END IF;

    IF to_regclass('public.route_job_dispatches') IS NOT NULL THEN
        SELECT COUNT(*) INTO v40_column_count
        FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'route_job_dispatches'
          AND column_name IN ('job_id', 'created_at', 'next_attempt_at', 'attempt_count',
                              'sent_at', 'lease_token', 'lease_expires_at', 'last_error');
        SELECT COUNT(*) INTO v40_constraint_count
        FROM information_schema.table_constraints
        WHERE table_schema = 'public' AND table_name = 'route_job_dispatches'
          AND constraint_name IN ('route_job_dispatches_pkey', 'route_job_dispatches_job_id_fkey',
              'route_job_dispatches_attempt_count_check', 'ck_route_job_dispatch_lease_pair',
              'ck_route_job_dispatch_sent_not_leased');
        IF EXISTS (SELECT 1 FROM public.route_job_dispatches WHERE sent_at IS NULL) THEN
            RAISE EXCEPTION 'Recovery invariant failed: unsent route-job dispatches exist';
        END IF;
    END IF;
    IF (v40_count = 1 AND (to_regclass('public.route_job_dispatches') IS NULL
                           OR to_regclass('public.idx_route_job_dispatch_due') IS NULL
                           OR v40_column_count <> 8 OR v40_constraint_count <> 5))
       OR (v40_count = 0 AND (to_regclass('public.route_job_dispatches') IS NOT NULL
                              OR to_regclass('public.idx_route_job_dispatch_due') IS NOT NULL)) THEN
        RAISE EXCEPTION 'Recovery invariant failed: V40 schema/history drift';
    END IF;

    IF to_regclass('public.route_job_terminal_events') IS NOT NULL THEN
        SELECT COUNT(*) INTO v41_column_count
        FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'route_job_terminal_events'
          AND column_name IN ('event_id', 'job_id', 'state_revision', 'event_type', 'terminal_status',
              'original_payload', 'created_at', 'next_attempt_at', 'attempt_count', 'delivered_at',
              'lease_token', 'lease_expires_at', 'last_error');
        SELECT COUNT(*) INTO v41_constraint_count
        FROM information_schema.table_constraints
        WHERE table_schema = 'public' AND table_name = 'route_job_terminal_events'
          AND constraint_name IN ('route_job_terminal_events_pkey', 'route_job_terminal_events_job_id_fkey',
              'route_job_terminal_events_state_revision_check', 'route_job_terminal_events_event_type_check',
              'route_job_terminal_events_terminal_status_check', 'route_job_terminal_events_attempt_count_check',
              'uq_route_job_terminal_event_identity', 'ck_route_job_terminal_event_lease_pair',
              'ck_route_job_terminal_event_delivered_not_leased', 'ck_route_job_terminal_event_dlq_status');
        IF EXISTS (SELECT 1 FROM public.route_job_terminal_events WHERE delivered_at IS NULL) THEN
            RAISE EXCEPTION 'Recovery invariant failed: undelivered terminal events exist';
        END IF;
    END IF;
    IF (v41_count = 1 AND (to_regclass('public.route_job_terminal_events') IS NULL
                           OR to_regclass('public.idx_route_job_terminal_event_due') IS NULL
                           OR v41_column_count <> 13 OR v41_constraint_count <> 10))
       OR (v41_count = 0 AND (to_regclass('public.route_job_terminal_events') IS NOT NULL
                              OR to_regclass('public.idx_route_job_terminal_event_due') IS NOT NULL)) THEN
        RAISE EXCEPTION 'Recovery invariant failed: V41 schema/history drift';
    END IF;
END
\$\$;
SELECT 'release-invariants-ok';
  ")"
  result="$(printf '%s' "$result" | tr -d '[:space:]')"
  [ "$result" = "release-invariants-ok" ] \
    || fail "Recovery invariant validation returned an unexpected marker for database $database."
}

create_recovery_backup() {
  local env_file="$1"
  local destination="$2"
  local temp_dump="${destination}.tmp"
  local checksum

  rm -f "$temp_dump"
  compose_env "$env_file" exec -T postgres \
    pg_dump --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
      --format=custom --no-owner --no-privileges > "$temp_dump"
  [ -s "$temp_dump" ] || fail "Database backup is empty."
  checksum="$(cksum < "$temp_dump")"
  [ -n "$checksum" ] || fail "Could not checksum the database backup."
  mv "$temp_dump" "$destination"
  printf '%s\n' "$checksum" > "${destination}.cksum"
  echo "Pre-migration database backup created at $destination"
}

verify_recovery_backup() {
  local backup="$1"
  local expected_checksum actual_checksum
  [ -s "$backup" ] || return 1
  [ -s "${backup}.cksum" ] || return 1
  expected_checksum="$(cat "${backup}.cksum")"
  actual_checksum="$(cksum < "$backup")"
  [ "$actual_checksum" = "$expected_checksum" ]
}

database_exists() {
  local env_file="$1"
  local database="$2"
  local result
  result="$(printf '%s\n' \
    "SELECT CASE WHEN EXISTS (SELECT 1 FROM pg_catalog.pg_database WHERE datname = :'target_database') THEN 1 ELSE 0 END;" \
    | compose_env "$env_file" exec -T postgres \
        psql --username "$POSTGRES_USER" --dbname postgres --no-psqlrc --quiet \
          --tuples-only --no-align --set ON_ERROR_STOP=1 \
          --set "target_database=$database" --file=-)" || return 1
  result="$(printf '%s' "$result" | tr -d '[:space:]')"
  [ "$result" = "1" ]
}

database_oid() {
  local env_file="$1"
  local database="$2"
  local result
  result="$(printf '%s\n' \
    "SELECT oid::text FROM pg_catalog.pg_database WHERE datname = :'target_database';" \
    | compose_env "$env_file" exec -T postgres \
        psql --username "$POSTGRES_USER" --dbname postgres --no-psqlrc --quiet \
          --tuples-only --no-align --set ON_ERROR_STOP=1 \
          --set "target_database=$database" --file=-)" || return 1
  result="$(printf '%s' "$result" | tr -d '[:space:]')"
  case "$result" in
    ''|*[!0-9]*) return 1 ;;
  esac
  printf '%s\n' "$result"
}

synchronize_dataset_release_identity() {
  local source_env="$1"
  local database="$2"
  shift 2
  local scenic_version road_fingerprint previous_fingerprint road_revision road_identity_marker
  local anchor_schema target_env identity_temp

  scenic_version="$(psql_query_db "$source_env" "$database" "
DO \$\$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM public.scenic_score_tiles) THEN
        RAISE EXCEPTION 'Scenic scoring dataset is empty';
    END IF;
    IF EXISTS (SELECT 1 FROM public.scenic_score_tiles WHERE scoring_version IS NULL OR btrim(scoring_version) = '') THEN
        RAISE EXCEPTION 'Scenic scoring dataset contains an unsigned version';
    END IF;
    IF (SELECT COUNT(DISTINCT btrim(scoring_version)) FROM public.scenic_score_tiles) <> 1 THEN
        RAISE EXCEPTION 'Scenic scoring dataset has more than one active version';
    END IF;
END
\$\$;
SELECT DISTINCT btrim(scoring_version) FROM public.scenic_score_tiles;
  ")"
  scenic_version="$(printf '%s' "$scenic_version" | tr -d '\r\n')"
  printf '%s' "$scenic_version" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9._+-]{0,127}$' \
    || fail "Active scenic scoring version is not a nonblank signed identifier."
  printf '%s' "$scenic_version" | grep -Eq '^3\.7([._+-][A-Za-z0-9][A-Za-z0-9._+-]*)?$' \
    || fail "Active scenic scoring version must identify a complete signed 3.7 release."
  [ -n "${EXPECTED_SCENIC_SCORING_VERSION:-}" ] \
    || fail "Expected scenic scoring identity is required before synchronization."
  [ "$scenic_version" = "$EXPECTED_SCENIC_SCORING_VERSION" ] \
    || fail "Live scenic scoring identity differs from the accepted release gate."

  road_identity_marker="$(psql_query_db "$source_env" "$database" "
SELECT CASE WHEN
  EXISTS (SELECT 1 FROM public.road_segments)
  AND NOT EXISTS (
    SELECT 1 FROM public.road_segments
    WHERE stable_identity_key IS NULL OR btrim(stable_identity_key) = ''
  )
THEN 'road-identity-ok' ELSE 'road-identity-divergent' END;
  ")"
  road_identity_marker="$(printf '%s' "$road_identity_marker" | tr -d '[:space:]')"
  [ "$road_identity_marker" = "road-identity-ok" ] \
    || fail "Road dataset is empty or lacks complete V38 stable identity coverage."

  if ! road_fingerprint="$(
    compose_env "$source_env" exec -T postgres \
      psql --username "$POSTGRES_USER" --dbname "$database" \
        --no-psqlrc --quiet --tuples-only --no-align --set ON_ERROR_STOP=1 \
        --command "
COPY (
  SELECT payload
  FROM (
    SELECT stable_identity_key,
           jsonb_build_array(
             stable_identity_key,
             osm_way_id,
             encode(ST_AsEWKB(ST_Normalize(geometry), 'XDR'), 'hex'),
             h3_tile_index,
             length_meters,
             speed_limit_kmh,
             road_type,
             surface,
             curvature,
             elevation_change
           )::text AS payload
    FROM public.road_segments
    WHERE stable_identity_key IS NOT NULL
      AND btrim(stable_identity_key) <> ''
  ) canonical_road_segments
  ORDER BY stable_identity_key COLLATE \"C\", payload COLLATE \"C\"
) TO STDOUT;
        " | sha256sum
  )"; then
    fail "Could not stream and fingerprint the canonical road dataset."
  fi
  road_fingerprint="${road_fingerprint%% *}"
  printf '%s' "$road_fingerprint" | grep -Eq '^[0-9a-f]{64}$' \
    || fail "Road dataset fingerprint is not a sha256 value."
  [ -n "${EXPECTED_ROAD_DATASET_FINGERPRINT:-}" ] \
    || fail "Expected road dataset fingerprint is required before synchronization."
  [ "$road_fingerprint" = "$EXPECTED_ROAD_DATASET_FINGERPRINT" ] \
    || fail "Live road dataset fingerprint differs from the accepted release gate."

  previous_fingerprint="$(get_env_var MOODRIDE_ROAD_DATASET_FINGERPRINT "$source_env")"
  road_revision="$(get_env_var MOODRIDE_ROAD_DATASET_REVISION "$source_env")"
  if [ -z "$road_revision" ] || [ "$previous_fingerprint" != "$road_fingerprint" ]; then
    road_revision="road-${road_fingerprint}"
  fi
  printf '%s' "$road_revision" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9._+-]{0,127}$' \
    || fail "Road dataset revision is not a valid nonblank identifier."

  anchor_schema="$(get_env_var MOODRIDE_ROAD_ANCHOR_CACHE_SCHEMA "$source_env")"
  if [ -z "$anchor_schema" ]; then
    anchor_schema="v1"
  fi
  printf '%s' "$anchor_schema" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$' \
    || fail "Road anchor cache schema is not a valid nonblank identifier."

  for target_env in "$source_env" "$@"; do
    identity_temp="${target_env}.dataset-identity.tmp"
    rm -f "$identity_temp"
    cp -p "$target_env" "$identity_temp"
    set_env_var MOODRIDE_SCENIC_SCORING_VERSION "$scenic_version" "$identity_temp"
    set_env_var MOODRIDE_ROAD_DATASET_FINGERPRINT "$road_fingerprint" "$identity_temp"
    set_env_var MOODRIDE_ROAD_DATASET_REVISION "$road_revision" "$identity_temp"
    set_env_var MOODRIDE_ROAD_ANCHOR_CACHE_SCHEMA "$anchor_schema" "$identity_temp"
    chmod 600 "$identity_temp"
    mv "$identity_temp" "$target_env"
  done
  echo "Validated and persisted scenic, road-dataset, and road-anchor cache identities."
}


evict_scenic_anchor_cache_namespaces() {
  local env_file="$1"
  local redis_password
  redis_password="$(get_env_var REDIS_PASSWORD "$env_file")"
  [ -n "$redis_password" ] || fail "REDIS_PASSWORD is missing; refusing incomplete cache cutover."
  compose_env "$env_file" exec -T -e REDISCLI_AUTH="$redis_password" redis sh -ceu '
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
  unset redis_password
  echo "Evicted versioned and legacy scenic-tile and road-anchor cache namespaces."
}

validate_recovery_database() {
  local env_file="$1"
  local database="$2"
  local expected_history="$3"
  local expected_catalog="$4"
  local actual_history="${expected_history}.${database}.tmp"
  local actual_catalog="${expected_catalog}.${database}.tmp"

  database_exists "$env_file" "$database" || return 1
  if ! capture_flyway_history_db "$env_file" "$database" "$actual_history" \
     || ! cmp -s "$expected_history" "$actual_history" \
     || ! capture_database_catalog "$env_file" "$database" "$actual_catalog" \
     || ! cmp -s "$expected_catalog" "$actual_catalog" \
     || ! validate_release_invariants "$env_file" "$database"; then
    rm -f "$actual_history" "$actual_catalog"
    return 1
  fi
  rm -f "$actual_history" "$actual_catalog"
}

create_validated_recovery_database() {
  local env_file="$1"
  local backup="$2"
  local recovery_database="$3"
  local expected_history="$4"
  local expected_catalog="$5"
  local marker="${backup}.recovery-db"
  local marker_tmp="${marker}.tmp"

  verify_recovery_backup "$backup" || fail "Recovery backup checksum verification failed."
  if database_exists "$env_file" "$recovery_database"; then
    fail "Recovery database already exists and will not be overwritten: $recovery_database"
  fi
  compose_env "$env_file" exec -T postgres \
    createdb --username "$POSTGRES_USER" --owner "$POSTGRES_USER" "$recovery_database"
  if ! compose_env "$env_file" exec -T postgres \
      pg_restore --username "$POSTGRES_USER" --dbname "$recovery_database" \
        --exit-on-error --no-owner --no-privileges < "$backup"; then
    echo "Full scratch restore failed; current database $POSTGRES_DB was not changed. Partial scratch database retained as $recovery_database." >&2
    return 1
  fi
  if ! validate_recovery_database "$env_file" "$recovery_database" \
      "$expected_history" "$expected_catalog"; then
    echo "Scratch restore validation failed; current database $POSTGRES_DB was not changed. Scratch database retained as $recovery_database." >&2
    return 1
  fi
  printf '%s\n' "$recovery_database" > "$marker_tmp"
  mv "$marker_tmp" "$marker"
  echo "Full scratch restore, Flyway history, catalog, and release invariants validated in $recovery_database."
}

run_database_rename() {
  local env_file="$1"
  local old_name="$2"
  local new_name="$3"
  compose_env "$env_file" exec -T postgres \
    psql --username "$POSTGRES_USER" --dbname postgres --no-psqlrc --quiet \
      --set ON_ERROR_STOP=1 --set "old_database=$old_name" --set "new_database=$new_name" \
      --file=- <<'SQL'
SELECT pg_catalog.pg_terminate_backend(pid)
FROM pg_catalog.pg_stat_activity
WHERE datname = :'old_database' AND pid <> pg_catalog.pg_backend_pid();
SELECT pg_catalog.format(
    'ALTER DATABASE %I RENAME TO %I',
    :'old_database',
    :'new_database'
)
\gexec
SQL
}
retry_database_rename() {
  local env_file="$1"
  local old_name="$2"
  local new_name="$3"
  local attempt
  for attempt in 1 2 3; do
    if run_database_rename "$env_file" "$old_name" "$new_name"; then
      return 0
    fi
    compose_env "$env_file" exec -T postgres \
      psql --username "$POSTGRES_USER" --dbname postgres --no-psqlrc --quiet \
        --set ON_ERROR_STOP=1 --set "target_database=$old_name" --file=- <<'SQL' || true
SELECT pg_catalog.pg_terminate_backend(pid)
FROM pg_catalog.pg_stat_activity
WHERE datname = :'target_database' AND pid <> pg_catalog.pg_backend_pid();
SQL
    sleep 1
  done
  return 1
}

allow_database_connections() {
  local env_file="$1"
  local database="$2"
  compose_env "$env_file" exec -T postgres \
    psql --username "$POSTGRES_USER" --dbname postgres --no-psqlrc --quiet \
      --set ON_ERROR_STOP=1 --set "target_database=$database" --file=- <<'SQL'
SELECT pg_catalog.format(
    'ALTER DATABASE %I WITH ALLOW_CONNECTIONS true',
    :'target_database'
)
\gexec
SQL
}

reclaim_database_name() {
  local env_file="$1"
  local old_name="$2"
  local new_name="$3"

  database_exists "$env_file" "$old_name" || return 1
  if database_exists "$env_file" "$new_name"; then
    return 1
  fi

  if ! compose_env "$env_file" exec -T postgres \
      psql --username "$POSTGRES_USER" --dbname postgres --no-psqlrc --quiet \
        --set ON_ERROR_STOP=1 --set "old_database=$old_name" \
        --set "new_database=$new_name" --file=- <<'SQL'
SELECT pg_catalog.format(
    'ALTER DATABASE %I WITH ALLOW_CONNECTIONS false',
    :'old_database'
)
\gexec
SELECT pg_catalog.pg_terminate_backend(pid)
FROM pg_catalog.pg_stat_activity
WHERE datname = :'old_database' AND pid <> pg_catalog.pg_backend_pid();
SELECT pg_catalog.format(
    'ALTER DATABASE %I RENAME TO %I',
    :'old_database',
    :'new_database'
)
\gexec
SELECT pg_catalog.format(
    'ALTER DATABASE %I WITH ALLOW_CONNECTIONS true',
    :'new_database'
)
\gexec
SQL
  then
    if database_exists "$env_file" "$new_name" \
       && ! database_exists "$env_file" "$old_name"; then
      allow_database_connections "$env_file" "$new_name" || return 1
      return 0
    fi
    if database_exists "$env_file" "$old_name"; then
      allow_database_connections "$env_file" "$old_name" || true
    fi
    return 1
  fi

  database_exists "$env_file" "$new_name" \
    && ! database_exists "$env_file" "$old_name"
}

verify_failed_promotion_rollback_invariant() {
  local env_file="$1"
  local prior_database_oid="$2"
  local recovery_database_oid="$3"
  local quarantine_database="$4"
  local failed_recovery_database="$5"
  local result
  result="$(printf '%s\n' "
SELECT CASE WHEN
  EXISTS (
    SELECT 1
    FROM pg_catalog.pg_database
    WHERE datname = :'canonical_database'
      AND oid::text = :'prior_database_oid'
      AND datallowconn
  )
  AND EXISTS (
    SELECT 1
    FROM pg_catalog.pg_database
    WHERE datname = :'failed_recovery_database'
      AND oid::text = :'recovery_database_oid'
      AND datallowconn
  )
  AND NOT EXISTS (
    SELECT 1
    FROM pg_catalog.pg_database
    WHERE datname = :'quarantine_database'
  )
THEN 'failed-promotion-rollback-ok'
ELSE 'failed-promotion-rollback-divergent'
END;" | compose_env "$env_file" exec -T postgres \
    psql --username "$POSTGRES_USER" --dbname postgres --no-psqlrc --quiet \
      --tuples-only --no-align --set ON_ERROR_STOP=1 \
      --set "canonical_database=$POSTGRES_DB" \
      --set "prior_database_oid=$prior_database_oid" \
      --set "recovery_database_oid=$recovery_database_oid" \
      --set "quarantine_database=$quarantine_database" \
      --set "failed_recovery_database=$failed_recovery_database" \
      --file=-)" || return 1
  result="$(printf '%s' "$result" | tr -d '[:space:]')"
  [ "$result" = "failed-promotion-rollback-ok" ]
}


promote_validated_recovery_database() {
  local env_file="$1"
  local recovery_database="$2"
  local quarantine_database="$3"
  local backup="$4"
  local expected_history="$5"
  local expected_catalog="$6"
  local failed_recovery_database="${quarantine_database}_failed"
  local failed_suffix=0
  local prior_database_oid recovery_database_oid

  verify_recovery_backup "$backup" || fail "Recovery backup checksum no longer verifies."
  validate_recovery_database "$env_file" "$recovery_database" \
    "$expected_history" "$expected_catalog" \
    || fail "Validated recovery database no longer passes Flyway/catalog/invariant checks."
  database_exists "$env_file" "$POSTGRES_DB" \
    || fail "Current database $POSTGRES_DB is unavailable; refusing recovery promotion."
  prior_database_oid="$(database_oid "$env_file" "$POSTGRES_DB")" \
    || fail "Could not identify the current database before recovery promotion."
  recovery_database_oid="$(database_oid "$env_file" "$recovery_database")" \
    || fail "Could not identify the recovery database before promotion."
  [ "$prior_database_oid" != "$recovery_database_oid" ] \
    || fail "Current and recovery database identities unexpectedly match."
  if database_exists "$env_file" "$quarantine_database"; then
    fail "Quarantine database already exists and will not be overwritten: $quarantine_database"
  fi
  while database_exists "$env_file" "$failed_recovery_database"; do
    failed_suffix=$((failed_suffix + 1))
    [ "$failed_suffix" -le 100 ] \
      || fail "Could not allocate a unique quarantine name for failed recovery."
    failed_recovery_database="${quarantine_database}_failed_${failed_suffix}"
  done

  retry_database_rename "$env_file" "$POSTGRES_DB" "$quarantine_database" || return 1
  if ! retry_database_rename "$env_file" "$recovery_database" "$POSTGRES_DB"; then
    echo "Recovery promotion failed after quarantine; restoring the untouched current database name." >&2
    retry_database_rename "$env_file" "$quarantine_database" "$POSTGRES_DB" \
      || fail "CRITICAL: could not restore quarantined database to canonical name after promotion failure."
    return 1
  fi

  if ! validate_recovery_database "$env_file" "$POSTGRES_DB" \
      "$expected_history" "$expected_catalog"; then
    echo "Promoted recovery failed validation; returning the quarantined current database to service." >&2
    while database_exists "$env_file" "$failed_recovery_database"; do
      failed_suffix=$((failed_suffix + 1))
      [ "$failed_suffix" -le 100 ] \
        || fail "Could not allocate a unique quarantine name for failed recovery."
      failed_recovery_database="${quarantine_database}_failed_${failed_suffix}"
    done
    if ! retry_database_rename "$env_file" "$POSTGRES_DB" "$failed_recovery_database"; then
      echo "Failed recovery rename did not succeed after retries; quiescing connections and reclaiming the canonical name." >&2
      reclaim_database_name "$env_file" "$POSTGRES_DB" "$failed_recovery_database" \
        || fail "CRITICAL: could not move the failed recovery away from the canonical database name."
    fi
    if ! retry_database_rename "$env_file" "$quarantine_database" "$POSTGRES_DB"; then
      echo "Quarantine restore rename did not succeed after retries; quiescing connections and reclaiming the canonical name." >&2
      reclaim_database_name "$env_file" "$quarantine_database" "$POSTGRES_DB" \
        || fail "CRITICAL: could not restore the prior quarantined database to the canonical name."
    fi
    verify_failed_promotion_rollback_invariant "$env_file" \
      "$prior_database_oid" "$recovery_database_oid" \
      "$quarantine_database" "$failed_recovery_database" \
      || fail "CRITICAL: failed recovery rollback invariant is divergent; refusing further database changes."
    return 1
  fi

  printf '%s\n' "$POSTGRES_DB" > "${backup}.recovery-db.tmp"
  mv "${backup}.recovery-db.tmp" "${backup}.recovery-db"
  printf '%s\n' "$quarantine_database" > "${backup}.quarantine-db.tmp"
  mv "${backup}.quarantine-db.tmp" "${backup}.quarantine-db"
  echo "Validated recovery promoted as $POSTGRES_DB; previous database preserved in quarantine as $quarantine_database."
}
