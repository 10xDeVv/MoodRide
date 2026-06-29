-- Scenic POI / discovery-stop data quality v3.5.
--
-- Usage:
--   psql -d moodride -v ON_ERROR_STOP=1 -v chunk_size=50000 -f scripts/setup/data-quality-enrichment-v35.sql
--
-- Optional:
--   -v source_scoring_version=3.4-tree-canopy-calibration
--   -v scoring_version=3.5-scenic-poi-calibration
--
-- Prereqs:
-- - scenic_score_tiles already has v3.4 tree-canopy columns.
-- - overture_places exists with category/confidence/geometry.
-- - This is a scenic-place proxy. OSM viewpoint-specific data can be layered in later.

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
\set scoring_version '3.5-scenic-poi-calibration'
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
    ADD COLUMN IF NOT EXISTS scenic_poi_score DOUBLE PRECISION NOT NULL DEFAULT 0.0;

ALTER TABLE public.scenic_score_tiles DROP CONSTRAINT IF EXISTS scenic_poi_score_range;
ALTER TABLE public.scenic_score_tiles
    ADD CONSTRAINT scenic_poi_score_range CHECK (scenic_poi_score >= 0.0 AND scenic_poi_score <= 1.0);

CREATE TABLE IF NOT EXISTS public.overture_scenic_poi_category_weights (
    category TEXT PRIMARY KEY,
    scenic_weight DOUBLE PRECISION NOT NULL CHECK (scenic_weight >= 0.0 AND scenic_weight <= 1.0),
    category_group TEXT NOT NULL
);

INSERT INTO public.overture_scenic_poi_category_weights (category, scenic_weight, category_group) VALUES
    ('lookout', 1.00, 'viewpoint'),
    ('viewpoint', 1.00, 'viewpoint'),
    ('scenic_lookout', 1.00, 'viewpoint'),
    ('waterfall', 0.96, 'natural_feature'),
    ('lighthouse', 0.94, 'landmark'),
    ('national_park', 0.92, 'park'),
    ('state_park', 0.88, 'park'),
    ('nature_reserve', 0.88, 'park'),
    ('botanical_garden', 0.86, 'park'),
    ('forest', 0.84, 'natural_feature'),
    ('mountain', 0.84, 'natural_feature'),
    ('beach', 0.82, 'waterfront'),
    ('lake', 0.78, 'waterfront'),
    ('river', 0.74, 'waterfront'),
    ('pier', 0.72, 'waterfront'),
    ('marina', 0.68, 'waterfront'),
    ('campground', 0.64, 'outdoor'),
    ('hiking_trail', 0.62, 'outdoor'),
    ('trail', 0.62, 'outdoor'),
    ('mountain_bike_trails', 0.58, 'outdoor'),
    ('park', 0.56, 'park'),
    ('landmark_and_historical_building', 0.54, 'landmark'),
    ('monument', 0.52, 'landmark'),
    ('castle', 0.52, 'landmark'),
    ('fort', 0.50, 'landmark'),
    ('bridge', 0.46, 'landmark'),
    ('museum', 0.44, 'culture'),
    ('history_museum', 0.46, 'culture'),
    ('community_museum', 0.42, 'culture'),
    ('art_museum', 0.44, 'culture'),
    ('science_museum', 0.38, 'culture'),
    ('art_gallery', 0.40, 'culture'),
    ('zoo', 0.36, 'culture'),
    ('aquarium', 0.36, 'culture'),
    ('winery', 0.34, 'discovery'),
    ('farm', 0.28, 'discovery')
ON CONFLICT (category) DO UPDATE
SET scenic_weight = EXCLUDED.scenic_weight,
    category_group = EXCLUDED.category_group;

ANALYZE public.scenic_score_tiles;
ANALYZE public.overture_places;

CREATE TEMP TABLE dq_v35_settings AS
SELECT
    :'scoring_version'::TEXT AS scoring_version,
    :'source_scoring_version'::TEXT AS source_scoring_version;

\echo Building v3.5 target tile list...
DROP TABLE IF EXISTS public.dq_v35_target_tiles;
CREATE UNLOGGED TABLE public.dq_v35_target_tiles AS
SELECT h3_index, geometry
FROM public.scenic_score_tiles
WHERE COALESCE(scoring_version, '') <> :'scoring_version'
  AND (
      :'source_scoring_version' = ''
      OR COALESCE(scoring_version, '') = :'source_scoring_version'
  );

