-- Overture + light-pollution scenic enrichment v3.0.
--
-- Usage:
--   psql -d moodride -v ON_ERROR_STOP=1 -v chunk_size=1000 -f scripts/setup/data-quality-enrichment-v30.sql
--
-- Optional:
--   -v light_pollution_reference_max=100
--
-- Prereqs:
-- - scenic_score_tiles already has v2.9 component columns, including park_score.
-- - public.overture_place_tile_scores loaded with per-H3 Overture POI scores.
-- - public.overture_building_density_tiles loaded with per-H3 building density scores.
-- - public.light_pollution_raster loaded as PostGIS raster, optional.
--   If missing, darkness_score is set to the neutral default 0.5.

DO $$
BEGIN
    IF to_regclass('public.scenic_score_tiles') IS NULL THEN
        RAISE EXCEPTION 'Required table missing: public.scenic_score_tiles';
    END IF;
    IF to_regclass('public.overture_place_tile_scores') IS NULL THEN
        RAISE EXCEPTION 'Required table missing: public.overture_place_tile_scores';
    END IF;
    IF to_regclass('public.overture_building_density_tiles') IS NULL THEN
        RAISE EXCEPTION 'Required table missing: public.overture_building_density_tiles';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'scenic_score_tiles'
          AND column_name = 'park_score'
    ) THEN
        RAISE EXCEPTION 'scenic_score_tiles.park_score is missing. Run 2.9 protected-area enrichment first.';
    END IF;
END $$;

ALTER TABLE public.scenic_score_tiles
    ADD COLUMN IF NOT EXISTS overture_poi_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS building_density_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS darkness_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS urban_penalty_score DOUBLE PRECISION NOT NULL DEFAULT 0.0;

CREATE UNIQUE INDEX IF NOT EXISTS overture_place_tile_scores_h3_idx
    ON public.overture_place_tile_scores (h3_index);

CREATE UNIQUE INDEX IF NOT EXISTS overture_building_density_tiles_h3_idx
    ON public.overture_building_density_tiles (h3_index);

DO $$
BEGIN
    IF to_regclass('public.light_pollution_raster') IS NOT NULL THEN
        CREATE INDEX IF NOT EXISTS light_pollution_raster_gist_idx
            ON public.light_pollution_raster
            USING GIST (ST_ConvexHull(rast));
        ANALYZE public.light_pollution_raster;
    ELSE
        RAISE NOTICE 'public.light_pollution_raster missing; using neutral darkness_score=0.5 for v3.0.';
        CREATE TABLE public.light_pollution_raster (
            rid serial PRIMARY KEY,
            rast raster
        );
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS scenic_score_tiles_geom_idx
    ON public.scenic_score_tiles
    USING GIST (geometry);

ANALYZE public.overture_place_tile_scores;
ANALYZE public.overture_building_density_tiles;
ANALYZE public.scenic_score_tiles;

\if :{?chunk_size}
\else
\set chunk_size 1000
\endif

\if :{?light_pollution_reference_max}
\else
\set light_pollution_reference_max 100
\endif

\echo Building 3.0 enrichment batches...
DROP TABLE IF EXISTS public.dq_v30_batches;
CREATE UNLOGGED TABLE public.dq_v30_batches AS
SELECT
    sst.h3_index,
    ROW_NUMBER() OVER (ORDER BY sst.h3_index) AS rn
FROM public.scenic_score_tiles sst
WHERE COALESCE(sst.scoring_version, '') <> '3.0-overture-lightpollution-enrichment';

CREATE UNIQUE INDEX IF NOT EXISTS dq_v30_batches_h3_idx
    ON public.dq_v30_batches (h3_index);
CREATE INDEX IF NOT EXISTS dq_v30_batches_rn_idx
    ON public.dq_v30_batches (rn);
ANALYZE public.dq_v30_batches;

SELECT COUNT(*) AS target_tiles, MIN(rn) AS min_rn, MAX(rn) AS max_rn
FROM public.dq_v30_batches;

