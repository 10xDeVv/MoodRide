-- V11__phase5_job_contract_alignment.sql
-- Align route job persistence with Phase 5 async contract.

ALTER TABLE route_jobs
    ADD COLUMN IF NOT EXISTS failed_at TIMESTAMP;

ALTER TABLE route_jobs
    ADD COLUMN IF NOT EXISTS max_retries INTEGER NOT NULL DEFAULT 2;

ALTER TABLE route_jobs
    ADD COLUMN IF NOT EXISTS route_id UUID;

-- Normalize legacy statuses to spec-aligned values.
UPDATE route_jobs SET status = 'QUEUED' WHERE status = 'SUBMITTED';
UPDATE route_jobs SET status = 'COMPLETED' WHERE status = 'SUCCESS';

-- Ensure null route ids are backfilled for completed jobs when route exists.
UPDATE route_jobs j
SET route_id = r.id
FROM routes r
WHERE r.job_id = j.id
  AND j.route_id IS NULL;

ALTER TABLE route_jobs
    DROP CONSTRAINT IF EXISTS route_jobs_status_check;

ALTER TABLE route_jobs
    ADD CONSTRAINT route_jobs_status_check
    CHECK (status IN ('QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED', 'TIMEOUT'));

CREATE INDEX IF NOT EXISTS idx_route_jobs_route_id ON route_jobs(route_id);

