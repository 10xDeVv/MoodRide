-- Persist user preference vectors so route workers can personalize route generation.
ALTER TABLE route_jobs
    ADD COLUMN IF NOT EXISTS preference_vector TEXT;
