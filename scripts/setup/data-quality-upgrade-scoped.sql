-- Scoped (faster) data-quality upgrade over the area covered by elevation_raster.
-- Intended for validation slices (for example: Maritimes DEM subset).

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

CREATE TEMP TABLE target_h3 AS
SELECT DISTINCT sst.h3_index
FROM scenic_score_tiles sst
JOIN elevation_raster er
  ON ST_Intersects(er.rast, sst.geometry);

ANALYZE target_h3;

-- 1) Green + solitude from land cover and road density (target subset only).
WITH landcover_pixel_counts AS (
    SELECT
        sst.h3_index,
        vc.value::INTEGER AS class_id,
        SUM(vc.count)::DOUBLE PRECISION AS pixel_count
    FROM scenic_score_tiles sst
    JOIN target_h3 t
      ON t.h3_index = sst.h3_index
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

-- 2) Elevation from DEM stddev (target subset only).
WITH elevation_stats AS (
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
    JOIN target_h3 t
      ON t.h3_index = sst.h3_index
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
SET elevation_score = LEAST(
        1.0,
        GREATEST(0.0, COALESCE(es.elevation_stddev_m, 0.0) / 100.0)
    ),
    elevation_variance = LEAST(
        1.0,
        GREATEST(0.0, COALESCE(es.elevation_stddev_m, 0.0) / 100.0)
    )
FROM elevation_stats es
WHERE sst.h3_index = es.h3_index;

-- 3) Composite recompute for target subset only.
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
    scoring_version = '2.3-raster-data-quality-upgrade-scoped'
WHERE EXISTS (
    SELECT 1
    FROM target_h3 t
    WHERE t.h3_index = sst.h3_index
);

ANALYZE scenic_score_tiles;

SELECT COUNT(*) AS target_tiles FROM target_h3;

SELECT
    COUNT(*) AS target_tiles,
    COUNT(*) FILTER (WHERE green_score > 0.0) AS green_non_zero_tiles,
    COUNT(*) FILTER (WHERE solitude_score > 0.0) AS solitude_non_zero_tiles,
    COUNT(*) FILTER (WHERE elevation_score > 0.0) AS elevation_non_zero_tiles,
    ROUND(AVG(green_score)::numeric, 6) AS avg_green_score,
    ROUND(STDDEV_POP(green_score)::numeric, 6) AS stddev_green_score,
    ROUND(AVG(elevation_score)::numeric, 6) AS avg_elevation_score,
    ROUND(STDDEV_POP(elevation_score)::numeric, 6) AS stddev_elevation_score,
    ROUND(AVG(scenic_score)::numeric, 6) AS avg_scenic_score,
    ROUND(STDDEV_POP(scenic_score)::numeric, 6) AS stddev_scenic_score
FROM scenic_score_tiles sst
WHERE EXISTS (
    SELECT 1
    FROM target_h3 t
    WHERE t.h3_index = sst.h3_index
);

SELECT
    COUNT(*) AS all_tiles,
    COUNT(*) FILTER (WHERE green_score > 0.0) AS green_non_zero_tiles,
    COUNT(*) FILTER (WHERE solitude_score > 0.0) AS solitude_non_zero_tiles,
    COUNT(*) FILTER (WHERE elevation_score > 0.0) AS elevation_non_zero_tiles,
    ROUND(AVG(scenic_score)::numeric, 6) AS avg_scenic_score,
    ROUND(STDDEV_POP(scenic_score)::numeric, 6) AS stddev_scenic_score
FROM scenic_score_tiles;
