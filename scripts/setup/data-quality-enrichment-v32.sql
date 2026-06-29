-- Road-stress / road-class scenic data quality v3.2.
--
-- Usage:
--   psql -d moodride -v ON_ERROR_STOP=1 -v chunk_size=50000 -f scripts/setup/data-quality-enrichment-v32.sql
--
-- Optional:
--   -v source_scoring_version=3.1-darkness-urban-penalty-calibration
--   -v scoring_version=3.2-road-stress-calibration
--
-- Prereqs:
-- - scenic_score_tiles already has v3.1 enrichment columns.
-- - road_segments has h3_tile_index, road_type, surface, speed_limit_kmh, and length_meters.
--
-- This script derives road_stress_score as a badness score:
--   0.0 = calmer/local/lower-stress road context
--   1.0 = high-speed/major-road/high-stress road context

\if :{?chunk_size}
\else
\set chunk_size 50000
\endif

\if :{?scoring_version}
\else
\set scoring_version '3.2-road-stress-calibration'
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
    IF to_regclass('public.road_segments') IS NULL THEN
        RAISE EXCEPTION 'Required table missing: public.road_segments';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'road_segments'
          AND column_name = 'h3_tile_index'
    ) THEN
        RAISE EXCEPTION 'road_segments.h3_tile_index is missing; road-stress scoring needs per-H3 road assignment.';
    END IF;
END $$;

ALTER TABLE public.scenic_score_tiles
    ADD COLUMN IF NOT EXISTS road_stress_score DOUBLE PRECISION NOT NULL DEFAULT 0.0;

ALTER TABLE public.scenic_score_tiles DROP CONSTRAINT IF EXISTS road_stress_score_range;
ALTER TABLE public.scenic_score_tiles
    ADD CONSTRAINT road_stress_score_range CHECK (road_stress_score >= 0.0 AND road_stress_score <= 1.0);

CREATE INDEX IF NOT EXISTS road_segments_h3_tile_idx
    ON public.road_segments (h3_tile_index);

ANALYZE public.road_segments;
ANALYZE public.scenic_score_tiles;

