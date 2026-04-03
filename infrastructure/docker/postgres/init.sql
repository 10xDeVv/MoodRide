-- MoodRide PostgreSQL Initialization Script
-- Creates PostGIS extension and sets up initial configuration

-- Enable PostGIS
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS postgis_topology;

-- Verify PostGIS installation
SELECT PostGIS_Version();

-- Create application user (if not exists)
DO
$$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_user WHERE usename = 'moodride') THEN
        CREATE USER moodride WITH PASSWORD 'moodride_dev_password';
    END IF;
END
$$;

-- Grant privileges
GRANT ALL PRIVILEGES ON DATABASE moodride TO moodride;
GRANT ALL ON SCHEMA public TO moodride;

-- Set search path
ALTER DATABASE moodride SET search_path TO public, postgis;

-- Log initialization
DO $$
BEGIN
    RAISE NOTICE 'MoodRide database initialized with PostGIS %', PostGIS_Version();
END $$;
