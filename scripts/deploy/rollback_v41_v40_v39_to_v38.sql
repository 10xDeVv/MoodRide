-- Coordinated rollback from lifecycle V41/V40/V39 to the production V38 baseline.
-- All route-api, route-worker, and notification consumers must be stopped before this
-- file is run. If any guard fails, the transaction remains unchanged; the deployment
-- scripts promote a fully restored and validated replacement database without dropping
-- this database.

\set ON_ERROR_STOP on

BEGIN;

-- Keep the guard observations true until all schema and history mutations commit.
LOCK TABLE public.flyway_schema_history IN ACCESS EXCLUSIVE MODE;
LOCK TABLE public.route_jobs IN ACCESS EXCLUSIVE MODE;
LOCK TABLE public.routes IN ACCESS EXCLUSIVE MODE;

DO $$
DECLARE
    active_job_count BIGINT;
    v38_rank INTEGER;
    v39_success_count INTEGER;
    v40_success_count INTEGER;
    v39_column_count INTEGER;
    v39_index_count INTEGER;
    v40_table_present BOOLEAN;
    v40_index_present BOOLEAN;
    v40_column_count INTEGER;
    v40_constraint_count INTEGER;
    pending_dispatch_count BIGINT;
    v41_success_count INTEGER;
    v41_table_present BOOLEAN;
    v41_index_present BOOLEAN;
    v41_column_count INTEGER;
    v41_constraint_count INTEGER;
    pending_terminal_event_count BIGINT;
