-- National scenic calibration v2.8.
--
-- Purpose:
-- - Keep the completed 2.7 raster-backed data.
-- - Correct the NALCMS land cover class mapping used by prior upgrade scripts.
-- - Reduce DEM surface-model overboost in urban/developed areas.
-- - Recompute composite scenic_score with a less elevation-dominant blend.
--
-- Usage:
--   psql -d moodride -v ON_ERROR_STOP=1 -v chunk_size=2000 -f scripts/setup/data-quality-calibration-v28.sql
--
-- Safe to rerun: targets only tiles not already at 2.8.

SET work_mem = '256MB';
SET temp_buffers = '256MB';
SET max_parallel_workers_per_gather = 4;

DO $$
BEGIN
    IF to_regclass('public.scenic_score_tiles') IS NULL THEN
        RAISE EXCEPTION 'Required table missing: public.scenic_score_tiles';
    END IF;
    IF to_regclass('public.landcover_raster') IS NULL THEN
        RAISE EXCEPTION 'Required table missing: public.landcover_raster';
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS public.landcover_class_weights (
    class_id INTEGER PRIMARY KEY,
    class_label TEXT NOT NULL,
    green_weight DOUBLE PRECISION NOT NULL CHECK (green_weight >= 0.0 AND green_weight <= 1.0),
    is_urban BOOLEAN NOT NULL DEFAULT FALSE
);

INSERT INTO public.landcover_class_weights (class_id, class_label, green_weight, is_urban) VALUES
    -- NALCMS 2020 classes.
    (1, 'Temperate or sub-polar needleleaf forest', 1.0, FALSE),
    (2, 'Sub-polar taiga needleleaf forest', 0.9, FALSE),
    (3, 'Tropical or sub-tropical broadleaf evergreen forest', 1.0, FALSE),
    (4, 'Tropical or sub-tropical broadleaf deciduous forest', 1.0, FALSE),
    (5, 'Temperate or sub-polar broadleaf deciduous forest', 1.0, FALSE),
    (6, 'Mixed forest', 1.0, FALSE),
    (7, 'Tropical or sub-tropical shrubland', 0.6, FALSE),
    (8, 'Temperate or sub-polar shrubland', 0.6, FALSE),
    (9, 'Tropical or sub-tropical grassland', 0.6, FALSE),
    (10, 'Temperate or sub-polar grassland', 0.6, FALSE),
    (11, 'Sub-polar or polar shrubland-lichen-moss', 0.5, FALSE),
    (12, 'Sub-polar or polar grassland-lichen-moss', 0.5, FALSE),
    (13, 'Sub-polar or polar barren-lichen-moss', 0.2, FALSE),
    (14, 'Wetland', 0.8, FALSE),
    (15, 'Cropland', 0.3, FALSE),
    (16, 'Barren lands', 0.0, FALSE),
    (17, 'Urban and built-up', 0.0, TRUE),
    (18, 'Water', 0.0, FALSE),
    (19, 'Snow and ice', 0.0, FALSE),
    -- NLCD fallback classes, retained for mixed local development datasets.
    (21, 'Developed, Open Space', 0.0, TRUE),
    (22, 'Developed, Low Intensity', 0.0, TRUE),
    (23, 'Developed, Medium Intensity', 0.0, TRUE),
    (24, 'Developed, High Intensity', 0.0, TRUE),
    (31, 'Barren Land', 0.0, FALSE),
    (41, 'Deciduous Forest', 1.0, FALSE),
    (42, 'Evergreen Forest', 1.0, FALSE),
    (43, 'Mixed Forest', 1.0, FALSE),
    (52, 'Shrub/Scrub', 0.6, FALSE),
    (71, 'Grassland/Herbaceous', 0.6, FALSE),
    (81, 'Pasture/Hay', 0.3, FALSE),
    (82, 'Cultivated Crops', 0.3, FALSE),
    (90, 'Woody Wetlands', 0.7, FALSE),
    (95, 'Emergent Herbaceous Wetlands', 0.7, FALSE)
ON CONFLICT (class_id) DO UPDATE
SET class_label = EXCLUDED.class_label,
    green_weight = EXCLUDED.green_weight,
    is_urban = EXCLUDED.is_urban;

\if :{?chunk_size}
\else
\set chunk_size 2000
\endif

\echo Building national calibration batches for 2.8...
DROP TABLE IF EXISTS public.dq_v28_batches;
CREATE UNLOGGED TABLE public.dq_v28_batches AS
SELECT
    sst.h3_index,
    ROW_NUMBER() OVER (ORDER BY sst.h3_index) AS rn
FROM public.scenic_score_tiles sst
WHERE COALESCE(sst.scoring_version, '') <> '2.8-urban-aware-elevation-calibration';

CREATE UNIQUE INDEX IF NOT EXISTS dq_v28_batches_h3_idx
    ON public.dq_v28_batches (h3_index);
CREATE INDEX IF NOT EXISTS dq_v28_batches_rn_idx
    ON public.dq_v28_batches (rn);
ANALYZE public.dq_v28_batches;

SELECT COUNT(*) AS target_tiles, MIN(rn) AS min_rn, MAX(rn) AS max_rn
FROM public.dq_v28_batches;

