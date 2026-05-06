-- Persist route generation algorithm metadata for route detail responses.
ALTER TABLE route_jobs
    ADD COLUMN IF NOT EXISTS algorithm_version VARCHAR(50);

ALTER TABLE route_jobs
    ADD COLUMN IF NOT EXISTS beam_candidates INTEGER;
