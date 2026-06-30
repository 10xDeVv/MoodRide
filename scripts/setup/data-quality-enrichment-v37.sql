-- Bridge / coastal-road-moment scenic data quality v3.7.
--
-- Usage:
--   psql -d moodride -v ON_ERROR_STOP=1 -v chunk_size=50000 -f scripts/setup/data-quality-enrichment-v37.sql
--
-- Optional:
--   -v source_scoring_version=3.6-viewpoint-calibration
--   -v scoring_version=3.7-bridge-coastal-calibration
--
-- Prereqs:
-- - scenic_score_tiles already has v3.6 viewpoint columns.
-- - v3.3 water_visibility_score / water_crossing_score / coastal_road_score exist.
-- - overture_places exists. Bridge/pier/marina/lighthouse/beach categories are optional hints.
--
-- This is intentionally fast: it reuses already-computed water-road metrics and
-- joins only a small Overture category subset to target H3 tiles.

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
\set scoring_version '3.7-bridge-coastal-calibration'
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
    ADD COLUMN IF NOT EXISTS bridge_coastal_score DOUBLE PRECISION NOT NULL DEFAULT 0.0;

ALTER TABLE public.scenic_score_tiles DROP CONSTRAINT IF EXISTS bridge_coastal_score_range;
ALTER TABLE public.scenic_score_tiles
    ADD CONSTRAINT bridge_coastal_score_range CHECK (bridge_coastal_score >= 0.0 AND bridge_coastal_score <= 1.0);

CREATE TABLE IF NOT EXISTS public.overture_bridge_coastal_category_weights (
    category TEXT PRIMARY KEY,
    bridge_coastal_weight DOUBLE PRECISION NOT NULL CHECK (bridge_coastal_weight >= 0.0 AND bridge_coastal_weight <= 1.0),
    category_group TEXT NOT NULL
);

INSERT INTO public.overture_bridge_coastal_category_weights (category, bridge_coastal_weight, category_group) VALUES
    ('bridge', 1.00, 'bridge'),
    ('pier', 0.90, 'waterfront_structure'),
    ('marina', 0.78, 'waterfront_structure'),
    ('lighthouse', 0.76, 'coastal_landmark'),
    ('beach', 0.64, 'waterfront'),
    ('waterfall', 0.58, 'water_moment')
ON CONFLICT (category) DO UPDATE
SET bridge_coastal_weight = EXCLUDED.bridge_coastal_weight,
    category_group = EXCLUDED.category_group;

ANALYZE public.scenic_score_tiles;
ANALYZE public.overture_places;

\echo Building v3.7 target tile list...
DROP TABLE IF EXISTS public.dq_v37_target_tiles;
CREATE UNLOGGED TABLE public.dq_v37_target_tiles AS
SELECT h3_index, geometry
FROM public.scenic_score_tiles
WHERE COALESCE(scoring_version, '') <> :'scoring_version'
  AND (
      :'source_scoring_version' = ''
      OR COALESCE(scoring_version, '') = :'source_scoring_version'
  );

CREATE UNIQUE INDEX IF NOT EXISTS dq_v37_target_tiles_h3_idx
    ON public.dq_v37_target_tiles (h3_index);
CREATE INDEX IF NOT EXISTS dq_v37_target_tiles_geom_idx
    ON public.dq_v37_target_tiles USING GIST (geometry);
ANALYZE public.dq_v37_target_tiles;

\echo Building v3.7 weighted bridge/coastal source subset...
DROP TABLE IF EXISTS public.dq_v37_weighted_bridge_coastal_places;
CREATE UNLOGGED TABLE public.dq_v37_weighted_bridge_coastal_places AS
SELECT
    p.id,
    p.category,
    p.geometry,
    LEAST(1.0, GREATEST(0.0, COALESCE(p.confidence, 0.75))) AS confidence,
    w.bridge_coastal_weight,
    w.category_group
FROM public.overture_places p
JOIN public.overture_bridge_coastal_category_weights w
  ON w.category = p.category
WHERE p.geometry IS NOT NULL
  AND COALESCE(p.confidence, 0.75) >= 0.35;

CREATE INDEX IF NOT EXISTS dq_v37_weighted_bridge_coastal_places_geom_idx
    ON public.dq_v37_weighted_bridge_coastal_places USING GIST (geometry);
CREATE INDEX IF NOT EXISTS dq_v37_weighted_bridge_coastal_places_category_idx
    ON public.dq_v37_weighted_bridge_coastal_places (category);
ANALYZE public.dq_v37_weighted_bridge_coastal_places;

SELECT COUNT(*) AS weighted_bridge_coastal_place_candidates
FROM public.dq_v37_weighted_bridge_coastal_places;

\echo Building v3.7 bridge/coastal place tile aggregates...
DROP TABLE IF EXISTS public.dq_v37_bridge_coastal_place_tiles;
CREATE UNLOGGED TABLE public.dq_v37_bridge_coastal_place_tiles AS
WITH tile_place_matches AS MATERIALIZED (
    SELECT
        tt.h3_index,
        wbc.category,
        wbc.category_group,
        wbc.bridge_coastal_weight,
        wbc.confidence
    FROM public.dq_v37_target_tiles tt
    JOIN public.dq_v37_weighted_bridge_coastal_places wbc
      ON wbc.geometry && tt.geometry
     AND ST_Covers(tt.geometry, wbc.geometry)
),
tile_raw AS (
    SELECT
        h3_index,
        SUM(bridge_coastal_weight * confidence) AS weighted_bridge_coastal_signal,
        COUNT(*) AS bridge_coastal_place_count,
        COUNT(DISTINCT category_group) AS bridge_coastal_group_count,
        MAX(bridge_coastal_weight * confidence) AS best_bridge_coastal_signal
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
                (LN(1.0 + weighted_bridge_coastal_signal) / LN(1.0 + 3.0)) * 0.60
                + (LN(1.0 + bridge_coastal_group_count) / LN(1.0 + 2.0)) * 0.12
                + best_bridge_coastal_signal * 0.28
            )
        )
    ) AS bridge_coastal_place_score,
    bridge_coastal_place_count,
    bridge_coastal_group_count,
    weighted_bridge_coastal_signal
