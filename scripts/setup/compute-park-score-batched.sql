-- Batched park_score computation to avoid long-running table locks.
--
-- Usage:
--   psql -d moodride -v ON_ERROR_STOP=1 -v chunk_size=1000 -f scripts/setup/compute-park-score-batched.sql
--   psql -d moodride -v ON_ERROR_STOP=1 -v chunk_size=1000 -v apply_multiplier=1 -f scripts/setup/compute-park-score-batched.sql

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

-- Optional caller override via: -v chunk_size=1000
\if :{?chunk_size}
\else
\set chunk_size 1000
\endif

\echo Building park target batches...
DROP TABLE IF EXISTS public.park_target_batches;
CREATE UNLOGGED TABLE public.park_target_batches AS
SELECT
    sst.h3_index,
    ROW_NUMBER() OVER (ORDER BY sst.h3_index) AS rn
FROM scenic_score_tiles sst;

CREATE UNIQUE INDEX IF NOT EXISTS park_target_batches_h3_idx
    ON public.park_target_batches (h3_index);
CREATE INDEX IF NOT EXISTS park_target_batches_rn_idx
    ON public.park_target_batches (rn);
ANALYZE public.park_target_batches;

SELECT COUNT(*) AS target_tiles, MIN(rn) AS min_rn, MAX(rn) AS max_rn
FROM public.park_target_batches;

\echo Running batched park_score updates with chunk_size=:chunk_size ...
WITH bounds AS (
    SELECT
        gs::INTEGER AS start_rn,
        LEAST(
            (gs + :chunk_size::INTEGER - 1)::INTEGER,
            (SELECT COALESCE(MAX(rn), 0) FROM public.park_target_batches)
        ) AS end_rn
    FROM generate_series(
        1,
        (SELECT COALESCE(MAX(rn), 0) FROM public.park_target_batches),
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

    WITH batch_h3 AS (
        SELECT h3_index
        FROM public.park_target_batches
        WHERE rn BETWEEN v_start AND v_end
    ),
    scored AS (
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
        JOIN batch_h3 b
          ON b.h3_index = sst.h3_index
    )
    UPDATE scenic_score_tiles sst
    SET park_score = scored.park_score
    FROM scored
    WHERE sst.h3_index = scored.h3_index;

    RAISE NOTICE 'Batch %%..%% complete', v_start, v_end;
END
$batch$;
$fmt$, start_rn, end_rn)
FROM bounds
\gexec

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
