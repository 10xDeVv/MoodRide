-- Darkness + urban-penalty calibration v3.1.
--
-- Usage:
--   psql -d moodride -v ON_ERROR_STOP=1 -v chunk_size=1000 -f scripts/setup/data-quality-enrichment-v31.sql
--
-- Optional:
--   -v source_scoring_version=3.0-overture-lightpollution-enrichment
--   -v scoring_version=3.1-darkness-urban-penalty-calibration
--   -v light_pollution_reference_max=100
--   -v allow_neutral_darkness=true
--
-- Prereqs:
-- - scenic_score_tiles already has v3.0 enrichment columns.
-- - public.overture_place_tile_scores loaded with per-H3 Overture POI scores.
-- - public.overture_building_density_tiles loaded with per-H3 building density scores.
-- - public.light_pollution_tile_samples loaded with per-H3 darkness scores,
--   or public.light_pollution_raster loaded as PostGIS raster.
--
-- By default this script refuses to run without raster rows. Set
-- allow_neutral_darkness=true only for dry-run/dev cases where a neutral
-- darkness_score=0.5 is explicitly acceptable.

\if :{?chunk_size}
\else
\set chunk_size 1000
\endif

\if :{?scoring_version}
\else
\set scoring_version '3.1-darkness-urban-penalty-calibration'
\endif

\if :{?source_scoring_version}
\else
\set source_scoring_version ''
\endif

\if :{?light_pollution_reference_max}
\else
\set light_pollution_reference_max 100
\endif

\if :{?allow_neutral_darkness}
\else
\set allow_neutral_darkness false
\endif

CREATE TEMP TABLE dq_v31_config AS
SELECT
    lower(:'allow_neutral_darkness') IN ('1', 'true', 't', 'yes', 'y') AS allow_neutral_darkness;

DO $$
DECLARE
    raster_rows BIGINT := 0;
    sample_rows BIGINT := 0;
    allow_neutral BOOLEAN := false;
BEGIN
    SELECT cfg.allow_neutral_darkness
    INTO allow_neutral
    FROM pg_temp.dq_v31_config cfg;

    IF to_regclass('public.scenic_score_tiles') IS NULL THEN
        RAISE EXCEPTION 'Required table missing: public.scenic_score_tiles';
    END IF;
    IF to_regclass('public.overture_place_tile_scores') IS NULL THEN
        RAISE EXCEPTION 'Required table missing: public.overture_place_tile_scores';
    END IF;
    IF to_regclass('public.overture_building_density_tiles') IS NULL THEN
        RAISE EXCEPTION 'Required table missing: public.overture_building_density_tiles';
    END IF;
    IF to_regclass('public.light_pollution_raster') IS NULL THEN
        IF allow_neutral THEN
            RAISE NOTICE 'public.light_pollution_raster missing; allow_neutral_darkness=true, using darkness_score=0.5.';
            CREATE TABLE public.light_pollution_raster (
                rid serial PRIMARY KEY,
                rast raster
            );
        ELSE
            RAISE EXCEPTION 'Required table missing: public.light_pollution_raster. Import a real raster first or set allow_neutral_darkness=true for a dry run.';
        END IF;
    END IF;

    IF to_regclass('public.light_pollution_tile_samples') IS NULL THEN
        CREATE TABLE public.light_pollution_tile_samples (
            h3_index TEXT PRIMARY KEY,
            raw_value DOUBLE PRECISION,
            darkness_score DOUBLE PRECISION NOT NULL
        );
    END IF;

    EXECUTE 'SELECT COUNT(*) FROM public.light_pollution_raster' INTO raster_rows;
    EXECUTE 'SELECT COUNT(*) FROM public.light_pollution_tile_samples' INTO sample_rows;
    IF raster_rows = 0 AND sample_rows = 0 AND NOT allow_neutral THEN
        RAISE EXCEPTION 'No light-pollution data available. Import public.light_pollution_tile_samples or public.light_pollution_raster before running 3.1.';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'scenic_score_tiles'
          AND column_name = 'darkness_score'
    ) THEN
        RAISE EXCEPTION 'scenic_score_tiles.darkness_score is missing. Run V22 / 3.0 schema support first.';
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

