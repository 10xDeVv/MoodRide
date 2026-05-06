-- V16__allow_multiple_routes_per_job.sql
-- Enable storing multiple generated route options per route job.

ALTER TABLE routes
    DROP CONSTRAINT IF EXISTS routes_job_id_key;

DROP INDEX IF EXISTS routes_job_id_key;

CREATE INDEX IF NOT EXISTS idx_routes_job_generated_at
    ON routes (job_id, generated_at);
