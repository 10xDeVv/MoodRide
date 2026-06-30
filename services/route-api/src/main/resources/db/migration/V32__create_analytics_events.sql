CREATE TABLE IF NOT EXISTS analytics_events (
    id UUID PRIMARY KEY,
    anonymous_session_id VARCHAR(80) NOT NULL,
    event_name VARCHAR(80) NOT NULL,
    job_id UUID,
    route_id UUID,
    route_profile VARCHAR(40),
    route_mode VARCHAR(16),
    vibes_json TEXT,
    time_budget_minutes INTEGER,
    route_count INTEGER,
    status VARCHAR(40),
    duration_ms BIGINT,
    scenic_score DOUBLE PRECISION,
    metadata_json TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_analytics_events_created_at ON analytics_events (created_at);
CREATE INDEX IF NOT EXISTS idx_analytics_events_event_name ON analytics_events (event_name);
CREATE INDEX IF NOT EXISTS idx_analytics_events_session ON analytics_events (anonymous_session_id);
CREATE INDEX IF NOT EXISTS idx_analytics_events_job ON analytics_events (job_id);
CREATE INDEX IF NOT EXISTS idx_analytics_events_route ON analytics_events (route_id);
