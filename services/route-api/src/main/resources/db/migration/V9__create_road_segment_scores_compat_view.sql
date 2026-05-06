-- V9__create_road_segment_scores_compat_view.sql
-- Compatibility view for a Phase-4-style per-road scenic dataset.

DROP VIEW IF EXISTS road_segment_scores;
CREATE VIEW road_segment_scores AS
SELECT
    rs.id AS id,
    rs.geom AS geom,
    rs.highway_type AS road_type,
    rs.length_m AS length_meters,
    rs.curvature_score AS curvature_score,
    LEAST(1.0, GREATEST(0.0, ABS(COALESCE(rs.elevation_gain_m, 0.0)) / 100.0))::real AS elevation_score,
    COALESCE(1.0 - s.traffic_density_score, 0.5)::real AS traffic_score,
    COALESCE(s.land_use_score, 0.5)::real AS greenery_score,
    COALESCE(s.composite_score, 0.5)::real AS scenic_score,
    rs.last_updated AS updated_at
FROM road_segment rs
LEFT JOIN scenic_score_tile s ON s.h3_index = rs.h3_index_res7;