FROM tile_raw;

CREATE UNIQUE INDEX IF NOT EXISTS dq_v37_bridge_coastal_place_tiles_h3_idx
    ON public.dq_v37_bridge_coastal_place_tiles (h3_index);
ANALYZE public.dq_v37_bridge_coastal_place_tiles;

\echo Building v3.7 enrichment batches...
DROP TABLE IF EXISTS public.dq_v37_batches;
CREATE UNLOGGED TABLE public.dq_v37_batches AS
SELECT
    sst.h3_index,
    ROW_NUMBER() OVER (ORDER BY sst.h3_index) AS rn
FROM public.scenic_score_tiles sst
WHERE COALESCE(sst.scoring_version, '') <> :'scoring_version'
  AND (
      :'source_scoring_version' = ''
      OR COALESCE(sst.scoring_version, '') = :'source_scoring_version'
  );

CREATE UNIQUE INDEX IF NOT EXISTS dq_v37_batches_h3_idx
    ON public.dq_v37_batches (h3_index);
CREATE INDEX IF NOT EXISTS dq_v37_batches_rn_idx
    ON public.dq_v37_batches (rn);
ANALYZE public.dq_v37_batches;

SELECT COUNT(*) AS target_tiles, MIN(rn) AS min_rn, MAX(rn) AS max_rn
FROM public.dq_v37_batches;

\echo Running v3.7 bridge/coastal enrichment with chunk_size=:chunk_size source_scoring_version=:source_scoring_version scoring_version=:scoring_version ...
WITH bounds AS (
    SELECT
        gs::INTEGER AS start_rn,
        LEAST(
            (gs + :chunk_size::INTEGER - 1)::INTEGER,
            (SELECT COALESCE(MAX(rn), 0) FROM public.dq_v37_batches)
        ) AS end_rn
    FROM generate_series(
        1,
        (SELECT COALESCE(MAX(rn), 0) FROM public.dq_v37_batches),
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
            LEAST(1.0, GREATEST(0.0, COALESCE(sst.water_visibility_score, 0.0))) AS water_visibility_score,
            LEAST(1.0, GREATEST(0.0, COALESCE(sst.water_crossing_score, 0.0))) AS water_crossing_score,
            LEAST(1.0, GREATEST(0.0, COALESCE(sst.coastal_road_score, 0.0))) AS coastal_road_score,
            LEAST(1.0, GREATEST(0.0, COALESCE(bc.bridge_coastal_place_score, 0.0))) AS bridge_coastal_place_score
        FROM public.scenic_score_tiles sst
        JOIN public.dq_v37_batches b
          ON b.h3_index = sst.h3_index
        LEFT JOIN public.dq_v37_bridge_coastal_place_tiles bc
          ON bc.h3_index = sst.h3_index
        WHERE b.rn BETWEEN v_start AND v_end
    ),
    scored AS (
        SELECT
            h3_index,
            LEAST(
                1.0,
                GREATEST(
                    0.0,
                    water_visibility_score * 0.30
                    + coastal_road_score * 0.30
                    + water_crossing_score * 0.24
                    + bridge_coastal_place_score * 0.16
                )
            ) AS bridge_coastal_score
        FROM batch_tiles
    )
    UPDATE public.scenic_score_tiles sst
    SET bridge_coastal_score = scored.bridge_coastal_score,
        water_score = GREATEST(COALESCE(sst.water_score, 0.0), scored.bridge_coastal_score * 0.70),
        scenic_score = LEAST(1.0, GREATEST(0.0, COALESCE(sst.scenic_score, 0.0) + (scored.bridge_coastal_score * 0.030))),
        last_scored = CURRENT_TIMESTAMP,
        scoring_version = %L
    FROM scored
    WHERE sst.h3_index = scored.h3_index;

    RAISE NOTICE 'Batch %%..%% complete', v_start, v_end;
END
$batch$;
$fmt$, start_rn, end_rn, :'scoring_version')
FROM bounds
\gexec

ANALYZE public.scenic_score_tiles;

\echo Final stats (v3.7 target)
SELECT
    COUNT(*) AS tiles,
    COUNT(*) FILTER (WHERE bridge_coastal_score > 0.0) AS bridge_coastal_non_zero_tiles,
    ROUND(MIN(bridge_coastal_score)::numeric, 6) AS min_bridge_coastal_score,
    ROUND(AVG(bridge_coastal_score)::numeric, 6) AS avg_bridge_coastal_score,
    ROUND(MAX(bridge_coastal_score)::numeric, 6) AS max_bridge_coastal_score,
    ROUND(STDDEV_POP(bridge_coastal_score)::numeric, 6) AS stddev_bridge_coastal_score,
    ROUND(AVG(scenic_score)::numeric, 6) AS avg_scenic_score,
    ROUND(STDDEV_POP(scenic_score)::numeric, 6) AS stddev_scenic_score
FROM public.scenic_score_tiles
WHERE scoring_version = :'scoring_version';

SELECT scoring_version, COUNT(*)
FROM public.scenic_score_tiles
GROUP BY scoring_version
ORDER BY scoring_version;