BEGIN
    SELECT COUNT(*)
    INTO active_job_count
    FROM public.route_jobs
    WHERE status IN ('QUEUED', 'PROCESSING', 'PRIMARY_READY');

    IF active_job_count <> 0 THEN
        RAISE EXCEPTION
            'Rollback refused: % active route jobs remain (QUEUED/PROCESSING/PRIMARY_READY)',
            active_job_count;
    END IF;

    SELECT installed_rank
    INTO v38_rank
    FROM public.flyway_schema_history
    WHERE version = '38'
      AND description = 'add road segment stable identity'
      AND checksum = 1443186875
      AND success IS TRUE;

    IF v38_rank IS NULL THEN
        RAISE EXCEPTION 'Rollback refused: exact production V38 road-identity lineage is missing';
    END IF;

    IF (SELECT COUNT(*) FROM public.flyway_schema_history
        WHERE version = '38' AND description = 'add road segment stable identity'
          AND checksum = 1443186875 AND success IS TRUE) <> 1 THEN
        RAISE EXCEPTION 'Rollback refused: production V38 lineage is not unique';
    END IF;

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
        RAISE EXCEPTION 'Rollback refused: V39/V40/V41 description or checksum mismatch';
    END IF;

    SELECT COUNT(*) FILTER (WHERE version = '39' AND success IS TRUE),
           COUNT(*) FILTER (WHERE version = '40' AND success IS TRUE),
           COUNT(*) FILTER (WHERE version = '41' AND success IS TRUE)
    INTO v39_success_count, v40_success_count, v41_success_count
    FROM public.flyway_schema_history;

    IF v39_success_count <> 1 OR v40_success_count <> 1 OR v41_success_count <> 1 THEN
        RAISE EXCEPTION 'Rollback refused: exact successful V39/V40/V41 history is incomplete or duplicated';
    END IF;

    IF EXISTS (
        SELECT 1 FROM public.flyway_schema_history
        WHERE version IN ('39', '40', '41') AND success IS TRUE
          AND installed_rank <= v38_rank
    ) THEN
        RAISE EXCEPTION 'Rollback refused: V39/V40/V41 rank is not after production V38';
    END IF;

    IF (SELECT installed_rank FROM public.flyway_schema_history WHERE version = '39' AND success IS TRUE)
       >= (SELECT installed_rank FROM public.flyway_schema_history WHERE version = '40' AND success IS TRUE)
       OR (SELECT installed_rank FROM public.flyway_schema_history WHERE version = '40' AND success IS TRUE)
       >= (SELECT installed_rank FROM public.flyway_schema_history WHERE version = '41' AND success IS TRUE) THEN
        RAISE EXCEPTION 'Rollback refused: V39/V40/V41 installation order is divergent';
    END IF;

    -- Any other successful migration after the production V38 baseline makes this
    -- coordinated rollback ambiguous, including repeatable migrations.
    IF EXISTS (
        SELECT 1 FROM public.flyway_schema_history
        WHERE success IS TRUE
          AND installed_rank > v38_rank
          AND version IS DISTINCT FROM '39'
          AND version IS DISTINCT FROM '40'
          AND version IS DISTINCT FROM '41'
    ) THEN
        RAISE EXCEPTION 'Rollback refused: an unexpected successful migration exists after V41';
    END IF;

    SELECT COUNT(*)
    INTO v39_column_count
    FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = 'route_jobs'
      AND column_name IN (
          'primary_ready_at',
          'state_revision',
          'option_revision',
          'option_count',
          'options_complete',
          'lease_token',
          'lease_expires_at',
          'row_version'
      );

    SELECT (CASE WHEN to_regclass('public.ux_routes_job_profile') IS NOT NULL THEN 1 ELSE 0 END)
         + (CASE WHEN to_regclass('public.idx_route_jobs_active_lease') IS NOT NULL THEN 1 ELSE 0 END)
    INTO v39_index_count;

    v40_table_present := to_regclass('public.route_job_dispatches') IS NOT NULL;
    v40_index_present := to_regclass('public.idx_route_job_dispatch_due') IS NOT NULL;

    IF v40_table_present THEN
        SELECT COUNT(*)
        INTO v40_column_count
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'route_job_dispatches'
          AND column_name IN (
              'job_id',
              'created_at',
              'next_attempt_at',
              'attempt_count',
              'sent_at',
              'lease_token',
              'lease_expires_at',
              'last_error'
          );

        SELECT COUNT(*)
        INTO v40_constraint_count
        FROM information_schema.table_constraints
        WHERE table_schema = 'public'
          AND table_name = 'route_job_dispatches'
          AND constraint_name IN (
              'route_job_dispatches_pkey',
              'route_job_dispatches_job_id_fkey',
              'route_job_dispatches_attempt_count_check',
              'ck_route_job_dispatch_lease_pair',
              'ck_route_job_dispatch_sent_not_leased'
          );

        SELECT COUNT(*)
        INTO pending_dispatch_count
        FROM public.route_job_dispatches
        WHERE sent_at IS NULL;

        IF pending_dispatch_count <> 0 THEN
            RAISE EXCEPTION
                'Rollback refused: % V40 route-job dispatches remain unsent',
                pending_dispatch_count;
        END IF;
    ELSE
        v40_column_count := 0;
        v40_constraint_count := 0;
        pending_dispatch_count := 0;
    END IF;

    v41_table_present := to_regclass('public.route_job_terminal_events') IS NOT NULL;
    v41_index_present := to_regclass('public.idx_route_job_terminal_event_due') IS NOT NULL;

    IF v41_table_present THEN
        SELECT COUNT(*)
        INTO v41_column_count
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'route_job_terminal_events'
          AND column_name IN (
              'event_id',
              'job_id',
              'state_revision',
              'event_type',
              'terminal_status',
              'original_payload',
              'created_at',
              'next_attempt_at',
              'attempt_count',
              'delivered_at',
              'lease_token',
              'lease_expires_at',
              'last_error'
          );

        SELECT COUNT(*)
        INTO v41_constraint_count
        FROM information_schema.table_constraints
        WHERE table_schema = 'public'
          AND table_name = 'route_job_terminal_events'
          AND constraint_name IN (
              'route_job_terminal_events_pkey',
              'route_job_terminal_events_job_id_fkey',
              'route_job_terminal_events_state_revision_check',
              'route_job_terminal_events_event_type_check',
              'route_job_terminal_events_terminal_status_check',
              'route_job_terminal_events_attempt_count_check',
              'uq_route_job_terminal_event_identity',
              'ck_route_job_terminal_event_lease_pair',
              'ck_route_job_terminal_event_delivered_not_leased',
              'ck_route_job_terminal_event_dlq_status'
          );

        SELECT COUNT(*)
        INTO pending_terminal_event_count
        FROM public.route_job_terminal_events
        WHERE delivered_at IS NULL;

        IF pending_terminal_event_count <> 0 THEN
            RAISE EXCEPTION
                'Rollback refused: % V41 terminal events remain undelivered',
                pending_terminal_event_count;
        END IF;
    ELSE
        v41_column_count := 0;
        v41_constraint_count := 0;
        pending_terminal_event_count := 0;
    END IF;

    IF v39_column_count <> 8 OR v39_index_count <> 2 THEN
        RAISE EXCEPTION
            'Rollback refused: V39 schema/history drift (history %, columns %, indexes %)',
            v39_success_count, v39_column_count, v39_index_count;
    END IF;

    IF NOT v40_table_present
       OR NOT v40_index_present
       OR v40_column_count <> 8
       OR v40_constraint_count <> 5 THEN
        RAISE EXCEPTION
            'Rollback refused: V40 schema/history drift (history %, table %, index %, columns %, constraints %)',
            v40_success_count, v40_table_present, v40_index_present,
            v40_column_count, v40_constraint_count;
    END IF;

    IF NOT v41_table_present
       OR NOT v41_index_present
       OR v41_column_count <> 13
       OR v41_constraint_count <> 10 THEN
        RAISE EXCEPTION
            'Rollback refused: V41 schema/history drift (history %, table %, index %, columns %, constraints %)',
            v41_success_count, v41_table_present, v41_index_present,
            v41_column_count, v41_constraint_count;
    END IF;
