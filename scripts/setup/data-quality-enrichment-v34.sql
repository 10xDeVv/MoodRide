-- Tree-canopy scenic data quality v3.4.
--
-- Usage:
--   psql -d moodride -v ON_ERROR_STOP=1 -v chunk_size=50000 -f scripts/setup/data-quality-enrichment-v34.sql
--
-- Optional:
--   -v source_scoring_version=3.3-water-visibility-calibration
--   -v scoring_version=3.4-tree-canopy-calibration
--
-- Prereqs:
-- - scenic_score_tiles already has v3.3 water-visibility columns.
-- - landcover_raster exists, or nlcd_land_cover_cells exists as a fallback.
-- - This is a fast tree-canopy proxy from land-cover classes, not a true LiDAR canopy dataset.

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
\set scoring_version '3.4-tree-canopy-calibration'
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
    IF to_regclass('public.landcover_raster') IS NULL
       AND to_regclass('public.nlcd_land_cover_cells') IS NULL THEN
        RAISE EXCEPTION 'Required land-cover source missing: public.landcover_raster or public.nlcd_land_cover_cells';
    END IF;
END $$;

ALTER TABLE public.scenic_score_tiles
    ADD COLUMN IF NOT EXISTS tree_canopy_score DOUBLE PRECISION NOT NULL DEFAULT 0.0;

ALTER TABLE public.scenic_score_tiles DROP CONSTRAINT IF EXISTS tree_canopy_score_range;
ALTER TABLE public.scenic_score_tiles
    ADD CONSTRAINT tree_canopy_score_range CHECK (tree_canopy_score >= 0.0 AND tree_canopy_score <= 1.0);

CREATE TABLE IF NOT EXISTS public.landcover_canopy_class_weights (
    class_id INTEGER PRIMARY KEY,
    class_label TEXT NOT NULL,
    canopy_weight DOUBLE PRECISION NOT NULL CHECK (canopy_weight >= 0.0 AND canopy_weight <= 1.0)
);

INSERT INTO public.landcover_canopy_class_weights (class_id, class_label, canopy_weight) VALUES
    (11, 'Open Water', 0.00),
    (21, 'Developed, Open Space', 0.03),
    (22, 'Developed, Low Intensity', 0.08),
    (23, 'Developed, Medium Intensity', 0.03),
    (24, 'Developed, High Intensity', 0.00),
    (31, 'Barren Land', 0.00),
    (41, 'Deciduous Forest', 1.00),
    (42, 'Evergreen Forest', 1.00),
    (43, 'Mixed Forest', 1.00),
    (52, 'Shrub/Scrub', 0.30),
    (71, 'Grassland/Herbaceous', 0.08),
    (81, 'Pasture/Hay', 0.05),
    (82, 'Cultivated Crops', 0.02),
    (90, 'Woody Wetlands', 0.80),
    (95, 'Emergent Herbaceous Wetlands', 0.10),
    (1, 'Canada LC Class 1', 0.95),
    (2, 'Canada LC Class 2', 0.95),
    (3, 'Canada LC Class 3', 0.95),
    (4, 'Canada LC Class 4', 0.80),
    (5, 'Canada LC Class 5', 0.40),
    (6, 'Canada LC Class 6', 0.40),
    (7, 'Canada LC Class 7', 0.65),
    (8, 'Canada LC Class 8', 0.08),
    (9, 'Canada LC Class 9', 0.00),
    (10, 'Canada LC Class 10', 0.00),
    (12, 'Canada LC Class 12', 0.00),
    (13, 'Canada LC Class 13', 0.65),
    (14, 'Canada LC Class 14', 0.45),
    (15, 'Canada LC Class 15', 0.15),
    (16, 'Canada LC Class 16', 0.00),
    (17, 'Canada LC Class 17', 0.00),
    (18, 'Canada LC Class 18', 0.00),
    (19, 'Canada LC Class 19', 0.00)
ON CONFLICT (class_id) DO UPDATE
SET class_label = EXCLUDED.class_label,
    canopy_weight = EXCLUDED.canopy_weight;

ANALYZE public.scenic_score_tiles;

CREATE TEMP TABLE dq_v34_settings AS
SELECT
    :'scoring_version'::TEXT AS scoring_version,
    :'source_scoring_version'::TEXT AS source_scoring_version;

