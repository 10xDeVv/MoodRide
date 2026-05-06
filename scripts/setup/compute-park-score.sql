-- Compute park_score for protected areas and optionally apply multiplier.
--
-- Usage:
--   psql -d moodride -v ON_ERROR_STOP=1 -f scripts/setup/compute-park-score.sql
--   psql -d moodride -v ON_ERROR_STOP=1 -v apply_multiplier=1 -f scripts/setup/compute-park-score.sql

DO $$
BEGIN
    IF to_regclass('public.scenic_score_tiles') IS NULL THEN
        RAISE EXCEPTION 'Required table missing: public.scenic_score_tiles';
    END IF;
    IF to_regclass('public.protected_areas') IS NULL THEN
        RAISE EXCEPTION 'Required table missing: public.protected_areas';
    END IF;
END $$;

ALTER TABLE scenic_score_tiles
    ADD COLUMN IF NOT EXISTS park_score double precision DEFAULT 0.0;

CREATE INDEX IF NOT EXISTS protected_areas_geom_idx
    ON protected_areas
    USING GIST (geometry);

-- Compute park_score for all tiles.
WITH scored AS (
    SELECT
        sst.h3_index,
        CASE
            WHEN EXISTS (
                SELECT 1 FROM protected_areas pa
                WHERE ST_Intersects(pa.geometry, sst.geometry)
            ) THEN 1.0
            WHEN EXISTS (
                SELECT 1 FROM protected_areas pa
                WHERE ST_DWithin(pa.geometry::geography, sst.geometry::geography, 2000)
            ) THEN 0.6
            WHEN EXISTS (
                SELECT 1 FROM protected_areas pa
                WHERE ST_DWithin(pa.geometry::geography, sst.geometry::geography, 5000)
            ) THEN 0.3
            ELSE 0.0
        END AS park_score
    FROM scenic_score_tiles sst
)
UPDATE scenic_score_tiles sst
SET park_score = scored.park_score
FROM scored
WHERE sst.h3_index = scored.h3_index;

-- Optional multiplier (enable with -v apply_multiplier=1).
\if :{?apply_multiplier}
\else
\set apply_multiplier 0
\endif

\if :apply_multiplier
UPDATE scenic_score_tiles
SET scenic_score = LEAST(1.0, GREATEST(0.0, scenic_score * (1.0 + park_score * 0.3))),
    last_scored = CURRENT_TIMESTAMP,
    scoring_version = '2.5-park-score';
\endif

ANALYZE scenic_score_tiles;

SELECT
    COUNT(*) AS tiles,
    COUNT(*) FILTER (WHERE park_score >= 1.0) AS park_inside,
    COUNT(*) FILTER (WHERE park_score >= 0.6 AND park_score < 1.0) AS park_within_2km,
    COUNT(*) FILTER (WHERE park_score >= 0.3 AND park_score < 0.6) AS park_within_5km,
    ROUND(AVG(park_score)::numeric, 6) AS avg_park_score,
    ROUND(STDDEV_POP(park_score)::numeric, 6) AS std_park_score
FROM scenic_score_tiles;
