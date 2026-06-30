-- Viewpoint / photo-landmark scenic data quality v3.6.
--
-- Usage:
--   psql -d moodride -v ON_ERROR_STOP=1 -v chunk_size=50000 -f scripts/setup/data-quality-enrichment-v36.sql
--
-- Optional:
--   -v source_scoring_version=3.5-scenic-poi-calibration
--   -v scoring_version=3.6-viewpoint-calibration
--
-- Prereqs:
-- - scenic_score_tiles already has v3.5 scenic-POI columns.
-- - overture_places exists with category/confidence/geometry.
-- - This separates true photo/viewpoint landmarks from broad scenic POI density.

SET client_min_messages = WARNING;
SET work_mem = '256MB';
SET temp_buffers = '256MB';
SET max_parallel_workers_per_gather = 4;

\if :{?chunk_size}
\else
\set chunk_size 50000
\endif

\if :{?scoring_version}
\else
\set scoring_version '3.6-viewpoint-calibration'
\endif

\if :{?source_scoring_version}
\else
\set source_scoring_version ''
\endif

DO $$
BEGIN
    IF to_regclass('public.scenic_score_tiles') IS NULL THEN
        RAISE EXCEPTION 'Required table missing: public.scenic_score_tiles';
    END IF;
    IF to_regclass('public.overture_places') IS NULL THEN
        RAISE EXCEPTION 'Required table missing: public.overture_places';
    END IF;
END $$;

ALTER TABLE public.scenic_score_tiles
    ADD COLUMN IF NOT EXISTS viewpoint_score DOUBLE PRECISION NOT NULL DEFAULT 0.0;

ALTER TABLE public.scenic_score_tiles DROP CONSTRAINT IF EXISTS viewpoint_score_range;
ALTER TABLE public.scenic_score_tiles
    ADD CONSTRAINT viewpoint_score_range CHECK (viewpoint_score >= 0.0 AND viewpoint_score <= 1.0);

CREATE TABLE IF NOT EXISTS public.overture_viewpoint_category_weights (
    category TEXT PRIMARY KEY,
    viewpoint_weight DOUBLE PRECISION NOT NULL CHECK (viewpoint_weight >= 0.0 AND viewpoint_weight <= 1.0),
    category_group TEXT NOT NULL
);

INSERT INTO public.overture_viewpoint_category_weights (category, viewpoint_weight, category_group) VALUES
    ('lookout', 1.00, 'viewpoint'),
    ('viewpoint', 1.00, 'viewpoint'),
    ('scenic_lookout', 1.00, 'viewpoint'),
    ('waterfall', 0.92, 'natural_feature'),
    ('lighthouse', 0.88, 'landmark'),
    ('mountain', 0.72, 'natural_feature'),
    ('beach', 0.58, 'waterfront'),
    ('pier', 0.54, 'waterfront'),
    ('bridge', 0.48, 'landmark'),
    ('monument', 0.42, 'landmark'),
    ('landmark_and_historical_building', 0.36, 'landmark')
ON CONFLICT (category) DO UPDATE
SET viewpoint_weight = EXCLUDED.viewpoint_weight,
    category_group = EXCLUDED.category_group;

ANALYZE public.scenic_score_tiles;
ANALYZE public.overture_places;

\echo Building v3.6 target tile list...
DROP TABLE IF EXISTS public.dq_v36_target_tiles;
CREATE UNLOGGED TABLE public.dq_v36_target_tiles AS
SELECT h3_index, geometry
FROM public.scenic_score_tiles
WHERE COALESCE(scoring_version, '') <> :'scoring_version'
  AND (
      :'source_scoring_version' = ''
      OR COALESCE(scoring_version, '') = :'source_scoring_version'
  );

CREATE UNIQUE INDEX IF NOT EXISTS dq_v36_target_tiles_h3_idx
    ON public.dq_v36_target_tiles (h3_index);
