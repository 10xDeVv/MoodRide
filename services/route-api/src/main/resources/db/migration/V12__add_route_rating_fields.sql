-- V12__add_route_rating_fields.sql
-- Persist post-drive user ratings on route records.

ALTER TABLE routes
    ADD COLUMN IF NOT EXISTS user_rating SMALLINT;

ALTER TABLE routes
    ADD COLUMN IF NOT EXISTS rated_at TIMESTAMP;

ALTER TABLE routes
    DROP CONSTRAINT IF EXISTS routes_user_rating_range;

ALTER TABLE routes
    ADD CONSTRAINT routes_user_rating_range
    CHECK (user_rating IS NULL OR (user_rating >= 1 AND user_rating <= 5));

CREATE INDEX IF NOT EXISTS idx_routes_user_rating
    ON routes(user_rating)
    WHERE user_rating IS NOT NULL;
