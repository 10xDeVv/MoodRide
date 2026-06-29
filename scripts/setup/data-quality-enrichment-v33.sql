-- Water visibility / coastal-road scenic data quality v3.3.
--
-- Usage:
--   psql -d moodride -v ON_ERROR_STOP=1 -v chunk_size=50000 -f scripts/setup/data-quality-enrichment-v33.sql
--
-- Optional:
--   -v source_scoring_version=3.2-road-stress-calibration
--   -v scoring_version=3.3-water-visibility-calibration
--
-- Prereqs:
-- - scenic_score_tiles already has v3.2 road-stress enrichment columns.
-- - road_segments has h3_tile_index and geometry.
-- - natural_earth_Water_Bodies has water polygons. The script fails if it is missing or empty.
-- - planet_osm_line is optional; when present with hstore tags it adds bridge/water-crossing hints.

\if :{?chunk_size}
\else
\set chunk_size 50000
\endif

\if :{?scoring_version}
\else
\set scoring_version '3.3-water-visibility-calibration'
\endif

\if :{?source_scoring_version}
\else
\set source_scoring_version ''
\endif

DO $$
DECLARE
    water_rows BIGINT;
BEGIN
    IF to_regclass('public.scenic_score_tiles') IS NULL THEN
        RAISE EXCEPTION 'Required table missing: public.scenic_score_tiles';
    END IF;
    IF to_regclass('public.road_segments') IS NULL THEN
        RAISE EXCEPTION 'Required table missing: public.road_segments';
    END IF;
    IF to_regclass('public."natural_earth_Water_Bodies"') IS NULL THEN
        RAISE EXCEPTION 'Required table missing: public."natural_earth_Water_Bodies"';
    END IF;

    EXECUTE 'SELECT COUNT(*) FROM public."natural_earth_Water_Bodies"' INTO water_rows;
    IF water_rows = 0 THEN
        RAISE EXCEPTION 'public."natural_earth_Water_Bodies" has 0 rows; re-import water geometry before v3.3 water-visibility scoring.';
    END IF;
END $$;

ALTER TABLE public.scenic_score_tiles
    ADD COLUMN IF NOT EXISTS water_visibility_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS water_crossing_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS coastal_road_score DOUBLE PRECISION NOT NULL DEFAULT 0.0;

ALTER TABLE public.scenic_score_tiles DROP CONSTRAINT IF EXISTS water_visibility_score_range;
ALTER TABLE public.scenic_score_tiles DROP CONSTRAINT IF EXISTS water_crossing_score_range;
ALTER TABLE public.scenic_score_tiles DROP CONSTRAINT IF EXISTS coastal_road_score_range;
ALTER TABLE public.scenic_score_tiles
    ADD CONSTRAINT water_visibility_score_range CHECK (water_visibility_score >= 0.0 AND water_visibility_score <= 1.0),
    ADD CONSTRAINT water_crossing_score_range CHECK (water_crossing_score >= 0.0 AND water_crossing_score <= 1.0),
    ADD CONSTRAINT coastal_road_score_range CHECK (coastal_road_score >= 0.0 AND coastal_road_score <= 1.0);

CREATE INDEX IF NOT EXISTS road_segments_h3_tile_idx
    ON public.road_segments (h3_tile_index);

CREATE INDEX IF NOT EXISTS natural_earth_water_bodies_geom_idx
    ON public."natural_earth_Water_Bodies" USING GIST (geometry);

ANALYZE public.road_segments;
ANALYZE public."natural_earth_Water_Bodies";
ANALYZE public.scenic_score_tiles;

\echo Building optional v3.3 OSM bridge/coastal hint table...
DROP TABLE IF EXISTS public.dq_v33_osm_water_hint_lines;
CREATE UNLOGGED TABLE public.dq_v33_osm_water_hint_lines (
    geometry geometry(GEOMETRY, 4326),
    bridge_hint BOOLEAN NOT NULL DEFAULT FALSE,
    coastal_hint BOOLEAN NOT NULL DEFAULT FALSE
);

