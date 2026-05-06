-- V8__recompute_scenic_scores_no_nested_aggregates.sql
-- Recomputes scenic_score_tiles using a CTE to avoid nested aggregate calls.

WITH tile_stats AS (
    SELECT
        h3_tile_index AS h3_index,
        COUNT(*)::double precision AS road_count,
        ST_Envelope(ST_Collect(geometry)) AS tile_geom,
        GREATEST(ST_Area(ST_Envelope(ST_Collect(geometry))::geography) / 1000000.0, 0.001) AS area_km2
    FROM road_segments
    WHERE h3_tile_index IS NOT NULL
    GROUP BY h3_tile_index
),
scored AS (
    SELECT
        h3_index,
        tile_geom,
        LEAST((road_count / area_km2) / 100.0, 1.0) AS proxy_density_score
    FROM tile_stats
)
INSERT INTO scenic_score_tiles (
    h3_index,
    geometry,
    scenic_score,
    water_proximity,
    elevation_variance,
    natural_land_use,
    road_density,
    poi_density,
    visual_complexity,
    last_scored,
    scoring_version
)
SELECT
    h3_index,
    tile_geom,
    proxy_density_score,
    0.5,
    0.5,
    0.5,
    proxy_density_score,
    0.5,
    0.5,
    CURRENT_TIMESTAMP,
    '1.1'
FROM scored
ON CONFLICT (h3_index) DO UPDATE SET
    geometry = EXCLUDED.geometry,
    scenic_score = EXCLUDED.scenic_score,
    road_density = EXCLUDED.road_density,
    water_proximity = EXCLUDED.water_proximity,
    elevation_variance = EXCLUDED.elevation_variance,
    natural_land_use = EXCLUDED.natural_land_use,
    poi_density = EXCLUDED.poi_density,
    visual_complexity = EXCLUDED.visual_complexity,
    last_scored = CURRENT_TIMESTAMP,
    scoring_version = '1.1';

SELECT
    COUNT(*) AS total_tiles_scored,
    ROUND(AVG(scenic_score)::numeric, 3) AS avg_scenic_score,
    ROUND(MAX(scenic_score)::numeric, 3) AS max_scenic_score,
    ROUND(MIN(scenic_score)::numeric, 3) AS min_scenic_score
FROM scenic_score_tiles;