\echo Building v3.4 tree-canopy tile aggregates...
DROP TABLE IF EXISTS public.dq_v34_tree_canopy_tiles;
CREATE UNLOGGED TABLE public.dq_v34_tree_canopy_tiles (
    h3_index VARCHAR(15) PRIMARY KEY,
    tree_canopy_score DOUBLE PRECISION NOT NULL
);

DO $$
BEGIN
    IF to_regclass('public.landcover_raster') IS NOT NULL THEN
        EXECUTE $sql$
            INSERT INTO public.dq_v34_tree_canopy_tiles (h3_index, tree_canopy_score)
            WITH target_points AS MATERIALIZED (
                SELECT
                    h3_index,
                    ST_PointOnSurface(ST_Transform(geometry, 3979)) AS sample_geom
                FROM public.scenic_score_tiles
                WHERE COALESCE(scoring_version, '') <> (SELECT scoring_version FROM dq_v34_settings)
                  AND (
                      (SELECT source_scoring_version FROM dq_v34_settings) = ''
                      OR COALESCE(scoring_version, '') = (SELECT source_scoring_version FROM dq_v34_settings)
                  )
            ),
            sampled_classes AS (
                SELECT
                    tp.h3_index,
                    sample.class_id
                FROM target_points tp
                LEFT JOIN LATERAL (
                    SELECT ST_Value(lr.rast, 1, tp.sample_geom, TRUE)::INTEGER AS class_id
                    FROM public.landcover_raster lr
                    WHERE ST_ConvexHull(lr.rast) && tp.sample_geom
                      AND ST_Intersects(ST_ConvexHull(lr.rast), tp.sample_geom)
                      AND ST_Value(lr.rast, 1, tp.sample_geom, TRUE) IS NOT NULL
                    LIMIT 1
                ) sample ON TRUE
            )
            SELECT
                sc.h3_index,
                LEAST(
                    1.0,
                    GREATEST(
                        0.0,
                        COALESCE(lcw.canopy_weight, 0.0)
                    )
                ) AS tree_canopy_score
            FROM sampled_classes sc
            LEFT JOIN public.landcover_canopy_class_weights lcw
              ON lcw.class_id = sc.class_id
        $sql$;
    ELSE
        EXECUTE $sql$
            INSERT INTO public.dq_v34_tree_canopy_tiles (h3_index, tree_canopy_score)
            WITH target_tiles AS MATERIALIZED (
                SELECT h3_index, geometry
                FROM public.scenic_score_tiles
                WHERE COALESCE(scoring_version, '') <> (SELECT scoring_version FROM dq_v34_settings)
                  AND (
                      (SELECT source_scoring_version FROM dq_v34_settings) = ''
                      OR COALESCE(scoring_version, '') = (SELECT source_scoring_version FROM dq_v34_settings)
                  )
            ),
            overlaps AS (
                SELECT
                    tt.h3_index,
                    n.nlcd_class::INTEGER AS class_id,
                    ST_Area(ST_Intersection(tt.geometry, n.geometry)::geography) AS area_m2
                FROM target_tiles tt
                JOIN public.nlcd_land_cover_cells n
                  ON n.geometry && tt.geometry
                 AND ST_Intersects(n.geometry, tt.geometry)
            )
            SELECT
                o.h3_index,
                LEAST(
                    1.0,
                    GREATEST(
                        0.0,
                        SUM(o.area_m2 * COALESCE(lcw.canopy_weight, 0.0))
                            / NULLIF(SUM(o.area_m2), 0.0)
                    )
                ) AS tree_canopy_score
            FROM overlaps o
            LEFT JOIN public.landcover_canopy_class_weights lcw
              ON lcw.class_id = o.class_id
            GROUP BY o.h3_index
        $sql$;
    END IF;
END $$;

ANALYZE public.dq_v34_tree_canopy_tiles;

\echo Building v3.4 enrichment batches...
DROP TABLE IF EXISTS public.dq_v34_batches;
CREATE UNLOGGED TABLE public.dq_v34_batches AS
SELECT
    sst.h3_index,
    ROW_NUMBER() OVER (ORDER BY sst.h3_index) AS rn
FROM public.scenic_score_tiles sst
WHERE COALESCE(sst.scoring_version, '') <> :'scoring_version'
  AND (
      :'source_scoring_version' = ''
      OR COALESCE(sst.scoring_version, '') = :'source_scoring_version'
  );

CREATE UNIQUE INDEX IF NOT EXISTS dq_v34_batches_h3_idx
    ON public.dq_v34_batches (h3_index);
CREATE INDEX IF NOT EXISTS dq_v34_batches_rn_idx
    ON public.dq_v34_batches (rn);