\echo Running 2.8 urban-aware elevation calibration with chunk_size=:chunk_size ...
WITH bounds AS (
    SELECT
        gs::INTEGER AS start_rn,
        LEAST(
            (gs + :chunk_size::INTEGER - 1)::INTEGER,
            (SELECT COALESCE(MAX(rn), 0) FROM public.dq_v28_batches)
        ) AS end_rn
    FROM generate_series(
        1,
        (SELECT COALESCE(MAX(rn), 0) FROM public.dq_v28_batches),
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
        SELECT
            sst.h3_index,
            ST_Transform(sst.geometry, 3979) AS geom_3979
        FROM public.dq_v28_batches b
        JOIN public.scenic_score_tiles sst
          ON sst.h3_index = b.h3_index
        WHERE b.rn BETWEEN v_start AND v_end
    ),
    landcover_pixel_counts AS (
        SELECT
            b.h3_index,
            vc.value::INTEGER AS class_id,
            SUM(vc.count)::DOUBLE PRECISION AS pixel_count
        FROM batch_h3 b
        JOIN public.landcover_raster lr
          ON ST_ConvexHull(lr.rast) && b.geom_3979
         AND ST_Intersects(ST_ConvexHull(lr.rast), b.geom_3979)
        CROSS JOIN LATERAL ST_ValueCount(
            ST_Clip(lr.rast, b.geom_3979, TRUE),
            1,
            TRUE
        ) AS vc(value, count)
        GROUP BY b.h3_index, vc.value
    ),
    landcover_context AS (
        SELECT
            lpc.h3_index,
            SUM(lpc.pixel_count * COALESCE(lcw.green_weight, 0.0))
                / NULLIF(SUM(lpc.pixel_count), 0.0) AS green_score,
            SUM(
                lpc.pixel_count
                * CASE WHEN COALESCE(lcw.is_urban, FALSE) THEN 1.0 ELSE 0.0 END
            ) / NULLIF(SUM(lpc.pixel_count), 0.0) AS urban_proportion
        FROM landcover_pixel_counts lpc
        LEFT JOIN public.landcover_class_weights lcw
          ON lcw.class_id = lpc.class_id
        GROUP BY lpc.h3_index
    ),
    calibrated AS (
        SELECT
            sst.h3_index,
            LEAST(
                1.0,
                GREATEST(0.0, COALESCE(lc.green_score, sst.green_score, 0.0))
            ) AS calibrated_green_score,
            LEAST(
                1.0,
                GREATEST(
                    0.0,
                    ((1.0 - COALESCE(lc.urban_proportion, 0.0)) * 0.6) +
                    ((1.0 - LEAST(1.0, GREATEST(0.0, COALESCE(sst.road_density, 0.0)))) * 0.4)
                )
            ) AS calibrated_solitude_score,
            LEAST(
                1.0,
                GREATEST(
                    0.0,
                    COALESCE(sst.elevation_score, 0.0)
                    * (1.0 - (COALESCE(lc.urban_proportion, 0.0) * 0.80))
                )
            ) AS calibrated_elevation_score
        FROM public.scenic_score_tiles sst
        JOIN batch_h3 b
          ON b.h3_index = sst.h3_index
        LEFT JOIN landcover_context lc
          ON lc.h3_index = sst.h3_index
    )
    UPDATE public.scenic_score_tiles sst
    SET green_score = c.calibrated_green_score,
        natural_land_use = c.calibrated_green_score,
        solitude_score = c.calibrated_solitude_score,
        elevation_score = c.calibrated_elevation_score,
        elevation_variance = c.calibrated_elevation_score,
        scenic_score = LEAST(
            1.0,
            GREATEST(
                0.0,
                COALESCE(sst.water_score, 0.0) * 0.25 +
                COALESCE(c.calibrated_green_score, 0.0) * 0.22 +
                COALESCE(c.calibrated_elevation_score, 0.0) * 0.15 +
                COALESCE(c.calibrated_solitude_score, 0.0) * 0.12 +
                COALESCE(sst.curve_score, 0.0) * 0.11 +
                COALESCE(sst.poi_score, 0.0) * 0.15
            )
        ),
        last_scored = CURRENT_TIMESTAMP,
        scoring_version = '2.8-urban-aware-elevation-calibration'
    FROM calibrated c
    WHERE sst.h3_index = c.h3_index;

    RAISE NOTICE 'Batch %%..%% complete', v_start, v_end;
END
$batch$;
$fmt$, start_rn, end_rn)
FROM bounds
\gexec

ANALYZE public.scenic_score_tiles;

\echo Final stats (2.8 target + global)
SELECT
    COUNT(*) AS tiles,
    COUNT(*) FILTER (WHERE green_score > 0.0) AS green_non_zero_tiles,
    COUNT(*) FILTER (WHERE solitude_score > 0.0) AS solitude_non_zero_tiles,
    COUNT(*) FILTER (WHERE elevation_score > 0.0) AS elevation_non_zero_tiles,
    ROUND(AVG(green_score)::numeric, 6) AS avg_green_score,
    ROUND(STDDEV_POP(green_score)::numeric, 6) AS stddev_green_score,
    ROUND(AVG(elevation_score)::numeric, 6) AS avg_elevation_score,
    ROUND(STDDEV_POP(elevation_score)::numeric, 6) AS stddev_elevation_score,
    ROUND(AVG(scenic_score)::numeric, 6) AS avg_scenic_score,
    ROUND(STDDEV_POP(scenic_score)::numeric, 6) AS stddev_scenic_score
FROM public.scenic_score_tiles
WHERE scoring_version = '2.8-urban-aware-elevation-calibration';

SELECT
    COUNT(*) AS all_tiles,
    ROUND(AVG(scenic_score)::numeric, 6) AS avg_scenic_score,
    ROUND(STDDEV_POP(scenic_score)::numeric, 6) AS stddev_scenic_score
FROM public.scenic_score_tiles;