DO $$
BEGIN
    IF to_regclass('public.planet_osm_line') IS NOT NULL
       AND EXISTS (
           SELECT 1
           FROM information_schema.columns
           WHERE table_schema = 'public'
             AND table_name = 'planet_osm_line'
             AND column_name = 'tags'
       )
       AND EXISTS (
           SELECT 1
           FROM information_schema.columns
           WHERE table_schema = 'public'
             AND table_name = 'planet_osm_line'
             AND column_name = 'way'
       )
    THEN
        EXECUTE $sql$
            INSERT INTO public.dq_v33_osm_water_hint_lines (geometry, bridge_hint, coastal_hint)
            SELECT
                ST_CollectionExtract(ST_MakeValid(ST_Transform(way, 4326)), 2)::geometry(GEOMETRY, 4326),
                (tags ? 'bridge' AND COALESCE(tags->'bridge', '') NOT IN ('', 'no')) AS bridge_hint,
                (
                    (tags ? 'bridge' AND COALESCE(tags->'bridge', '') NOT IN ('', 'no'))
                    OR (tags ? 'waterway')
                    OR (tags ? 'ford')
                ) AS coastal_hint
            FROM public.planet_osm_line
            WHERE way IS NOT NULL
              AND (
                  (tags ? 'bridge' AND COALESCE(tags->'bridge', '') NOT IN ('', 'no'))
                  OR (tags ? 'waterway')
                  OR (tags ? 'ford')
              )
        $sql$;
    END IF;
END $$;

DELETE FROM public.dq_v33_osm_water_hint_lines
WHERE geometry IS NULL OR ST_IsEmpty(geometry);

CREATE INDEX IF NOT EXISTS dq_v33_osm_water_hint_lines_geom_idx
    ON public.dq_v33_osm_water_hint_lines USING GIST (geometry);
ANALYZE public.dq_v33_osm_water_hint_lines;

\echo Building v3.3 water visibility tile aggregates...
DROP TABLE IF EXISTS public.dq_v33_water_visibility_tiles;
CREATE UNLOGGED TABLE public.dq_v33_water_visibility_tiles AS
WITH segment_water AS (
    SELECT
        rs.h3_tile_index AS h3_index,
        GREATEST(COALESCE(rs.length_meters, 0.0), 1.0) AS length_weight,
        water.min_distance_meters,
        COALESCE(water.crosses_water, FALSE) AS crosses_water,
        COALESCE(hints.has_bridge_hint, FALSE) AS has_bridge_hint,
        COALESCE(hints.has_coastal_hint, FALSE) AS has_coastal_hint
    FROM public.road_segments rs
    LEFT JOIN LATERAL (
        SELECT
            MIN(ST_Distance(rs.geometry, w.geometry) * 111320.0) AS min_distance_meters,
            BOOL_OR(ST_Intersects(rs.geometry, w.geometry)) AS crosses_water
        FROM public."natural_earth_Water_Bodies" w
        WHERE w.geometry && ST_Expand(rs.geometry, 0.0035)
          AND ST_DWithin(rs.geometry, w.geometry, 0.0035)
    ) water ON TRUE
    LEFT JOIN LATERAL (
        SELECT
            BOOL_OR(h.bridge_hint) AS has_bridge_hint,
            BOOL_OR(h.coastal_hint) AS has_coastal_hint
        FROM public.dq_v33_osm_water_hint_lines h
        WHERE h.geometry && ST_Expand(rs.geometry, 0.001)
          AND ST_DWithin(rs.geometry, h.geometry, 0.0004)
    ) hints ON TRUE
    WHERE rs.h3_tile_index IS NOT NULL
      AND rs.h3_tile_index <> ''
),
segment_scores AS (
    SELECT
        h3_index,
        length_weight,
        CASE
            WHEN min_distance_meters IS NULL THEN 0.0
            WHEN min_distance_meters <= 25 THEN 1.00
            WHEN min_distance_meters <= 75 THEN 0.82
            WHEN min_distance_meters <= 150 THEN 0.56
            WHEN min_distance_meters <= 300 THEN 0.28
            ELSE 0.0
        END AS visibility_score,
        CASE
            WHEN crosses_water OR has_bridge_hint THEN 1.0
            WHEN has_coastal_hint THEN 0.45
            ELSE 0.0
        END AS crossing_score,
        CASE
            WHEN min_distance_meters IS NULL THEN 0.0
            WHEN crosses_water OR has_bridge_hint THEN 0.35
            WHEN min_distance_meters <= 50 THEN 1.00
            WHEN min_distance_meters <= 125 THEN 0.70
            WHEN min_distance_meters <= 250 THEN 0.35
            ELSE 0.0
        END AS coastal_road_score
    FROM segment_water
)
SELECT
    h3_index,
    LEAST(1.0, GREATEST(0.0, SUM(visibility_score * length_weight) / NULLIF(SUM(length_weight), 0.0))) AS water_visibility_score,
    LEAST(1.0, GREATEST(0.0, MAX(crossing_score))) AS water_crossing_score,
    LEAST(1.0, GREATEST(0.0, SUM(coastal_road_score * length_weight) / NULLIF(SUM(length_weight), 0.0))) AS coastal_road_score,
    COUNT(*) AS road_segment_count,
    SUM(length_weight) AS total_road_length_meters