ANALYZE public.dq_v34_batches;

SELECT COUNT(*) AS target_tiles, MIN(rn) AS min_rn, MAX(rn) AS max_rn
FROM public.dq_v34_batches;

\echo Running v3.4 tree-canopy enrichment with chunk_size=:chunk_size source_scoring_version=:source_scoring_version scoring_version=:scoring_version ...
WITH bounds AS (
    SELECT
        gs::INTEGER AS start_rn,
        LEAST(
            (gs + :chunk_size::INTEGER - 1)::INTEGER,
            (SELECT COALESCE(MAX(rn), 0) FROM public.dq_v34_batches)
        ) AS end_rn
    FROM generate_series(
        1,
        (SELECT COALESCE(MAX(rn), 0) FROM public.dq_v34_batches),
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
            LEAST(1.0, GREATEST(0.0, COALESCE(sst.green_score, 0.0))) AS prior_green_score,
            LEAST(1.0, GREATEST(0.0, COALESCE(sst.elevation_score, 0.0))) AS elevation_score,
            LEAST(1.0, GREATEST(0.0, COALESCE(sst.solitude_score, 0.0))) AS solitude_score,
            LEAST(1.0, GREATEST(0.0, COALESCE(sst.curve_score, 0.0))) AS curve_score,
            LEAST(1.0, GREATEST(0.0, GREATEST(COALESCE(sst.poi_score, 0.0), COALESCE(sst.overture_poi_score, 0.0)))) AS poi_score,
            LEAST(1.0, GREATEST(0.0, COALESCE(sst.park_score, 0.0))) AS park_score,
            LEAST(1.0, GREATEST(0.0, COALESCE(sst.urban_penalty_score, 0.0))) AS urban_penalty_score,
            LEAST(1.0, GREATEST(0.0, COALESCE(sst.road_stress_score, 0.0))) AS road_stress_score,
            LEAST(1.0, GREATEST(0.0, COALESCE(tc.tree_canopy_score, sst.tree_canopy_score, 0.0))) AS tree_canopy_score
        FROM public.scenic_score_tiles sst
        JOIN public.dq_v34_batches b
          ON b.h3_index = sst.h3_index
        LEFT JOIN public.dq_v34_tree_canopy_tiles tc
          ON tc.h3_index = sst.h3_index
        WHERE b.rn BETWEEN v_start AND v_end
    ),
    rescored AS (
        SELECT
            bt.*,
            GREATEST(
                bt.prior_green_score,
                LEAST(
                    1.0,
                    GREATEST(
                        0.0,
                        (bt.prior_green_score * 0.65) +
                        (bt.tree_canopy_score * 0.35)
                    )
                )
            ) AS calibrated_green_score
        FROM batch_tiles bt
    )
    UPDATE public.scenic_score_tiles sst
    SET tree_canopy_score = r.tree_canopy_score,
        green_score = r.calibrated_green_score,
        natural_land_use = GREATEST(COALESCE(sst.natural_land_use, 0.0), r.calibrated_green_score),
        scenic_score = LEAST(
            1.0,
            GREATEST(
                0.0,
                (
                    r.water_score * 0.22 +
                    r.calibrated_green_score * 0.20 +
                    r.elevation_score * 0.14 +
                    r.solitude_score * 0.14 +
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

\echo Final stats (v3.4 target)
SELECT
    COUNT(*) AS tiles,
    COUNT(*) FILTER (WHERE tree_canopy_score > 0.0) AS tree_canopy_non_zero_tiles,
    ROUND(MIN(tree_canopy_score)::numeric, 6) AS min_tree_canopy_score,
    ROUND(AVG(tree_canopy_score)::numeric, 6) AS avg_tree_canopy_score,
    ROUND(MAX(tree_canopy_score)::numeric, 6) AS max_tree_canopy_score,
    ROUND(STDDEV_POP(tree_canopy_score)::numeric, 6) AS stddev_tree_canopy_score,
    ROUND(AVG(green_score)::numeric, 6) AS avg_green_score,
    ROUND(AVG(scenic_score)::numeric, 6) AS avg_scenic_score,
    ROUND(STDDEV_POP(scenic_score)::numeric, 6) AS stddev_scenic_score
FROM public.scenic_score_tiles
WHERE scoring_version = :'scoring_version';

SELECT scoring_version, COUNT(*)
FROM public.scenic_score_tiles
GROUP BY scoring_version
ORDER BY scoring_version;
