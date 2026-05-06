-- V11__create_canonical_natural_earth_water_table.sql
-- Canonical Natural Earth table using requested mixed-case identifier and reserved fields.

CREATE TABLE IF NOT EXISTS "natural_earth_Water_Bodies" (
    id BIGSERIAL PRIMARY KEY,
    geometry geometry(MULTIPOLYGON, 4326) NOT NULL,
    feature_name VARCHAR(255),
    water_feature_class VARCHAR(128),
    land_feature_class VARCHAR(128),
    is_park BOOLEAN NOT NULL DEFAULT FALSE,
    is_forest BOOLEAN NOT NULL DEFAULT FALSE,
    source_layer VARCHAR(64),
    source VARCHAR(64) NOT NULL DEFAULT 'NaturalEarth',
    last_updated TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ne_water_canonical_geom
    ON "natural_earth_Water_Bodies" USING GIST (geometry);

CREATE INDEX IF NOT EXISTS idx_ne_water_canonical_class
    ON "natural_earth_Water_Bodies" (water_feature_class);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public'
          AND table_name = 'natural_earth_water_bodies'
    ) THEN
        INSERT INTO "natural_earth_Water_Bodies" (
            geometry,
            feature_name,
            water_feature_class,
            source,
            last_updated
        )
        SELECT
            geometry,
            name,
            feature_class,
            source,
            last_updated
        FROM natural_earth_water_bodies
        WHERE geometry IS NOT NULL
          AND NOT EXISTS (
              SELECT 1
              FROM "natural_earth_Water_Bodies" c
              WHERE c.geometry = natural_earth_water_bodies.geometry
          );
    END IF;
END $$;

