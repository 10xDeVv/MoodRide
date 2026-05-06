-- V17__persist_route_option_profile.sql
-- Persist route option profile labels on generated routes.

ALTER TABLE routes
    ADD COLUMN IF NOT EXISTS route_profile VARCHAR(32);

UPDATE routes
SET route_profile = ranked.route_profile
FROM (
    SELECT id,
           CASE ROW_NUMBER() OVER (PARTITION BY job_id ORDER BY generated_at, id)
               WHEN 1 THEN 'most_scenic'
               WHEN 2 THEN 'balanced'
               WHEN 3 THEN 'shorter'
               ELSE NULL
           END AS route_profile
    FROM routes
) ranked
WHERE routes.id = ranked.id
  AND routes.route_profile IS NULL
  AND ranked.route_profile IS NOT NULL;

ALTER TABLE routes
    DROP CONSTRAINT IF EXISTS chk_routes_route_profile;

ALTER TABLE routes
    ADD CONSTRAINT chk_routes_route_profile
    CHECK (route_profile IS NULL OR route_profile IN ('most_scenic', 'balanced', 'shorter'));

CREATE INDEX IF NOT EXISTS idx_routes_job_profile
    ON routes (job_id, route_profile, generated_at);