CREATE UNIQUE INDEX IF NOT EXISTS dq_v35_target_tiles_h3_idx
    ON public.dq_v35_target_tiles (h3_index);
CREATE INDEX IF NOT EXISTS dq_v35_target_tiles_geom_idx
    ON public.dq_v35_target_tiles USING GIST (geometry);
ANALYZE public.dq_v35_target_tiles;

\echo Building v3.5 weighted scenic POI source subset...
DROP TABLE IF EXISTS public.dq_v35_weighted_scenic_places;
CREATE UNLOGGED TABLE public.dq_v35_weighted_scenic_places AS
SELECT
    p.id,
    p.category,
    p.geometry,
    LEAST(1.0, GREATEST(0.0, COALESCE(p.confidence, 0.75))) AS confidence,
    w.scenic_weight,
    w.category_group
FROM public.overture_places p
JOIN public.overture_scenic_poi_category_weights w
  ON w.category = p.category
WHERE p.geometry IS NOT NULL
  AND COALESCE(p.confidence, 0.75) >= 0.35;

CREATE INDEX IF NOT EXISTS dq_v35_weighted_scenic_places_geom_idx
    ON public.dq_v35_weighted_scenic_places USING GIST (geometry);
CREATE INDEX IF NOT EXISTS dq_v35_weighted_scenic_places_category_idx
    ON public.dq_v35_weighted_scenic_places (category);
ANALYZE public.dq_v35_weighted_scenic_places;

SELECT COUNT(*) AS weighted_scenic_place_candidates
FROM public.dq_v35_weighted_scenic_places;

\echo Building v3.5 scenic POI tile aggregates...
DROP TABLE IF EXISTS public.dq_v35_scenic_poi_tiles;
CREATE UNLOGGED TABLE public.dq_v35_scenic_poi_tiles AS
WITH tile_place_matches AS MATERIALIZED (
    SELECT
        tt.h3_index,
        wsp.category,
        wsp.category_group,
        wsp.scenic_weight,
        wsp.confidence
    FROM public.dq_v35_target_tiles tt
    JOIN public.dq_v35_weighted_scenic_places wsp
      ON wsp.geometry && tt.geometry
     AND ST_Covers(tt.geometry, wsp.geometry)
),
tile_raw AS (
    SELECT
        h3_index,
        SUM(scenic_weight * confidence) AS weighted_place_signal,
        COUNT(*) AS scenic_place_count,
        COUNT(DISTINCT category_group) AS scenic_group_count,
        MAX(scenic_weight * confidence) AS best_place_signal
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
                (LN(1.0 + weighted_place_signal) / LN(1.0 + 5.0)) * 0.62
                + (LN(1.0 + scenic_group_count) / LN(1.0 + 3.0)) * 0.18
                + best_place_signal * 0.20
            )
        )
    ) AS scenic_poi_score,
    scenic_place_count,
    scenic_group_count,
    weighted_place_signal
FROM tile_raw;

CREATE UNIQUE INDEX IF NOT EXISTS dq_v35_scenic_poi_tiles_h3_idx
    ON public.dq_v35_scenic_poi_tiles (h3_index);
ANALYZE public.dq_v35_scenic_poi_tiles;

\echo Building v3.5 enrichment batches...
DROP TABLE IF EXISTS public.dq_v35_batches;
CREATE UNLOGGED TABLE public.dq_v35_batches AS
SELECT
    sst.h3_index,
    ROW_NUMBER() OVER (ORDER BY sst.h3_index) AS rn
FROM public.scenic_score_tiles sst
WHERE COALESCE(sst.scoring_version, '') <> :'scoring_version'
  AND (
      :'source_scoring_version' = ''
      OR COALESCE(sst.scoring_version, '') = :'source_scoring_version'
  );

CREATE UNIQUE INDEX IF NOT EXISTS dq_v35_batches_h3_idx
    ON public.dq_v35_batches (h3_index);
CREATE INDEX IF NOT EXISTS dq_v35_batches_rn_idx
    ON public.dq_v35_batches (rn);
ANALYZE public.dq_v35_batches;

SELECT COUNT(*) AS target_tiles, MIN(rn) AS min_rn, MAX(rn) AS max_rn
FROM public.dq_v35_batches;