CREATE INDEX IF NOT EXISTS dq_v36_target_tiles_geom_idx
    ON public.dq_v36_target_tiles USING GIST (geometry);
ANALYZE public.dq_v36_target_tiles;

\echo Building v3.6 weighted viewpoint source subset...
DROP TABLE IF EXISTS public.dq_v36_weighted_viewpoint_places;
CREATE UNLOGGED TABLE public.dq_v36_weighted_viewpoint_places AS
SELECT
    p.id,
    p.category,
    p.geometry,
    LEAST(1.0, GREATEST(0.0, COALESCE(p.confidence, 0.75))) AS confidence,
    w.viewpoint_weight,
    w.category_group
FROM public.overture_places p
JOIN public.overture_viewpoint_category_weights w
  ON w.category = p.category
WHERE p.geometry IS NOT NULL
  AND COALESCE(p.confidence, 0.75) >= 0.35;

CREATE INDEX IF NOT EXISTS dq_v36_weighted_viewpoint_places_geom_idx
    ON public.dq_v36_weighted_viewpoint_places USING GIST (geometry);
CREATE INDEX IF NOT EXISTS dq_v36_weighted_viewpoint_places_category_idx
    ON public.dq_v36_weighted_viewpoint_places (category);
ANALYZE public.dq_v36_weighted_viewpoint_places;

SELECT COUNT(*) AS weighted_viewpoint_place_candidates
FROM public.dq_v36_weighted_viewpoint_places;

\echo Building v3.6 viewpoint tile aggregates...
DROP TABLE IF EXISTS public.dq_v36_viewpoint_tiles;
CREATE UNLOGGED TABLE public.dq_v36_viewpoint_tiles AS
WITH tile_place_matches AS MATERIALIZED (
    SELECT
        tt.h3_index,
        wvp.category,
        wvp.category_group,
        wvp.viewpoint_weight,
        wvp.confidence
    FROM public.dq_v36_target_tiles tt
    JOIN public.dq_v36_weighted_viewpoint_places wvp
      ON wvp.geometry && tt.geometry
     AND ST_Covers(tt.geometry, wvp.geometry)
),
tile_raw AS (
    SELECT
        h3_index,
        SUM(viewpoint_weight * confidence) AS weighted_viewpoint_signal,
        COUNT(*) AS viewpoint_place_count,
        COUNT(DISTINCT category_group) AS viewpoint_group_count,
        MAX(viewpoint_weight * confidence) AS best_viewpoint_signal
    FROM tile_place_matches
    GROUP BY h3_index
)
SELECT
    h3_index,
    LEAST(
        1.0,
        GREATEST(
            0.0,
            (
                (LN(1.0 + weighted_viewpoint_signal) / LN(1.0 + 3.0)) * 0.58
                + (LN(1.0 + viewpoint_group_count) / LN(1.0 + 2.0)) * 0.14
                + best_viewpoint_signal * 0.28
            )
        )
    ) AS viewpoint_score,
    viewpoint_place_count,
    viewpoint_group_count,
    weighted_viewpoint_signal
FROM tile_raw;

CREATE UNIQUE INDEX IF NOT EXISTS dq_v36_viewpoint_tiles_h3_idx
    ON public.dq_v36_viewpoint_tiles (h3_index);
ANALYZE public.dq_v36_viewpoint_tiles;

\echo Building v3.6 enrichment batches...
DROP TABLE IF EXISTS public.dq_v36_batches;
CREATE UNLOGGED TABLE public.dq_v36_batches AS
SELECT
    sst.h3_index,
    ROW_NUMBER() OVER (ORDER BY sst.h3_index) AS rn
FROM public.scenic_score_tiles sst
WHERE COALESCE(sst.scoring_version, '') <> :'scoring_version'
  AND (
      :'source_scoring_version' = ''
      OR COALESCE(sst.scoring_version, '') = :'source_scoring_version'
  );