FROM segment_scores
GROUP BY h3_index;

CREATE UNIQUE INDEX IF NOT EXISTS dq_v33_water_visibility_tiles_h3_idx
    ON public.dq_v33_water_visibility_tiles (h3_index);
ANALYZE public.dq_v33_water_visibility_tiles;

\echo Building v3.3 enrichment batches...
DROP TABLE IF EXISTS public.dq_v33_batches;
CREATE UNLOGGED TABLE public.dq_v33_batches AS
SELECT
    sst.h3_index,
    ROW_NUMBER() OVER (ORDER BY sst.h3_index) AS rn
FROM public.scenic_score_tiles sst
WHERE COALESCE(sst.scoring_version, '') <> :'scoring_version'
  AND (
      :'source_scoring_version' = ''
      OR COALESCE(sst.scoring_version, '') = :'source_scoring_version'
  );

CREATE UNIQUE INDEX IF NOT EXISTS dq_v33_batches_h3_idx
    ON public.dq_v33_batches (h3_index);
CREATE INDEX IF NOT EXISTS dq_v33_batches_rn_idx
    ON public.dq_v33_batches (rn);
ANALYZE public.dq_v33_batches;

SELECT COUNT(*) AS target_tiles, MIN(rn) AS min_rn, MAX(rn) AS max_rn
FROM public.dq_v33_batches;