\echo Running v3.5 scenic-POI enrichment with chunk_size=:chunk_size source_scoring_version=:source_scoring_version scoring_version=:scoring_version ...
WITH bounds AS (
    SELECT
        gs::INTEGER AS start_rn,
        LEAST(
            (gs + :chunk_size::INTEGER - 1)::INTEGER,
            (SELECT COALESCE(MAX(rn), 0) FROM public.dq_v35_batches)
        ) AS end_rn
    FROM generate_series(
        1,
        (SELECT COALESCE(MAX(rn), 0) FROM public.dq_v35_batches),
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
            LEAST(1.0, GREATEST(0.0, COALESCE(sst.water_score, 0.0))) AS water_score,
            LEAST(1.0, GREATEST(0.0, COALESCE(sst.green_score, 0.0))) AS green_score,
            LEAST(1.0, GREATEST(0.0, COALESCE(sst.elevation_score, 0.0))) AS elevation_score,
            LEAST(1.0, GREATEST(0.0, COALESCE(sst.solitude_score, 0.0))) AS solitude_score,
            LEAST(1.0, GREATEST(0.0, COALESCE(sst.curve_score, 0.0))) AS curve_score,
            LEAST(1.0, GREATEST(0.0, COALESCE(sst.poi_score, 0.0))) AS prior_poi_score,
            LEAST(1.0, GREATEST(0.0, COALESCE(sst.overture_poi_score, 0.0))) AS overture_poi_score,
            LEAST(1.0, GREATEST(0.0, COALESCE(sp.scenic_poi_score, 0.0))) AS scenic_poi_score,
            LEAST(1.0, GREATEST(0.0, COALESCE(sst.park_score, 0.0))) AS park_score,
            LEAST(1.0, GREATEST(0.0, COALESCE(sst.urban_penalty_score, 0.0))) AS urban_penalty_score,
            LEAST(1.0, GREATEST(0.0, COALESCE(sst.road_stress_score, 0.0))) AS road_stress_score
        FROM public.scenic_score_tiles sst
        JOIN public.dq_v35_batches b
          ON b.h3_index = sst.h3_index
        LEFT JOIN public.dq_v35_scenic_poi_tiles sp
          ON sp.h3_index = sst.h3_index
        WHERE b.rn BETWEEN v_start AND v_end
    ),
    rescored AS (
        SELECT
            bt.*,
            GREATEST(bt.prior_poi_score, bt.overture_poi_score, bt.scenic_poi_score) AS calibrated_poi_score
        FROM batch_tiles bt
    )
    UPDATE public.scenic_score_tiles sst
    SET scenic_poi_score = r.scenic_poi_score,
        poi_score = r.calibrated_poi_score,
        poi_density = GREATEST(COALESCE(sst.poi_density, 0.0), r.calibrated_poi_score),
        scenic_score = LEAST(
            1.0,
            GREATEST(
                0.0,
                (
                    r.water_score * 0.21 +
                    r.green_score * 0.20 +
                    r.elevation_score * 0.14 +
                    r.solitude_score * 0.14 +
                    r.curve_score * 0.10 +
                    r.calibrated_poi_score * 0.13 +
                    r.park_score * 0.08 -
                    r.urban_penalty_score * 0.08 -
                    r.road_stress_score * 0.06
                )
            )
        ),
        last_scored = CURRENT_TIMESTAMP,
        scoring_version = %L
    FROM rescored r
    WHERE sst.h3_index = r.h3_index;

    RAISE NOTICE 'Batch %%..%% complete', v_start, v_end;
END
$batch$;
$fmt$, start_rn, end_rn, :'scoring_version')
FROM bounds
\gexec

ANALYZE public.scenic_score_tiles;

\echo Final stats (v3.5 target)
SELECT
    COUNT(*) AS tiles,
    COUNT(*) FILTER (WHERE scenic_poi_score > 0.0) AS scenic_poi_non_zero_tiles,
    ROUND(MIN(scenic_poi_score)::numeric, 6) AS min_scenic_poi_score,
    ROUND(AVG(scenic_poi_score)::numeric, 6) AS avg_scenic_poi_score,
    ROUND(MAX(scenic_poi_score)::numeric, 6) AS max_scenic_poi_score,
    ROUND(STDDEV_POP(scenic_poi_score)::numeric, 6) AS stddev_scenic_poi_score,
    ROUND(AVG(poi_score)::numeric, 6) AS avg_poi_score,
    ROUND(AVG(scenic_score)::numeric, 6) AS avg_scenic_score,
    ROUND(STDDEV_POP(scenic_score)::numeric, 6) AS stddev_scenic_score
FROM public.scenic_score_tiles
WHERE scoring_version = :'scoring_version';

SELECT scoring_version, COUNT(*)
FROM public.scenic_score_tiles
GROUP BY scoring_version
ORDER BY scoring_version;