CREATE UNIQUE INDEX IF NOT EXISTS dq_v36_batches_h3_idx
    ON public.dq_v36_batches (h3_index);
CREATE INDEX IF NOT EXISTS dq_v36_batches_rn_idx
    ON public.dq_v36_batches (rn);
ANALYZE public.dq_v36_batches;

SELECT COUNT(*) AS target_tiles, MIN(rn) AS min_rn, MAX(rn) AS max_rn
FROM public.dq_v36_batches;

\echo Running v3.6 viewpoint enrichment with chunk_size=:chunk_size source_scoring_version=:source_scoring_version scoring_version=:scoring_version ...
WITH bounds AS (
    SELECT
        gs::INTEGER AS start_rn,
        LEAST(
            (gs + :chunk_size::INTEGER - 1)::INTEGER,
            (SELECT COALESCE(MAX(rn), 0) FROM public.dq_v36_batches)
        ) AS end_rn
    FROM generate_series(
        1,
        (SELECT COALESCE(MAX(rn), 0) FROM public.dq_v36_batches),
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

    WITH batch_tiles AS MATERIALIZED (
        SELECT
            sst.h3_index,
            LEAST(1.0, GREATEST(0.0, COALESCE(vp.viewpoint_score, 0.0))) AS viewpoint_score
        FROM public.scenic_score_tiles sst
        JOIN public.dq_v36_batches b
          ON b.h3_index = sst.h3_index
        LEFT JOIN public.dq_v36_viewpoint_tiles vp
          ON vp.h3_index = sst.h3_index
        WHERE b.rn BETWEEN v_start AND v_end
    )
    UPDATE public.scenic_score_tiles sst
    SET viewpoint_score = bt.viewpoint_score,
        scenic_poi_score = GREATEST(COALESCE(sst.scenic_poi_score, 0.0), bt.viewpoint_score * 0.85),
        poi_score = GREATEST(COALESCE(sst.poi_score, 0.0), COALESCE(sst.scenic_poi_score, 0.0), bt.viewpoint_score * 0.85),
        poi_density = GREATEST(COALESCE(sst.poi_density, 0.0), COALESCE(sst.poi_score, 0.0), bt.viewpoint_score * 0.85),
        scenic_score = LEAST(1.0, GREATEST(0.0, COALESCE(sst.scenic_score, 0.0) + (bt.viewpoint_score * 0.035))),
        last_scored = CURRENT_TIMESTAMP,
        scoring_version = %L
    FROM batch_tiles bt
    WHERE sst.h3_index = bt.h3_index;

    RAISE NOTICE 'Batch %%..%% complete', v_start, v_end;
END
$batch$;
$fmt$, start_rn, end_rn, :'scoring_version')
FROM bounds
\gexec

ANALYZE public.scenic_score_tiles;

\echo Final stats (v3.6 target)
SELECT
    COUNT(*) AS tiles,
    COUNT(*) FILTER (WHERE viewpoint_score > 0.0) AS viewpoint_non_zero_tiles,
    ROUND(MIN(viewpoint_score)::numeric, 6) AS min_viewpoint_score,
    ROUND(AVG(viewpoint_score)::numeric, 6) AS avg_viewpoint_score,
    ROUND(MAX(viewpoint_score)::numeric, 6) AS max_viewpoint_score,
    ROUND(STDDEV_POP(viewpoint_score)::numeric, 6) AS stddev_viewpoint_score,
    ROUND(AVG(scenic_score)::numeric, 6) AS avg_scenic_score,
    ROUND(STDDEV_POP(scenic_score)::numeric, 6) AS stddev_scenic_score
FROM public.scenic_score_tiles
WHERE scoring_version = :'scoring_version';

SELECT scoring_version, COUNT(*)
FROM public.scenic_score_tiles
GROUP BY scoring_version
ORDER BY scoring_version;