\echo Building 3.2 road-stress tile aggregates...
DROP TABLE IF EXISTS public.dq_v32_road_stress_tiles;
CREATE UNLOGGED TABLE public.dq_v32_road_stress_tiles AS
WITH segment_scores AS (
    SELECT
        rs.h3_tile_index AS h3_index,
        GREATEST(COALESCE(rs.length_meters, 0.0), 1.0) AS length_weight,
        CASE lower(COALESCE(rs.road_type, 'unknown'))
            WHEN 'motorway' THEN 1.00
            WHEN 'motorway_link' THEN 0.92
            WHEN 'trunk' THEN 0.90
            WHEN 'trunk_link' THEN 0.82
            WHEN 'primary' THEN 0.74
            WHEN 'primary_link' THEN 0.68
            WHEN 'secondary' THEN 0.54
            WHEN 'secondary_link' THEN 0.50
            WHEN 'tertiary' THEN 0.40
            WHEN 'tertiary_link' THEN 0.38
            WHEN 'unclassified' THEN 0.34
            WHEN 'residential' THEN 0.22
            WHEN 'living_street' THEN 0.12
            WHEN 'service' THEN 0.24
            WHEN 'track' THEN 0.46
            ELSE 0.36
        END AS road_type_stress,
        CASE
            WHEN COALESCE(rs.speed_limit_kmh, 0) <= 0 THEN 0.32
            WHEN rs.speed_limit_kmh <= 30 THEN 0.12
            WHEN rs.speed_limit_kmh <= 40 THEN 0.18
            WHEN rs.speed_limit_kmh <= 50 THEN 0.26
            WHEN rs.speed_limit_kmh <= 60 THEN 0.34
            WHEN rs.speed_limit_kmh <= 80 THEN 0.54
            WHEN rs.speed_limit_kmh <= 100 THEN 0.78
            ELSE 0.92
        END AS speed_stress,
        CASE lower(COALESCE(rs.surface, 'unknown'))
            WHEN 'asphalt' THEN 0.02
            WHEN 'paved' THEN 0.04
            WHEN 'concrete' THEN 0.04
            WHEN 'concrete:lanes' THEN 0.05
            WHEN 'concrete:plates' THEN 0.06
            WHEN 'sett' THEN 0.16
            WHEN 'paving_stones' THEN 0.14
            WHEN 'compacted' THEN 0.18
            WHEN 'fine_gravel' THEN 0.22
            WHEN 'gravel' THEN 0.28
            WHEN 'unpaved' THEN 0.42
            WHEN 'dirt' THEN 0.48
            WHEN 'earth' THEN 0.50
            WHEN 'ground' THEN 0.50
            WHEN 'mud' THEN 0.68
            ELSE 0.08
        END AS surface_stress,
        CASE
            WHEN lower(COALESCE(rs.road_type, 'unknown')) IN ('motorway', 'motorway_link', 'trunk', 'trunk_link', 'primary', 'primary_link') THEN 1.0
            ELSE 0.0
        END AS major_road_flag
    FROM public.road_segments rs
    WHERE rs.h3_tile_index IS NOT NULL
      AND rs.h3_tile_index <> ''
),
combined AS (
    SELECT
        h3_index,
        length_weight,
        LEAST(
            1.0,
            GREATEST(
                0.0,
                road_type_stress * 0.55 +
                speed_stress * 0.30 +
                surface_stress * 0.15
            )
        ) AS segment_stress,
        major_road_flag
    FROM segment_scores
)
SELECT
    h3_index,
    LEAST(
        1.0,
        GREATEST(
            0.0,
            SUM(segment_stress * length_weight) / NULLIF(SUM(length_weight), 0.0)
        )
    ) AS road_stress_score,
    SUM(major_road_flag * length_weight) / NULLIF(SUM(length_weight), 0.0) AS major_road_share,
    COUNT(*) AS road_segment_count,
    SUM(length_weight) AS total_road_length_meters
FROM combined
GROUP BY h3_index;

CREATE UNIQUE INDEX IF NOT EXISTS dq_v32_road_stress_tiles_h3_idx
    ON public.dq_v32_road_stress_tiles (h3_index);
ANALYZE public.dq_v32_road_stress_tiles;

\echo Building 3.2 enrichment batches...
DROP TABLE IF EXISTS public.dq_v32_batches;
CREATE UNLOGGED TABLE public.dq_v32_batches AS
SELECT
    sst.h3_index,
    ROW_NUMBER() OVER (ORDER BY sst.h3_index) AS rn
FROM public.scenic_score_tiles sst
WHERE COALESCE(sst.scoring_version, '') <> :'scoring_version'
  AND (
      :'source_scoring_version' = ''
      OR COALESCE(sst.scoring_version, '') = :'source_scoring_version'
  );

CREATE UNIQUE INDEX IF NOT EXISTS dq_v32_batches_h3_idx
    ON public.dq_v32_batches (h3_index);
CREATE INDEX IF NOT EXISTS dq_v32_batches_rn_idx
    ON public.dq_v32_batches (rn);
ANALYZE public.dq_v32_batches;

SELECT COUNT(*) AS target_tiles, MIN(rn) AS min_rn, MAX(rn) AS max_rn
FROM public.dq_v32_batches;

