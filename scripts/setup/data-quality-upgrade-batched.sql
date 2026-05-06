-- Batched full-coverage data-quality upgrade (landcover + DEM).
--
-- Usage:
--   psql -d moodride -v ON_ERROR_STOP=1 -v chunk_size=500 -f scripts/setup/data-quality-upgrade-batched.sql
--
-- Notes:
-- - Updates all tiles not already at scoring_version '2.4-raster-data-quality-upgrade-national'.
-- - Elevation will be 0 outside the DEM footprint (current raster is a subset).

DO $$
BEGIN
    IF to_regclass('public.scenic_score_tiles') IS NULL THEN
        RAISE EXCEPTION 'Required table missing: public.scenic_score_tiles';
    END IF;
    IF to_regclass('public.landcover_raster') IS NULL THEN
        RAISE EXCEPTION 'Required table missing: public.landcover_raster';
    END IF;
    IF to_regclass('public.elevation_raster') IS NULL THEN
        RAISE EXCEPTION 'Required table missing: public.elevation_raster';
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS public.landcover_class_weights (
    class_id INTEGER PRIMARY KEY,
    class_label TEXT NOT NULL,
    green_weight DOUBLE PRECISION NOT NULL CHECK (green_weight >= 0.0 AND green_weight <= 1.0),
    is_urban BOOLEAN NOT NULL DEFAULT FALSE
);

INSERT INTO public.landcover_class_weights (class_id, class_label, green_weight, is_urban) VALUES
    (11, 'Open Water', 0.0, FALSE),
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
    (95, 'Emergent Herbaceous Wetlands', 0.7, FALSE),
    (1, 'Canada LC Class 1', 1.0, FALSE),
    (2, 'Canada LC Class 2', 1.0, FALSE),
    (3, 'Canada LC Class 3', 1.0, FALSE),
    (4, 'Canada LC Class 4', 0.8, FALSE),
    (5, 'Canada LC Class 5', 0.6, FALSE),
    (6, 'Canada LC Class 6', 0.6, FALSE),
    (7, 'Canada LC Class 7', 0.7, FALSE),
    (8, 'Canada LC Class 8', 0.3, FALSE),
    (9, 'Canada LC Class 9', 0.0, FALSE),
    (10, 'Canada LC Class 10', 0.0, TRUE),
    (12, 'Canada LC Class 12', 0.0, FALSE),
    (13, 'Canada LC Class 13', 0.7, FALSE),
    (14, 'Canada LC Class 14', 0.6, FALSE),
    (15, 'Canada LC Class 15', 0.2, FALSE),
    (16, 'Canada LC Class 16', 0.0, FALSE),
    (17, 'Canada LC Class 17', 0.0, FALSE),
    (18, 'Canada LC Class 18', 0.0, FALSE),
    (19, 'Canada LC Class 19', 0.0, FALSE)
ON CONFLICT (class_id) DO UPDATE
SET class_label = EXCLUDED.class_label,
    green_weight = EXCLUDED.green_weight,
    is_urban = EXCLUDED.is_urban;

-- Optional caller override via: -v chunk_size=500
\if :{?chunk_size}
\else
\set chunk_size 500
\endif

\echo Building national target batches...
DROP TABLE IF EXISTS public.dq_national_batches;
CREATE UNLOGGED TABLE public.dq_national_batches AS
SELECT
    sst.h3_index,
    ROW_NUMBER() OVER (ORDER BY sst.h3_index) AS rn
FROM scenic_score_tiles sst
WHERE COALESCE(sst.scoring_version, '') <> '2.4-raster-data-quality-upgrade-national';

CREATE UNIQUE INDEX IF NOT EXISTS dq_national_batches_h3_idx
    ON public.dq_national_batches (h3_index);
CREATE INDEX IF NOT EXISTS dq_national_batches_rn_idx
    ON public.dq_national_batches (rn);
ANALYZE public.dq_national_batches;

SELECT COUNT(*) AS target_tiles, MIN(rn) AS min_rn, MAX(rn) AS max_rn
FROM public.dq_national_batches;