END $$;

-- V41/V40 objects and history are removed in this same transaction.
DROP INDEX IF EXISTS public.idx_route_job_terminal_event_due;
DROP TABLE IF EXISTS public.route_job_terminal_events;
DROP INDEX IF EXISTS public.idx_route_job_dispatch_due;
DROP TABLE IF EXISTS public.route_job_dispatches;

-- The unique option invariant is intentionally relaxed only as part of this complete,
-- consumer-stopped rollback to V38. Re-forwarding V39 recreates and revalidates it.
DROP INDEX IF EXISTS public.idx_route_jobs_active_lease;
DROP INDEX IF EXISTS public.ux_routes_job_profile;

ALTER TABLE public.route_jobs
    DROP COLUMN IF EXISTS primary_ready_at,
    DROP COLUMN IF EXISTS state_revision,
    DROP COLUMN IF EXISTS option_revision,
    DROP COLUMN IF EXISTS option_count,
    DROP COLUMN IF EXISTS options_complete,
    DROP COLUMN IF EXISTS lease_token,
    DROP COLUMN IF EXISTS lease_expires_at,
    DROP COLUMN IF EXISTS row_version;

DO $$
DECLARE
    expected_delete_count INTEGER;
    actual_delete_count INTEGER;
BEGIN
    SELECT COUNT(*)
    INTO expected_delete_count
    FROM public.flyway_schema_history
    WHERE version IN ('39', '40', '41') AND success IS TRUE;

    DELETE FROM public.flyway_schema_history
    WHERE version IN ('39', '40', '41') AND success IS TRUE;

    GET DIAGNOSTICS actual_delete_count = ROW_COUNT;
    IF actual_delete_count <> expected_delete_count THEN
        RAISE EXCEPTION
            'Rollback failed: deleted % successful V39/V40/V41 Flyway rows, expected %',
            actual_delete_count, expected_delete_count;
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.flyway_schema_history
        WHERE version IN ('39', '40', '41') AND success IS TRUE
    ) THEN
        RAISE EXCEPTION 'Rollback failed: successful V39/V40/V41 Flyway history remains';
    END IF;

    IF to_regclass('public.route_job_terminal_events') IS NOT NULL
       OR to_regclass('public.idx_route_job_terminal_event_due') IS NOT NULL
       OR to_regclass('public.route_job_dispatches') IS NOT NULL
       OR to_regclass('public.idx_route_job_dispatch_due') IS NOT NULL
       OR to_regclass('public.idx_route_jobs_active_lease') IS NOT NULL
       OR to_regclass('public.ux_routes_job_profile') IS NOT NULL
       OR EXISTS (
           SELECT 1
           FROM information_schema.columns
           WHERE table_schema = 'public'
             AND table_name = 'route_jobs'
             AND column_name IN (
                 'primary_ready_at',
                 'state_revision',
                 'option_revision',
                 'option_count',
                 'options_complete',
                 'lease_token',
                 'lease_expires_at',
                 'row_version'
             )
       ) THEN
        RAISE EXCEPTION 'Rollback failed: V39/V40/V41 schema objects remain';
    END IF;
END $$;

COMMIT;
