-- Protected areas enrichment v2.9 (CPCAD).
--
-- Usage:
--   psql -d moodride -v ON_ERROR_STOP=1 -v chunk_size=2000 -f scripts/setup/data-quality-parks-v29.sql
--
-- Prereqs:
-- - protected_areas table loaded (geometry in SRID 4326).
-- - component-score columns present on scenic_score_tiles.

DO $$
BEGIN
    IF to_regclass('public.scenic_score_tiles') IS NULL THEN
        RAISE EXCEPTION 'Required table missing: public.scenic_score_tiles';
    END IF;
    IF to_regclass('public.protected_areas') IS NULL THEN
        RAISE EXCEPTION 'Required table missing: public.protected_areas';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'scenic_score_tiles'
          AND column_name = 'water_score'
    ) THEN
        RAISE EXCEPTION 'scenic_score_tiles.water_score is missing. Run component-score migration first.';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'scenic_score_tiles'
          AND column_name = 'green_score'
    ) THEN
        RAISE EXCEPTION 'scenic_score_tiles.green_score is missing. Run component-score migration first.';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'scenic_score_tiles'
          AND column_name = 'elevation_score'
    ) THEN
        RAISE EXCEPTION 'scenic_score_tiles.elevation_score is missing. Run component-score migration first.';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'scenic_score_tiles'
          AND column_name = 'solitude_score'
    ) THEN
        RAISE EXCEPTION 'scenic_score_tiles.solitude_score is missing. Run component-score migration first.';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'scenic_score_tiles'
          AND column_name = 'curve_score'
    ) THEN
        RAISE EXCEPTION 'scenic_score_tiles.curve_score is missing. Run component-score migration first.';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'scenic_score_tiles'
          AND column_name = 'poi_score'
    ) THEN
        RAISE EXCEPTION 'scenic_score_tiles.poi_score is missing. Run component-score migration first.';
    END IF;
END $$;

ALTER TABLE scenic_score_tiles
    ADD COLUMN IF NOT EXISTS park_score DOUBLE PRECISION NOT NULL DEFAULT 0.0;

CREATE INDEX IF NOT EXISTS protected_areas_geog_idx
    ON public.protected_areas
    USING GIST ((geometry::geography));

\echo Building protected-area spatial working table...
DROP TABLE IF EXISTS public.dq_protected_areas_subdivided;
CREATE UNLOGGED TABLE public.dq_protected_areas_subdivided AS
SELECT
    id,
    ST_Multi((ST_Dump(ST_Subdivide(geometry, 256))).geom)::geometry(MULTIPOLYGON, 4326) AS geometry
FROM public.protected_areas
WHERE geometry IS NOT NULL
  AND NOT ST_IsEmpty(geometry);

CREATE INDEX IF NOT EXISTS dq_protected_areas_subdivided_geom_idx
    ON public.dq_protected_areas_subdivided
    USING GIST (geometry);
ANALYZE public.dq_protected_areas_subdivided;

\if :{?chunk_size}
\else
\set chunk_size 2000
\endif

\echo Building protected-area batches for 2.9...
DROP TABLE IF EXISTS public.dq_park_batches;
CREATE UNLOGGED TABLE public.dq_park_batches AS
SELECT
    sst.h3_index,
    ROW_NUMBER() OVER (ORDER BY sst.h3_index) AS rn
FROM public.scenic_score_tiles sst
WHERE COALESCE(sst.scoring_version, '') <> '2.9-protected-areas-enrichment';

CREATE UNIQUE INDEX IF NOT EXISTS dq_park_batches_h3_idx
    ON public.dq_park_batches (h3_index);
CREATE INDEX IF NOT EXISTS dq_park_batches_rn_idx
    ON public.dq_park_batches (rn);
ANALYZE public.dq_park_batches;

SELECT COUNT(*) AS target_tiles, MIN(rn) AS min_rn, MAX(rn) AS max_rn
FROM public.dq_park_batches;