\echo Running batched national updates with chunk_size=:chunk_size ...
WITH bounds AS (
    SELECT
        gs::INTEGER AS start_rn,
        LEAST(
            (gs + :chunk_size::INTEGER - 1)::INTEGER,
            (SELECT COALESCE(MAX(rn), 0) FROM public.dq_national_batches)
        ) AS end_rn
    FROM generate_series(
        1,
        (SELECT COALESCE(MAX(rn), 0) FROM public.dq_national_batches),
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

    -- 1) Green + solitude for this batch.
    WITH batch_h3 AS (
        SELECT h3_index
        FROM public.dq_national_batches
        WHERE rn BETWEEN v_start AND v_end
    ),
    landcover_pixel_counts AS (
        SELECT
            sst.h3_index,
            vc.value::INTEGER AS class_id,
            SUM(vc.count)::DOUBLE PRECISION AS pixel_count
        FROM scenic_score_tiles sst
        JOIN batch_h3 b
          ON b.h3_index = sst.h3_index
        JOIN landcover_raster lr
          ON ST_Intersects(
                lr.rast,
                CASE
                    WHEN ST_SRID(sst.geometry) = ST_SRID(lr.rast) THEN sst.geometry
                    ELSE ST_Transform(sst.geometry, ST_SRID(lr.rast))
                END
             )
        CROSS JOIN LATERAL ST_ValueCount(
            ST_Clip(
                lr.rast,
                CASE
                    WHEN ST_SRID(sst.geometry) = ST_SRID(lr.rast) THEN sst.geometry
                    ELSE ST_Transform(sst.geometry, ST_SRID(lr.rast))
                END,
                TRUE
            ),
            1,
            TRUE
        ) AS vc(value, count)
        GROUP BY sst.h3_index, vc.value
    ),
    landcover_aggregates AS (
        SELECT
            lpc.h3_index,
            SUM(lpc.pixel_count) AS total_pixels,
            SUM(lpc.pixel_count * COALESCE(lcw.green_weight, 0.0))
                / NULLIF(SUM(lpc.pixel_count), 0.0) AS green_score,
            SUM(
                lpc.pixel_count
                * CASE WHEN COALESCE(lcw.is_urban, FALSE) THEN 1.0 ELSE 0.0 END
            ) / NULLIF(SUM(lpc.pixel_count), 0.0) AS urban_proportion
        FROM landcover_pixel_counts lpc
        LEFT JOIN landcover_class_weights lcw
          ON lcw.class_id = lpc.class_id
        GROUP BY lpc.h3_index
    )
    UPDATE scenic_score_tiles sst
    SET green_score = LEAST(1.0, GREATEST(0.0, COALESCE(la.green_score, 0.0))),
        solitude_score = LEAST(
            1.0,
            GREATEST(
                0.0,
                ((1.0 - COALESCE(la.urban_proportion, 0.0)) * 0.6) +
                ((1.0 - LEAST(1.0, GREATEST(0.0, COALESCE(sst.road_density, 0.0)))) * 0.4)
            )
        ),
        natural_land_use = LEAST(1.0, GREATEST(0.0, COALESCE(la.green_score, 0.0)))
    FROM batch_h3 b
    LEFT JOIN landcover_aggregates la
      ON la.h3_index = b.h3_index
    WHERE sst.h3_index = b.h3_index;

    -- 2) Elevation for this batch (0 outside DEM coverage).
    WITH batch_h3 AS (
        SELECT h3_index
        FROM public.dq_national_batches
        WHERE rn BETWEEN v_start AND v_end
    ),
    elevation_stats AS (
        SELECT
            sst.h3_index,
            (ST_SummaryStatsAgg(
                ST_Clip(
                    er.rast,
                    CASE
                        WHEN ST_SRID(sst.geometry) = ST_SRID(er.rast) THEN sst.geometry
                        ELSE ST_Transform(sst.geometry, ST_SRID(er.rast))
                    END,
                    TRUE
                ),
                1,
                TRUE
            )).stddev AS elevation_stddev_m
        FROM scenic_score_tiles sst
        JOIN batch_h3 b
          ON b.h3_index = sst.h3_index
        JOIN elevation_raster er
          ON ST_Intersects(
                er.rast,
                CASE
                    WHEN ST_SRID(sst.geometry) = ST_SRID(er.rast) THEN sst.geometry
                    ELSE ST_Transform(sst.geometry, ST_SRID(er.rast))
                END
             )
        GROUP BY sst.h3_index
    )
    UPDATE scenic_score_tiles sst
    SET elevation_score = LEAST(1.0, GREATEST(0.0, COALESCE(es.elevation_stddev_m, 0.0) / 100.0)),
        elevation_variance = LEAST(1.0, GREATEST(0.0, COALESCE(es.elevation_stddev_m, 0.0) / 100.0))
    FROM batch_h3 b
    LEFT JOIN elevation_stats es
      ON es.h3_index = b.h3_index
    WHERE sst.h3_index = b.h3_index;

    -- 3) Composite score for this batch.
    WITH batch_h3 AS (
        SELECT h3_index
        FROM public.dq_national_batches
        WHERE rn BETWEEN v_start AND v_end
    )
    UPDATE scenic_score_tiles sst
    SET scenic_score = LEAST(
            1.0,
            GREATEST(
                0.0,
                COALESCE(water_score, 0.0) * 0.25 +
                COALESCE(green_score, 0.0) * 0.20 +
                COALESCE(elevation_score, 0.0) * 0.20 +
                COALESCE(solitude_score, 0.0) * 0.10 +
                COALESCE(curve_score, 0.0) * 0.10 +
                COALESCE(poi_score, 0.0) * 0.15
            )
        ),
        last_scored = CURRENT_TIMESTAMP,
        scoring_version = '2.4-raster-data-quality-upgrade-national'
    WHERE EXISTS (
        SELECT 1
        FROM batch_h3 b
        WHERE b.h3_index = sst.h3_index
    );

    RAISE NOTICE 'Batch %%..%% complete', v_start, v_end;
