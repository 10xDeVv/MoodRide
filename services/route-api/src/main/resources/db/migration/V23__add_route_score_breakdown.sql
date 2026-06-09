ALTER TABLE routes
    ADD COLUMN IF NOT EXISTS score_breakdown_json TEXT;