\echo Running v3.3 water visibility enrichment with chunk_size=:chunk_size source_scoring_version=:source_scoring_version scoring_version=:scoring_version ...
WITH bounds AS (
    SELECT
        gs::INTEGER AS start_rn,
        LEAST(
            (gs + :chunk_size::INTEGER - 1)::INTEGER,
            (SELECT COALESCE(MAX(rn), 0) FROM public.dq_v33_batches)
        ) AS end_rn
    FROM generate_series(
        1,
        (SELECT COALESCE(MAX(rn), 0) FROM public.dq_v33_batches),
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
            LEAST(1.0, GREATEST(0.0, COALESCE(sst.water_score, 0.0))) AS prior_water_score,
            LEAST(1.0, GREATEST(0.0, COALESCE(sst.green_score, 0.0))) AS green_score,
            LEAST(1.0, GREATEST(0.0, COALESCE(sst.elevation_score, 0.0))) AS elevation_score,
            LEAST(1.0, GREATEST(0.0, COALESCE(sst.solitude_score, 0.0))) AS solitude_score,
            LEAST(1.0, GREATEST(0.0, COALESCE(sst.curve_score, 0.0))) AS curve_score,
            LEAST(1.0, GREATEST(0.0, GREATEST(COALESCE(sst.poi_score, 0.0), COALESCE(sst.overture_poi_score, 0.0)))) AS poi_score,
            LEAST(1.0, GREATEST(0.0, COALESCE(sst.park_score, 0.0))) AS park_score,
            LEAST(1.0, GREATEST(0.0, COALESCE(sst.urban_penalty_score, 0.0))) AS urban_penalty_score,
            LEAST(1.0, GREATEST(0.0, COALESCE(sst.road_stress_score, 0.0))) AS road_stress_score
        FROM public.scenic_score_tiles sst
        JOIN public.dq_v33_batches b
          ON b.h3_index = sst.h3_index
        WHERE b.rn BETWEEN v_start AND v_end
    ),
    combined AS (
        SELECT
            bt.*,
            LEAST(1.0, GREATEST(0.0, COALESCE(wv.water_visibility_score, 0.0))) AS water_visibility_score,
            LEAST(1.0, GREATEST(0.0, COALESCE(wv.water_crossing_score, 0.0))) AS water_crossing_score,
            LEAST(1.0, GREATEST(0.0, COALESCE(wv.coastal_road_score, 0.0))) AS coastal_road_score
        FROM batch_tiles bt
        LEFT JOIN public.dq_v33_water_visibility_tiles wv
          ON wv.h3_index = bt.h3_index
    ),
    rescored AS (
        SELECT
            c.*,
            GREATEST(
                c.prior_water_score,
                LEAST(
                    1.0,
                    GREATEST(
                        0.0,
                        c.water_visibility_score * 0.62 +
                        c.coastal_road_score * 0.26 +
                        c.water_crossing_score * 0.12
                    )
                )
            ) AS calibrated_water_score
        FROM combined c
    )
    UPDATE public.scenic_score_tiles sst
    SET water_visibility_score = r.water_visibility_score,
        water_crossing_score = r.water_crossing_score,
        coastal_road_score = r.coastal_road_score,
        water_score = r.calibrated_water_score,
        scenic_score = LEAST(
            1.0,
            GREATEST(
                0.0,
                (
                    r.calibrated_water_score * 0.22 +
                    r.green_score * 0.20 +
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

\echo Final stats (v3.3 target)
SELECT
    COUNT(*) AS tiles,
    COUNT(*) FILTER (WHERE water_visibility_score > 0.0) AS water_visibility_non_zero_tiles,
    COUNT(*) FILTER (WHERE water_crossing_score > 0.0) AS water_crossing_non_zero_tiles,
    COUNT(*) FILTER (WHERE coastal_road_score > 0.0) AS coastal_road_non_zero_tiles,
    ROUND(AVG(water_visibility_score)::numeric, 6) AS avg_water_visibility_score,
    ROUND(MAX(water_visibility_score)::numeric, 6) AS max_water_visibility_score,
    ROUND(STDDEV_POP(water_visibility_score)::numeric, 6) AS stddev_water_visibility_score,
    ROUND(AVG(water_crossing_score)::numeric, 6) AS avg_water_crossing_score,
    ROUND(AVG(coastal_road_score)::numeric, 6) AS avg_coastal_road_score,
    ROUND(AVG(water_score)::numeric, 6) AS avg_water_score,
    ROUND(AVG(scenic_score)::numeric, 6) AS avg_scenic_score,
    ROUND(STDDEV_POP(scenic_score)::numeric, 6) AS stddev_scenic_score
FROM public.scenic_score_tiles
WHERE scoring_version = :'scoring_version';

SELECT scoring_version, COUNT(*)
FROM public.scenic_score_tiles
GROUP BY scoring_version
ORDER BY scoring_version;
