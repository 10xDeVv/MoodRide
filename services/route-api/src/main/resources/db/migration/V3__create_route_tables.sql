-- V3__create_route_tables.sql
-- Route and route job tracking tables

CREATE TABLE route_jobs (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    start_latitude DOUBLE PRECISION NOT NULL,
    start_longitude DOUBLE PRECISION NOT NULL,
    time_budget_minutes INTEGER NOT NULL,
    vibe VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED',
    failure_reason TEXT,
    submitted_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for job queries
CREATE INDEX idx_job_user ON route_jobs(user_id);
CREATE INDEX idx_job_status ON route_jobs(status);
CREATE INDEX idx_job_submitted ON route_jobs(submitted_at DESC);

-- Routes table - represents a generated scenic route
CREATE TABLE routes (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL UNIQUE,
    user_id UUID NOT NULL,
    geometry geometry(LINESTRING, 4326) NOT NULL,
    total_distance_km DOUBLE PRECISION NOT NULL,
    estimated_duration_minutes INTEGER NOT NULL,
    scenic_score DOUBLE PRECISION NOT NULL,
    vibe VARCHAR(20) NOT NULL,
    generated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_route_job FOREIGN KEY (job_id) REFERENCES route_jobs(id) ON DELETE CASCADE
);

-- Indexes for route queries
CREATE INDEX idx_route_job ON routes(job_id);
CREATE INDEX idx_route_user ON routes(user_id);
CREATE INDEX idx_route_score ON routes(scenic_score DESC);
CREATE INDEX idx_route_geom ON routes USING GIST(geometry);
CREATE INDEX idx_route_expires ON routes(expires_at);

-- Route waypoints table - individual navigation points
CREATE TABLE route_waypoints (
    id UUID PRIMARY KEY,
    route_id UUID NOT NULL,
    waypoint_order INTEGER NOT NULL,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    instruction VARCHAR(200),
    distance_to_next DOUBLE PRECISION NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_waypoint_route FOREIGN KEY (route_id) REFERENCES routes(id) ON DELETE CASCADE
);

-- Indexes for waypoint queries
CREATE INDEX idx_waypoint_route ON route_waypoints(route_id, waypoint_order);

-- Constraint on scenic score range
ALTER TABLE routes ADD CONSTRAINT route_score_range 
    CHECK (scenic_score >= 0.0 AND scenic_score <= 1.0);
