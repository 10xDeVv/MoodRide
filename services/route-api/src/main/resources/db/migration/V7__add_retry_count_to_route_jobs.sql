-- Add retry_count column to route_jobs table for tracking job retry attempts
ALTER TABLE route_jobs ADD COLUMN IF NOT EXISTS retry_count INTEGER NOT NULL DEFAULT 0;