END
$batch$;
$fmt$, start_rn, end_rn)
FROM bounds
\gexec

ANALYZE scenic_score_tiles;

\echo Final stats (national)
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
FROM scenic_score_tiles
WHERE scoring_version = '2.4-raster-data-quality-upgrade-national';
-- Batched national data-quality upgrade over all tiles.
--
-- Usage:
--   psql -d moodride -v ON_ERROR_STOP=1 -v chunk_size=500 -f scripts/setup/data-quality-upgrade-batched.sql
--
-- Notes:
-- - Updates green/solitude for all tiles using landcover_raster.
-- - Updates elevation only where elevation_raster intersects.
-- - Recomputes scenic_score for all tiles in each batch.

DO $$
BEGIN
    IF to_regclass('public.scenic_score_tiles') IS NULL THEN
        RAISE EXCEPTION 'Required table missing: public.scenic_score_tiles';
    END IF;
    IF to_regclass('public.landcover_raster') IS NULL THEN
        RAISE EXCEPTION 'Required table missing: public.landcover_raster';
    END IF;
    IF to_regclass('public.elevation_raster') IS NULL THEN
        RAISE EXCEPTION 'Required table missing: public.elevation_raster';
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS public.landcover_class_weights (
    class_id INTEGER PRIMARY KEY,
    class_label TEXT NOT NULL,
    green_weight DOUBLE PRECISION NOT NULL CHECK (green_weight >= 0.0 AND green_weight <= 1.0),
    is_urban BOOLEAN NOT NULL DEFAULT FALSE
);

INSERT INTO public.landcover_class_weights (class_id, class_label, green_weight, is_urban) VALUES
    (11, 'Open Water', 0.0, FALSE),
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
    (95, 'Emergent Herbaceous Wetlands', 0.7, FALSE),
    (1, 'Canada LC Class 1', 1.0, FALSE),
    (2, 'Canada LC Class 2', 1.0, FALSE),
    (3, 'Canada LC Class 3', 1.0, FALSE),
    (4, 'Canada LC Class 4', 0.8, FALSE),
    (5, 'Canada LC Class 5', 0.6, FALSE),
    (6, 'Canada LC Class 6', 0.6, FALSE),
    (7, 'Canada LC Class 7', 0.7, FALSE),
    (8, 'Canada LC Class 8', 0.3, FALSE),
    (9, 'Canada LC Class 9', 0.0, FALSE),
    (10, 'Canada LC Class 10', 0.0, TRUE),
    (12, 'Canada LC Class 12', 0.0, FALSE),
    (13, 'Canada LC Class 13', 0.7, FALSE),
    (14, 'Canada LC Class 14', 0.6, FALSE),
    (15, 'Canada LC Class 15', 0.2, FALSE),
    (16, 'Canada LC Class 16', 0.0, FALSE),
    (17, 'Canada LC Class 17', 0.0, FALSE),
    (18, 'Canada LC Class 18', 0.0, FALSE),
    (19, 'Canada LC Class 19', 0.0, FALSE)