\echo Running protected-area enrichment with chunk_size=:chunk_size ...
WITH bounds AS (
    SELECT
        gs::INTEGER AS start_rn,
        LEAST(
            (gs + :chunk_size::INTEGER - 1)::INTEGER,
            (SELECT COALESCE(MAX(rn), 0) FROM public.dq_park_batches)
        ) AS end_rn
    FROM generate_series(
        1,
        (SELECT COALESCE(MAX(rn), 0) FROM public.dq_park_batches),
        :chunk_size::INTEGER
    ) AS gs
)
SELECT format($fmt$
DO $batch$
DECLARE
    v_start INTEGER := %s;
    v_end INTEGER := %s;
BEGIN
    RAISE NOTICE 'Batch %%..%% started', v_start, v_end;

    WITH batch_h3 AS MATERIALIZED (
        SELECT sst.h3_index, sst.geometry
        FROM public.scenic_score_tiles sst
        JOIN public.dq_park_batches b
          ON b.h3_index = sst.h3_index
        WHERE b.rn BETWEEN v_start AND v_end
    ),
    park_scores AS (
        SELECT
            b.h3_index,
            CASE
                WHEN EXISTS (
                    SELECT 1
                    FROM public.dq_protected_areas_subdivided pa
                    WHERE pa.geometry && b.geometry
                      AND ST_Intersects(pa.geometry, b.geometry)
                ) THEN 1.0
                WHEN EXISTS (
                    SELECT 1
                    FROM public.dq_protected_areas_subdivided pa
                    WHERE ST_Expand(pa.geometry, 0.02) && b.geometry
                      AND ST_DWithin(pa.geometry, b.geometry, 0.02)
                ) THEN 0.6
                WHEN EXISTS (
                    SELECT 1
                    FROM public.dq_protected_areas_subdivided pa
                    WHERE ST_Expand(pa.geometry, 0.05) && b.geometry
                      AND ST_DWithin(pa.geometry, b.geometry, 0.05)
                ) THEN 0.3
                ELSE 0.0
            END AS park_score
        FROM batch_h3 b
    )
    UPDATE public.scenic_score_tiles sst
    SET park_score = ps.park_score,
        scenic_score = LEAST(
            1.0,
            GREATEST(
                0.0,
                (
                    COALESCE(sst.water_score, 0.0) * 0.25 +
                    COALESCE(sst.green_score, 0.0) * 0.22 +
                    COALESCE(sst.elevation_score, 0.0) * 0.15 +
                    COALESCE(sst.solitude_score, 0.0) * 0.12 +
                    COALESCE(sst.curve_score, 0.0) * 0.11 +
                    COALESCE(sst.poi_score, 0.0) * 0.15
                ) * (1.0 + (ps.park_score * 0.30))
            )
        ),
        last_scored = CURRENT_TIMESTAMP,
        scoring_version = '2.9-protected-areas-enrichment'
    FROM park_scores ps
    WHERE sst.h3_index = ps.h3_index;

    RAISE NOTICE 'Batch %%..%% complete', v_start, v_end;
END
$batch$;
$fmt$, start_rn, end_rn)
FROM bounds
\gexec

ANALYZE public.scenic_score_tiles;

\echo Final stats (2.9 target + global)
SELECT
    COUNT(*) AS tiles,
    COUNT(*) FILTER (WHERE park_score > 0.0) AS park_non_zero_tiles,
    ROUND(AVG(park_score)::numeric, 6) AS avg_park_score,
    ROUND(STDDEV_POP(park_score)::numeric, 6) AS stddev_park_score,
    ROUND(AVG(scenic_score)::numeric, 6) AS avg_scenic_score,
    ROUND(STDDEV_POP(scenic_score)::numeric, 6) AS stddev_scenic_score
FROM public.scenic_score_tiles
WHERE scoring_version = '2.9-protected-areas-enrichment';
