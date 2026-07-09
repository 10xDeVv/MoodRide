CREATE INDEX IF NOT EXISTS idx_road_segments_geometry_geog_gist
    ON road_segments
    USING GIST ((geometry::geography))
    WHERE geometry IS NOT NULL;

ANALYZE road_segments;