\echo Running 3.2 road-stress enrichment with chunk_size=:chunk_size source_scoring_version=:source_scoring_version scoring_version=:scoring_version ...
WITH bounds AS (
    SELECT
        gs::INTEGER AS start_rn,
        LEAST(
            (gs + :chunk_size::INTEGER - 1)::INTEGER,
            (SELECT COALESCE(MAX(rn), 0) FROM public.dq_v32_batches)
        ) AS end_rn
    FROM generate_series(
        1,
        (SELECT COALESCE(MAX(rn), 0) FROM public.dq_v32_batches),
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
            LEAST(1.0, GREATEST(0.0, COALESCE(sst.solitude_score, 0.0))) AS prior_solitude_score,
            LEAST(1.0, GREATEST(0.0, COALESCE(sst.curve_score, 0.0))) AS curve_score,
            LEAST(1.0, GREATEST(0.0, GREATEST(COALESCE(sst.poi_score, 0.0), COALESCE(sst.overture_poi_score, 0.0)))) AS poi_score,
            LEAST(1.0, GREATEST(0.0, COALESCE(sst.park_score, 0.0))) AS park_score,
            LEAST(1.0, GREATEST(0.0, COALESCE(sst.building_density_score, 0.0))) AS building_density_score,
            LEAST(1.0, GREATEST(0.0, COALESCE(sst.darkness_score, 0.5))) AS darkness_score,
            LEAST(1.0, GREATEST(0.0, COALESCE(sst.urban_penalty_score, 0.0))) AS urban_penalty_score,
            LEAST(1.0, GREATEST(0.0, COALESCE(sst.road_density, 0.0))) AS road_density
        FROM public.scenic_score_tiles sst
        JOIN public.dq_v32_batches b
          ON b.h3_index = sst.h3_index
        WHERE b.rn BETWEEN v_start AND v_end
    ),
    combined AS (
        SELECT
            bt.*,
            LEAST(
                1.0,
                GREATEST(
                    0.0,
                    COALESCE(rst.road_stress_score, LEAST(0.35, bt.road_density * 0.35))
                )
            ) AS road_stress_score
        FROM batch_tiles bt
        LEFT JOIN public.dq_v32_road_stress_tiles rst
          ON rst.h3_index = bt.h3_index
    ),
    rescored AS (
        SELECT
            c.*,
            LEAST(
                1.0,
                GREATEST(
                    0.0,
                    c.prior_solitude_score * 0.50 +
                    (1.0 - c.building_density_score) * 0.18 +
                    c.darkness_score * 0.17 +
                    (1.0 - c.road_density) * 0.10 +
                    (1.0 - c.road_stress_score) * 0.05
                )
            ) AS calibrated_solitude_score
        FROM combined c
    )
    UPDATE public.scenic_score_tiles sst
    SET road_stress_score = r.road_stress_score,
        solitude_score = r.calibrated_solitude_score,
        scenic_score = LEAST(
            1.0,
            GREATEST(
                0.0,
                (
                    r.water_score * 0.22 +
                    r.green_score * 0.20 +
                    r.elevation_score * 0.14 +
                    r.calibrated_solitude_score * 0.14 +
                    r.curve_score * 0.10 +
                    r.poi_score * 0.12 +
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

\echo Final stats (3.2 target)
SELECT
    COUNT(*) AS tiles,
    COUNT(*) FILTER (WHERE road_stress_score > 0.0) AS road_stress_non_zero_tiles,
    ROUND(MIN(road_stress_score)::numeric, 6) AS min_road_stress_score,
    ROUND(AVG(road_stress_score)::numeric, 6) AS avg_road_stress_score,
    ROUND(MAX(road_stress_score)::numeric, 6) AS max_road_stress_score,
    ROUND(STDDEV_POP(road_stress_score)::numeric, 6) AS stddev_road_stress_score,
    ROUND(AVG(solitude_score)::numeric, 6) AS avg_solitude_score,
    ROUND(AVG(scenic_score)::numeric, 6) AS avg_scenic_score,
    ROUND(STDDEV_POP(scenic_score)::numeric, 6) AS stddev_scenic_score
FROM public.scenic_score_tiles
WHERE scoring_version = :'scoring_version';

SELECT scoring_version, COUNT(*)
FROM public.scenic_score_tiles
GROUP BY scoring_version
ORDER BY scoring_version;
