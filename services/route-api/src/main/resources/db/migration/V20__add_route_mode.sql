-- V20__add_route_mode.sql
-- Store the movement mode independently from route option profiles.

ALTER TABLE route_jobs
    ADD COLUMN IF NOT EXISTS route_mode VARCHAR(16) NOT NULL DEFAULT 'DRIVE';

ALTER TABLE routes
    ADD COLUMN IF NOT EXISTS route_mode VARCHAR(16) NOT NULL DEFAULT 'DRIVE';

ALTER TABLE route_jobs
    DROP CONSTRAINT IF EXISTS chk_route_jobs_route_mode;

ALTER TABLE route_jobs
    ADD CONSTRAINT chk_route_jobs_route_mode
    CHECK (route_mode IN ('DRIVE', 'WALK', 'BIKE'));

ALTER TABLE routes
    DROP CONSTRAINT IF EXISTS chk_routes_route_mode;

ALTER TABLE routes
    ADD CONSTRAINT chk_routes_route_mode
    CHECK (route_mode IN ('DRIVE', 'WALK', 'BIKE'));

CREATE INDEX IF NOT EXISTS idx_route_jobs_mode_status_submitted
    ON route_jobs (route_mode, status, submitted_at DESC);

CREATE INDEX IF NOT EXISTS idx_routes_mode_generated
    ON routes (route_mode, generated_at DESC);
