ALTER TABLE route_jobs
    DROP CONSTRAINT IF EXISTS route_jobs_status_check;

ALTER TABLE route_jobs
    ADD CONSTRAINT route_jobs_status_check
    CHECK (status IN ('QUEUED', 'PROCESSING', 'PRIMARY_READY', 'COMPLETED', 'FAILED', 'TIMEOUT'));