ON CONFLICT (class_id) DO UPDATE
SET class_label = EXCLUDED.class_label,
    green_weight = EXCLUDED.green_weight,
    is_urban = EXCLUDED.is_urban;

-- Optional caller override via: -v chunk_size=500
\if :{?chunk_size}
\else
\set chunk_size 500
\endif

\echo Building national tile batch list...
DROP TABLE IF EXISTS public.dq_all_h3_batches;
CREATE UNLOGGED TABLE public.dq_all_h3_batches AS
SELECT
    sst.h3_index,
    ROW_NUMBER() OVER (ORDER BY sst.h3_index) AS rn
FROM scenic_score_tiles sst;

CREATE UNIQUE INDEX IF NOT EXISTS dq_all_h3_batches_h3_idx
    ON public.dq_all_h3_batches (h3_index);
CREATE INDEX IF NOT EXISTS dq_all_h3_batches_rn_idx
    ON public.dq_all_h3_batches (rn);
ANALYZE public.dq_all_h3_batches;

SELECT COUNT(*) AS target_tiles, MIN(rn) AS min_rn, MAX(rn) AS max_rn
FROM public.dq_all_h3_batches;

\echo Running batched updates with chunk_size=:chunk_size ...
WITH bounds AS (
    SELECT
        gs::INTEGER AS start_rn,
        LEAST(
            (gs + :chunk_size::INTEGER - 1)::INTEGER,
            (SELECT COALESCE(MAX(rn), 0) FROM public.dq_all_h3_batches)
        ) AS end_rn
    FROM generate_series(
        1,
        (SELECT COALESCE(MAX(rn), 0) FROM public.dq_all_h3_batches),
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

    -- 1) Green + solitude for this batch.
    WITH batch_h3 AS (
        SELECT h3_index
        FROM public.dq_all_h3_batches
        WHERE rn BETWEEN v_start AND v_end
    ),
    landcover_pixel_counts AS (
        SELECT
            sst.h3_index,
            vc.value::INTEGER AS class_id,
            SUM(vc.count)::DOUBLE PRECISION AS pixel_count
        FROM scenic_score_tiles sst
        JOIN batch_h3 b
          ON b.h3_index = sst.h3_index
        JOIN landcover_raster lr
          ON ST_Intersects(
                lr.rast,
                CASE
                    WHEN ST_SRID(sst.geometry) = ST_SRID(lr.rast) THEN sst.geometry
                    ELSE ST_Transform(sst.geometry, ST_SRID(lr.rast))
                END
             )
        CROSS JOIN LATERAL ST_ValueCount(
            ST_Clip(
                lr.rast,
                CASE
                    WHEN ST_SRID(sst.geometry) = ST_SRID(lr.rast) THEN sst.geometry
                    ELSE ST_Transform(sst.geometry, ST_SRID(lr.rast))
                END,
                TRUE
            ),
            1,
            TRUE
        ) AS vc(value, count)
        GROUP BY sst.h3_index, vc.value
    ),
    landcover_aggregates AS (
        SELECT
            lpc.h3_index,
            SUM(lpc.pixel_count) AS total_pixels,
            SUM(lpc.pixel_count * COALESCE(lcw.green_weight, 0.0))
                / NULLIF(SUM(lpc.pixel_count), 0.0) AS green_score,
            SUM(
                lpc.pixel_count
                * CASE WHEN COALESCE(lcw.is_urban, FALSE) THEN 1.0 ELSE 0.0 END
            ) / NULLIF(SUM(lpc.pixel_count), 0.0) AS urban_proportion
        FROM landcover_pixel_counts lpc
        LEFT JOIN landcover_class_weights lcw
          ON lcw.class_id = lpc.class_id
        GROUP BY lpc.h3_index
    )
    UPDATE scenic_score_tiles sst
    SET green_score = LEAST(1.0, GREATEST(0.0, COALESCE(la.green_score, sst.green_score, 0.0))),
        solitude_score = LEAST(
            1.0,
            GREATEST(
                0.0,
                ((1.0 - COALESCE(la.urban_proportion, 0.0)) * 0.6) +
                ((1.0 - LEAST(1.0, GREATEST(0.0, COALESCE(sst.road_density, 0.0)))) * 0.4)
            )
        ),
        natural_land_use = LEAST(1.0, GREATEST(0.0, COALESCE(la.green_score, sst.natural_land_use, 0.0)))
    FROM landcover_aggregates la
    WHERE sst.h3_index = la.h3_index;

    -- 2) Elevation for this batch (only where DEM intersects).
    WITH batch_h3 AS (
        SELECT h3_index
        FROM public.dq_all_h3_batches
        WHERE rn BETWEEN v_start AND v_end
    ),
    elevation_stats AS (
        SELECT
            sst.h3_index,
            (ST_SummaryStatsAgg(
                ST_Clip(
                    er.rast,
                    CASE
                        WHEN ST_SRID(sst.geometry) = ST_SRID(er.rast) THEN sst.geometry
                        ELSE ST_Transform(sst.geometry, ST_SRID(er.rast))
                    END,
                    TRUE
                ),
                1,
                TRUE
            )).stddev AS elevation_stddev_m
        FROM scenic_score_tiles sst
        JOIN batch_h3 b
          ON b.h3_index = sst.h3_index
        JOIN elevation_raster er
          ON ST_Intersects(
                er.rast,
                CASE
                    WHEN ST_SRID(sst.geometry) = ST_SRID(er.rast) THEN sst.geometry
                    ELSE ST_Transform(sst.geometry, ST_SRID(er.rast))
                END
             )
        GROUP BY sst.h3_index
    )
    UPDATE scenic_score_tiles sst
    SET elevation_score = LEAST(1.0, GREATEST(0.0, COALESCE(es.elevation_stddev_m, 0.0) / 100.0)),
        elevation_variance = LEAST(1.0, GREATEST(0.0, COALESCE(es.elevation_stddev_m, 0.0) / 100.0))
    FROM elevation_stats es
    WHERE sst.h3_index = es.h3_index;

    -- 3) Composite score for this batch.
    WITH batch_h3 AS (
        SELECT h3_index
        FROM public.dq_all_h3_batches
        WHERE rn BETWEEN v_start AND v_end
    )
    UPDATE scenic_score_tiles sst
    SET scenic_score = LEAST(
            1.0,
            GREATEST(
                0.0,
                COALESCE(water_score, 0.0) * 0.25 +
                COALESCE(green_score, 0.0) * 0.20 +
                COALESCE(elevation_score, 0.0) * 0.20 +
                COALESCE(solitude_score, 0.0) * 0.10 +
                COALESCE(curve_score, 0.0) * 0.10 +
                COALESCE(poi_score, 0.0) * 0.15
            )
        ),
        last_scored = CURRENT_TIMESTAMP,
        scoring_version = '2.6-raster-data-quality-upgrade-national-batched'
    WHERE EXISTS (
        SELECT 1
        FROM batch_h3 b
        WHERE b.h3_index = sst.h3_index
    );

    RAISE NOTICE 'Batch %%..%% complete', v_start, v_end;
END
$batch$;
$fmt$, start_rn, end_rn)
FROM bounds
\gexec

ANALYZE scenic_score_tiles;

\echo Final stats (global)
SELECT
    COUNT(*) AS all_tiles,
    COUNT(*) FILTER (WHERE green_score > 0.0) AS green_non_zero_tiles,
    COUNT(*) FILTER (WHERE solitude_score > 0.0) AS solitude_non_zero_tiles,
    COUNT(*) FILTER (WHERE elevation_score > 0.0) AS elevation_non_zero_tiles,
    ROUND(AVG(scenic_score)::numeric, 6) AS avg_scenic_score,
    ROUND(STDDEV_POP(scenic_score)::numeric, 6) AS stddev_scenic_score
FROM scenic_score_tiles;