CREATE INDEX IF NOT EXISTS light_pollution_raster_gist_idx
    ON public.light_pollution_raster
    USING GIST (ST_ConvexHull(rast));

CREATE UNIQUE INDEX IF NOT EXISTS light_pollution_tile_samples_h3_idx
    ON public.light_pollution_tile_samples (h3_index);

CREATE INDEX IF NOT EXISTS scenic_score_tiles_geom_idx
    ON public.scenic_score_tiles
    USING GIST (geometry);

ANALYZE public.overture_place_tile_scores;
ANALYZE public.overture_building_density_tiles;
ANALYZE public.light_pollution_raster;
ANALYZE public.light_pollution_tile_samples;
ANALYZE public.scenic_score_tiles;

\echo Building 3.1 enrichment batches...
DROP TABLE IF EXISTS public.dq_v31_batches;
CREATE UNLOGGED TABLE public.dq_v31_batches AS
SELECT
    sst.h3_index,
    ROW_NUMBER() OVER (ORDER BY sst.h3_index) AS rn
FROM public.scenic_score_tiles sst
WHERE COALESCE(sst.scoring_version, '') <> :'scoring_version'
  AND (
      :'source_scoring_version' = ''
      OR COALESCE(sst.scoring_version, '') = :'source_scoring_version'
  );

CREATE UNIQUE INDEX IF NOT EXISTS dq_v31_batches_h3_idx
    ON public.dq_v31_batches (h3_index);
CREATE INDEX IF NOT EXISTS dq_v31_batches_rn_idx
    ON public.dq_v31_batches (rn);
ANALYZE public.dq_v31_batches;

SELECT COUNT(*) AS target_tiles, MIN(rn) AS min_rn, MAX(rn) AS max_rn
FROM public.dq_v31_batches;

