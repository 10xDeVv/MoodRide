-- V8__create_spec_compatibility_views.sql
-- Compatibility views to expose engineering-spec naming while preserving current table structure.

DROP VIEW IF EXISTS route;
DROP VIEW IF EXISTS route_job;
DROP VIEW IF EXISTS road_segment_scores;
DROP VIEW IF EXISTS scenic_score_tile;
DROP VIEW IF EXISTS road_segment;
CREATE VIEW road_segment AS
SELECT
    id,
    osm_way_id,
    geometry AS geom,
    h3_tile_index AS h3_index_res7,
    road_type AS highway_type,
    surface AS surface_type,
    NULL::text AS road_name,
    speed_limit_kmh,
    length_meters AS length_m,
    curvature AS curvature_score,
    NULL::real AS avg_elevation_m,
    elevation_change AS elevation_gain_m,
    NULL::text AS land_use_class,
    NULL::real AS water_proximity_m,
    NULL::real AS poi_density,
    GREATEST(
        1,
        ROUND(
            CASE
                WHEN speed_limit_kmh > 0 THEN length_meters / (speed_limit_kmh * 1000.0 / 3600.0)
                ELSE length_meters / (50.0 * 1000.0 / 3600.0)
            END
        )::integer
    ) AS estimated_travel_time_s,
    CURRENT_TIMESTAMP AS last_updated,
    'osm'::text AS data_source
FROM road_segments;

CREATE VIEW scenic_score_tile AS
SELECT
    h3_index,
    7::smallint AS h3_resolution,
    ST_Y(ST_Centroid(geometry)) AS center_lat,
    ST_X(ST_Centroid(geometry)) AS center_lng,
    geometry AS geom,
    natural_land_use AS land_use_score,
    elevation_variance AS elevation_score,
    water_proximity AS water_proximity_score,
    (1.0 - LEAST(1.0, road_density))::real AS traffic_density_score,
    road_density AS road_curvature_score,
    poi_density AS poi_density_score,
    scenic_score AS composite_score,
    scenic_score AS coastal_score,
    scenic_score AS mountain_score,
    scenic_score AS countryside_score,
    scenic_score AS forest_score,
    scenic_score AS open_roads_score,
    last_scored AS computed_at,
    ARRAY['osm']::text[] AS data_sources_used,
    0.7::real AS confidence
FROM scenic_score_tiles;

CREATE VIEW route_job AS
SELECT
    id,
    user_id,
    start_latitude AS start_lat,
    start_longitude AS start_lng,
    time_budget_minutes,
    ARRAY[vibe]::text[] AS vibes,
    NULL::jsonb AS preference_vector,
    CASE
        WHEN status = 'SUBMITTED' THEN 'QUEUED'
        ELSE status
    END AS status,
    NULL::text AS worker_id,
    submitted_at AS queued_at,
    started_at,
    completed_at,
    NULL::timestamp AS failed_at,
    failure_reason,
    retry_count,
    2::smallint AS max_retries,
    NULL::uuid AS route_id
FROM route_jobs;

CREATE VIEW route AS
SELECT
    r.id,
    r.job_id,
    r.geometry AS geom,
    NULL::jsonb AS geom_geojson,
    NULL::bigint[] AS segment_ids,
    NULL::real[] AS segment_scores,
    r.total_distance_km * 1000.0 AS total_distance_m,
    r.estimated_duration_minutes * 60 AS estimated_duration_s,
    r.scenic_score,
    '[]'::jsonb AS scenic_highlights,
    j.start_latitude AS start_lat,
    j.start_longitude AS start_lng,
    j.time_budget_minutes,
    ARRAY[r.vibe]::text[] AS vibes,
    'STANDARD'::text AS quality_tier,
    'beam_v1'::text AS algorithm_version,
    10::integer AS beam_candidates,
    NULL::integer AS computation_time_ms,
    r.generated_at AS created_at,
    r.expires_at,
    NULL::smallint AS user_rating,
    NULL::timestamp AS rated_at
FROM routes r
JOIN route_jobs j ON j.id = r.job_id;