\echo Running 3.0 enrichment with chunk_size=:chunk_size ...
WITH bounds AS (
    SELECT
        gs::INTEGER AS start_rn,
        LEAST(
            (gs + :chunk_size::INTEGER - 1)::INTEGER,
            (SELECT COALESCE(MAX(rn), 0) FROM public.dq_v30_batches)
        ) AS end_rn
    FROM generate_series(
        1,
        (SELECT COALESCE(MAX(rn), 0) FROM public.dq_v30_batches),
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
            sst.geometry,
            ST_Transform(sst.geometry, 3979) AS geom_3979,
            NULLIF(ST_Area(ST_Transform(sst.geometry, 3979)), 0.0) AS tile_area_m2,
            ST_PointOnSurface(sst.geometry) AS sample_point
        FROM public.scenic_score_tiles sst
        JOIN public.dq_v30_batches b
          ON b.h3_index = sst.h3_index
        WHERE b.rn BETWEEN v_start AND v_end
    ),
    building_scores AS (
        SELECT
            bt.h3_index,
            COALESCE(obdt.building_density_score, 0.0) AS building_density_score
        FROM batch_tiles bt
        LEFT JOIN public.overture_building_density_tiles obdt
          ON obdt.h3_index = bt.h3_index
    ),
    place_scores AS (
        SELECT
            bt.h3_index,
            COALESCE(opts.overture_poi_score, 0.0) AS overture_poi_score
        FROM batch_tiles bt
        LEFT JOIN public.overture_place_tile_scores opts
          ON opts.h3_index = bt.h3_index
    ),
    darkness_scores AS (
        SELECT
            bt.h3_index,
            CASE
                WHEN EXISTS (SELECT 1 FROM public.light_pollution_raster LIMIT 1) THEN
                    COALESCE(
                        MAX(
                            GREATEST(
                                0.0,
                                LEAST(
                                    1.0,
                                    1.0 - (
                                        NULLIF(
                                            ST_Value(
                                                lp.rast,
                                                ST_Transform(bt.sample_point, ST_SRID(lp.rast))
                                            ),
                                            'NaN'::DOUBLE PRECISION
                                        ) / %s::DOUBLE PRECISION
                                    )
                                )
                            )
                        ),
                        0.5
                    )
                ELSE 0.5
            END AS darkness_score
        FROM batch_tiles bt
        LEFT JOIN public.light_pollution_raster lp
          ON ST_Intersects(lp.rast, ST_Transform(bt.sample_point, ST_SRID(lp.rast)))
        GROUP BY bt.h3_index
    ),
    combined AS (
        SELECT
            bt.h3_index,
            COALESCE(ps.overture_poi_score, 0.0) AS overture_poi_score,
            COALESCE(bs.building_density_score, 0.0) AS building_density_score,
            COALESCE(ds.darkness_score, 0.5) AS darkness_score,
            LEAST(1.0, COALESCE(bs.building_density_score, 0.0)) AS urban_penalty_score
        FROM batch_tiles bt
        LEFT JOIN place_scores ps ON ps.h3_index = bt.h3_index
        LEFT JOIN building_scores bs ON bs.h3_index = bt.h3_index
        LEFT JOIN darkness_scores ds ON ds.h3_index = bt.h3_index
    )
    UPDATE public.scenic_score_tiles sst
    SET overture_poi_score = c.overture_poi_score,
        building_density_score = c.building_density_score,
        darkness_score = c.darkness_score,
        urban_penalty_score = c.urban_penalty_score,
        poi_score = GREATEST(COALESCE(sst.poi_score, 0.0), c.overture_poi_score),
        solitude_score = LEAST(
            1.0,
            GREATEST(
                0.0,
                COALESCE(sst.solitude_score, 0.0) * 0.60 +
                (1.0 - c.building_density_score) * 0.25 +
                c.darkness_score * 0.15
            )
        ),
        scenic_score = LEAST(
            1.0,
            GREATEST(
                0.0,
                (
                    COALESCE(sst.water_score, 0.0) * 0.22 +
                    COALESCE(sst.green_score, 0.0) * 0.20 +
                    COALESCE(sst.elevation_score, 0.0) * 0.14 +
                    (
                        COALESCE(sst.solitude_score, 0.0) * 0.60 +
                        (1.0 - c.building_density_score) * 0.25 +
                        c.darkness_score * 0.15
                    ) * 0.14 +
                    COALESCE(sst.curve_score, 0.0) * 0.10 +
                    GREATEST(COALESCE(sst.poi_score, 0.0), c.overture_poi_score) * 0.12 +
                    COALESCE(sst.park_score, 0.0) * 0.08 -
                    c.urban_penalty_score * 0.10
                )
            )
        ),
        last_scored = CURRENT_TIMESTAMP,
        scoring_version = '3.0-overture-lightpollution-enrichment'
    FROM combined c
    WHERE sst.h3_index = c.h3_index;

    RAISE NOTICE 'Batch %%..%% complete', v_start, v_end;
END
$batch$;
$fmt$, start_rn, end_rn, :light_pollution_reference_max)
FROM bounds
\gexec

ANALYZE public.scenic_score_tiles;

\echo Final stats (3.0 target + global)
SELECT
    COUNT(*) AS tiles,
    COUNT(*) FILTER (WHERE overture_poi_score > 0.0) AS overture_poi_non_zero_tiles,
    COUNT(*) FILTER (WHERE building_density_score > 0.0) AS building_density_non_zero_tiles,
    COUNT(*) FILTER (WHERE darkness_score > 0.0) AS darkness_non_zero_tiles,
    ROUND(AVG(overture_poi_score)::numeric, 6) AS avg_overture_poi_score,
    ROUND(AVG(building_density_score)::numeric, 6) AS avg_building_density_score,
    ROUND(AVG(darkness_score)::numeric, 6) AS avg_darkness_score,
    ROUND(AVG(scenic_score)::numeric, 6) AS avg_scenic_score,
    ROUND(STDDEV_POP(scenic_score)::numeric, 6) AS stddev_scenic_score
FROM public.scenic_score_tiles
WHERE scoring_version = '3.0-overture-lightpollution-enrichment';

SELECT
    COUNT(*) AS all_tiles,
    ROUND(AVG(scenic_score)::numeric, 6) AS avg_scenic_score,
    ROUND(STDDEV_POP(scenic_score)::numeric, 6) AS stddev_scenic_score
FROM public.scenic_score_tiles;