\echo Running 3.1 enrichment with chunk_size=:chunk_size source_scoring_version=:source_scoring_version scoring_version=:scoring_version ...
WITH bounds AS (
    SELECT
        gs::INTEGER AS start_rn,
        LEAST(
            (gs + :chunk_size::INTEGER - 1)::INTEGER,
            (SELECT COALESCE(MAX(rn), 0) FROM public.dq_v31_batches)
        ) AS end_rn
    FROM generate_series(
        1,
        (SELECT COALESCE(MAX(rn), 0) FROM public.dq_v31_batches),
        :chunk_size::INTEGER
    ) AS gs
)
SELECT format($fmt$
DO $batch$
DECLARE
    v_start INTEGER := %s;
    v_end INTEGER := %s;
    v_allow_neutral BOOLEAN := lower(%L) IN ('1', 'true', 't', 'yes', 'y');
BEGIN
    RAISE NOTICE 'Batch %%..%% started', v_start, v_end;

    WITH batch_tiles AS MATERIALIZED (
        SELECT
            sst.h3_index,
            sst.geometry,
            ST_PointOnSurface(sst.geometry) AS sample_point,
            LEAST(1.0, GREATEST(0.0, COALESCE(sst.road_density, 0.0))) AS road_density,
            LEAST(1.0, GREATEST(0.0, COALESCE(sst.green_score, sst.natural_land_use, 0.0))) AS green_score,
            LEAST(1.0, GREATEST(0.0, COALESCE(sst.solitude_score, 0.0))) AS prior_solitude_score
        FROM public.scenic_score_tiles sst
        JOIN public.dq_v31_batches b
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
                WHEN MAX(lpts.darkness_score) IS NOT NULL THEN
                    LEAST(1.0, GREATEST(0.0, MAX(lpts.darkness_score)))
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
                WHEN v_allow_neutral THEN 0.5
                ELSE NULL
            END AS darkness_score
        FROM batch_tiles bt
        LEFT JOIN public.light_pollution_tile_samples lpts
          ON lpts.h3_index = bt.h3_index
        LEFT JOIN public.light_pollution_raster lp
          ON lpts.h3_index IS NULL
         AND ST_Intersects(lp.rast, ST_Transform(bt.sample_point, ST_SRID(lp.rast)))
        GROUP BY bt.h3_index
    ),
    combined AS (
        SELECT
            bt.h3_index,
            COALESCE(ps.overture_poi_score, 0.0) AS overture_poi_score,
            LEAST(1.0, GREATEST(0.0, COALESCE(bs.building_density_score, 0.0))) AS building_density_score,
            LEAST(1.0, GREATEST(0.0, COALESCE(ds.darkness_score, 0.5))) AS darkness_score,
            LEAST(
                1.0,
                GREATEST(
                    0.0,
                    COALESCE(bs.building_density_score, 0.0) * 0.45 +
                    bt.road_density * 0.20 +
                    (1.0 - COALESCE(ds.darkness_score, 0.5)) * 0.20 +
                    (1.0 - bt.green_score) * 0.15
                )
            ) AS urban_penalty_score,
            LEAST(
                1.0,
                GREATEST(
                    0.0,
                    bt.prior_solitude_score * 0.45 +
                    (1.0 - COALESCE(bs.building_density_score, 0.0)) * 0.20 +
                    COALESCE(ds.darkness_score, 0.5) * 0.20 +
                    (1.0 - bt.road_density) * 0.15
                )
            ) AS calibrated_solitude_score
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
        solitude_score = c.calibrated_solitude_score,
        scenic_score = LEAST(
            1.0,
            GREATEST(
                0.0,
                (
                    COALESCE(sst.water_score, 0.0) * 0.22 +
                    COALESCE(sst.green_score, 0.0) * 0.20 +
                    COALESCE(sst.elevation_score, 0.0) * 0.14 +
                    c.calibrated_solitude_score * 0.14 +
                    COALESCE(sst.curve_score, 0.0) * 0.10 +
                    GREATEST(COALESCE(sst.poi_score, 0.0), c.overture_poi_score) * 0.12 +
                    COALESCE(sst.park_score, 0.0) * 0.08 -
                    c.urban_penalty_score * 0.10
                )
            )
        ),
        last_scored = CURRENT_TIMESTAMP,
        scoring_version = %L
    FROM combined c
    WHERE sst.h3_index = c.h3_index;

    RAISE NOTICE 'Batch %%..%% complete', v_start, v_end;
END
$batch$;
$fmt$, start_rn, end_rn, :'allow_neutral_darkness', :light_pollution_reference_max, :'scoring_version')
FROM bounds
\gexec

ANALYZE public.scenic_score_tiles;

\echo Final stats (3.1 target)
SELECT
    COUNT(*) AS tiles,
    COUNT(*) FILTER (WHERE overture_poi_score > 0.0) AS overture_poi_non_zero_tiles,
    COUNT(*) FILTER (WHERE building_density_score > 0.0) AS building_density_non_zero_tiles,
    COUNT(*) FILTER (WHERE darkness_score <> 0.5) AS darkness_changed_tiles,
    COUNT(*) FILTER (WHERE building_density_score = urban_penalty_score) AS urban_equals_building_tiles,
    ROUND(MIN(darkness_score)::numeric, 6) AS min_darkness_score,
    ROUND(AVG(darkness_score)::numeric, 6) AS avg_darkness_score,
    ROUND(MAX(darkness_score)::numeric, 6) AS max_darkness_score,
    ROUND(STDDEV_POP(darkness_score)::numeric, 6) AS stddev_darkness_score,
    ROUND(AVG(urban_penalty_score)::numeric, 6) AS avg_urban_penalty_score,
    ROUND(AVG(scenic_score)::numeric, 6) AS avg_scenic_score,
    ROUND(STDDEV_POP(scenic_score)::numeric, 6) AS stddev_scenic_score
FROM public.scenic_score_tiles
WHERE scoring_version = :'scoring_version';

SELECT scoring_version, COUNT(*)
FROM public.scenic_score_tiles
GROUP BY scoring_version
ORDER BY scoring_version;
