-- MoodRide PostgreSQL Initialization Script
-- Creates PostGIS extension and sets up initial configuration

-- Enable PostGIS
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS postgis_topology;

-- Verify PostGIS installation
SELECT PostGIS_Version();

-- Read credentials from container env exposed to psql
\getenv app_user POSTGRES_USER
\getenv app_password POSTGRES_PASSWORD

-- Create or rotate application user using injected env values
DO
$$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_user WHERE usename = :'app_user') THEN
        EXECUTE format('CREATE USER %I WITH PASSWORD %L', :'app_user', :'app_password');
    ELSE
        EXECUTE format('ALTER USER %I WITH PASSWORD %L', :'app_user', :'app_password');
    END IF;
END
$$;

-- Grant privileges
DO $$
BEGIN
    EXECUTE format('GRANT ALL PRIVILEGES ON DATABASE moodride TO %I', :'app_user');
    EXECUTE format('GRANT ALL ON SCHEMA public TO %I', :'app_user');
END $$;

-- Set search path
ALTER DATABASE moodride SET search_path TO public, postgis;

-- Log initialization
DO $$
BEGIN
    RAISE NOTICE 'MoodRide database initialized with PostGIS %', PostGIS_Version();
END $$;
