-- Add first-latency lifecycle revisions and worker ownership fencing.
ALTER TABLE route_jobs
    ADD COLUMN IF NOT EXISTS primary_ready_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS state_revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS option_revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS option_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS options_complete BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS lease_token UUID,
    ADD COLUMN IF NOT EXISTS lease_expires_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS row_version BIGINT NOT NULL DEFAULT 0;

-- Refuse to normalize unexpected production data during an application rollout.
-- Duplicate profiles require an explicit, separately backed-up repair before this
-- migration can safely enforce the persisted option identity.
DO $$
DECLARE
    duplicate_group_count BIGINT;
BEGIN
    SELECT COUNT(*)
    INTO duplicate_group_count
    FROM (
        SELECT job_id, route_profile
        FROM routes
        WHERE route_profile IS NOT NULL
        GROUP BY job_id, route_profile
        HAVING COUNT(*) > 1
    ) duplicate_profiles;

    IF duplicate_group_count > 0 THEN
        RAISE EXCEPTION
            'Cannot add ux_routes_job_profile: found % duplicate (job_id, route_profile) groups',
            duplicate_group_count;
    END IF;
END $$;

-- Backfill lifecycle projections from the stable persisted route set.
UPDATE route_jobs j
SET primary_ready_at = COALESCE(
        j.primary_ready_at,
        (SELECT r.generated_at FROM routes r WHERE r.id = j.route_id),
        j.completed_at,
        j.started_at,
        j.submitted_at
    )
WHERE j.route_id IS NOT NULL
  AND j.status IN ('PRIMARY_READY', 'COMPLETED');

UPDATE route_jobs j
SET option_count = counts.profile_count,
    option_revision = GREATEST(j.option_revision, counts.profile_count)
FROM (
    SELECT job_id, COUNT(*)::INTEGER AS profile_count
    FROM routes
    WHERE route_profile IS NOT NULL
    GROUP BY job_id
) counts
WHERE counts.job_id = j.id;

UPDATE route_jobs
SET state_revision = GREATEST(
        state_revision,
        CASE status
            WHEN 'QUEUED' THEN 0
            WHEN 'PROCESSING' THEN 1
            WHEN 'PRIMARY_READY' THEN 2
            WHEN 'COMPLETED' THEN 3
            ELSE 2
        END
    ),
    options_complete = CASE WHEN status = 'COMPLETED' THEN TRUE ELSE options_complete END;

CREATE UNIQUE INDEX IF NOT EXISTS ux_routes_job_profile
    ON routes (job_id, route_profile)
    WHERE route_profile IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_route_jobs_active_lease
    ON route_jobs (status, lease_expires_at)
    WHERE status IN ('PROCESSING', 'PRIMARY_READY');
