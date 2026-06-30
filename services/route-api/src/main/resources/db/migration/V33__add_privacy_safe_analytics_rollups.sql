ALTER TABLE analytics_events
    ADD COLUMN IF NOT EXISTS anonymous_client_hash VARCHAR(128),
    ADD COLUMN IF NOT EXISTS region_key VARCHAR(40),
    ADD COLUMN IF NOT EXISTS time_budget_bucket INTEGER;

UPDATE analytics_events
SET anonymous_client_hash = anonymous_session_id
WHERE anonymous_client_hash IS NULL;

UPDATE analytics_events
SET time_budget_bucket = time_budget_minutes
WHERE time_budget_bucket IS NULL
  AND time_budget_minutes IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_analytics_events_client_hash ON analytics_events (anonymous_client_hash);
CREATE INDEX IF NOT EXISTS idx_analytics_events_region ON analytics_events (region_key);
CREATE INDEX IF NOT EXISTS idx_analytics_events_time_bucket ON analytics_events (time_budget_bucket);

CREATE TABLE IF NOT EXISTS route_analytics_daily (
    day DATE NOT NULL,
    region_key VARCHAR(40) NOT NULL,
    route_mode VARCHAR(16) NOT NULL,
    vibe VARCHAR(64) NOT NULL,
    time_budget_bucket INTEGER NOT NULL,
    event_count BIGINT NOT NULL DEFAULT 0,
    submitted_count BIGINT NOT NULL DEFAULT 0,
    completed_count BIGINT NOT NULL DEFAULT 0,
    failed_count BIGINT NOT NULL DEFAULT 0,
    vibe_unavailable_count BIGINT NOT NULL DEFAULT 0,
    start_drive_count BIGINT NOT NULL DEFAULT 0,
    navigation_open_count BIGINT NOT NULL DEFAULT 0,
    generation_ms_total DOUBLE PRECISION NOT NULL DEFAULT 0,
    generation_ms_count BIGINT NOT NULL DEFAULT 0,
    route_options_total BIGINT NOT NULL DEFAULT 0,
    route_options_count BIGINT NOT NULL DEFAULT 0,
    scenic_score_total DOUBLE PRECISION NOT NULL DEFAULT 0,
    scenic_score_count BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (day, region_key, route_mode, vibe, time_budget_bucket)
);

CREATE INDEX IF NOT EXISTS idx_route_analytics_daily_day ON route_analytics_daily (day);
CREATE INDEX IF NOT EXISTS idx_route_analytics_daily_region ON route_analytics_daily (region_key);
CREATE INDEX IF NOT EXISTS idx_route_analytics_daily_vibe ON route_analytics_daily (vibe);
