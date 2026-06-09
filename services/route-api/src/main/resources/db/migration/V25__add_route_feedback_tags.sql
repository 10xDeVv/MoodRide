ALTER TABLE routes
    ADD COLUMN IF NOT EXISTS feedback_tags_json TEXT;

