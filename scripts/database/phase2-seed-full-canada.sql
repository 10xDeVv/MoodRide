BEGIN;

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
    scoring_version,
    traffic_signal_score,
    created_at
)
SELECT DISTINCT
    rs.h3_tile_index AS h3_index,
    ST_SetSRID(
        h3_cell_to_boundary(rs.h3_tile_index::h3index)::geometry,
        4326
    ) AS geometry,
    0.0 AS scenic_score,
    0.0 AS water_proximity,
    0.0 AS elevation_variance,
    0.0 AS natural_land_use,
    0.0 AS road_density,
    0.0 AS poi_density,
    0.0 AS visual_complexity,
    NOW() AS last_scored,
    'phase2-full-canada' AS scoring_version,
    0.0 AS traffic_signal_score,
    NOW() AS created_at
FROM road_segments rs
WHERE NULLIF(BTRIM(rs.h3_tile_index), '') IS NOT NULL
ON CONFLICT (h3_index) DO NOTHING;

COMMIT;