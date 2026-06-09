CREATE TABLE IF NOT EXISTS route_duration_calibrations (
    id VARCHAR(128) PRIMARY KEY,
    route_mode VARCHAR(16) NOT NULL,
    region_key VARCHAR(32) NOT NULL,
    time_budget_bucket_minutes INTEGER NOT NULL,
    geometry_strategy VARCHAR(32) NOT NULL,
    sample_count INTEGER NOT NULL DEFAULT 0,
    radius_multiplier DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    learned_waypoint_count DOUBLE PRECISION NOT NULL DEFAULT 6.0,
    avg_requested_radius_km DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    avg_requested_waypoint_count DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    avg_duration_ratio DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_route_duration_calibrations_lookup
    ON route_duration_calibrations (
        route_mode,
        region_key,
        time_budget_bucket_minutes,
        geometry_strategy
    );
