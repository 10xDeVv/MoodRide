-- V7__extract_osm_highways_schema_compatible.sql
-- Phase 1 fallback extraction for osm2pgsql schemas that do not expose a `tags` hstore column.

DO $$
DECLARE
    has_tags_column boolean;
    insert_sql text;
BEGIN
    SELECT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'planet_osm_line'
          AND column_name = 'tags'
    ) INTO has_tags_column;

    IF has_tags_column THEN
        insert_sql := $q$
            INSERT INTO road_segments (
                osm_way_id,
                geometry,
                h3_tile_index,
                length_meters,
                speed_limit_kmh,
                road_type,
                surface,
                curvature,
                elevation_change,
                last_updated
            )
            SELECT
                osm_id,
                ST_Transform(way, 4326),
                'h3_' || LPAD(((ST_X(ST_Centroid(ST_Transform(way, 4326)))::int + 180) * 100)::text, 5, '0') ||
                '_' || LPAD(((ST_Y(ST_Centroid(ST_Transform(way, 4326)))::int + 90) * 100)::text, 5, '0') AS h3_index,
                ST_Length(ST_Transform(way, 4326)::geography),
                CASE
                    WHEN tags ? 'maxspeed' THEN
                        CASE
                            WHEN tags->'maxspeed' LIKE '%mph%' THEN
                                (REPLACE(tags->'maxspeed', ' mph', '')::int * 1.609)::int
                            ELSE COALESCE((tags->'maxspeed')::int, 50)
                        END
                    ELSE 50
                END AS speed_limit,
                tags->'highway' AS road_type,
                COALESCE(tags->'surface', 'paved') AS surface,
                CASE
                    WHEN ST_NumPoints(way) > 2 AND ST_Distance(ST_StartPoint(ST_Transform(way, 4326))::geography, ST_EndPoint(ST_Transform(way, 4326))::geography) > 0 THEN
                        LEAST(
                            ST_Length(ST_Transform(way, 4326)::geography) /
                            ST_Distance(ST_StartPoint(ST_Transform(way, 4326))::geography, ST_EndPoint(ST_Transform(way, 4326))::geography),
                            3.0
                        ) / 3.0
                    ELSE 0.0
                END AS curvature,
                0.0 AS elevation_change,
                CURRENT_TIMESTAMP
            FROM planet_osm_line
                        WHERE tags->'highway' IS NOT NULL
              AND way IS NOT NULL
              AND ST_IsValid(way)
              AND ST_Length(ST_Transform(way, 4326)::geography) > 50
              AND tags->'highway' IN (
                  'motorway', 'trunk', 'primary', 'secondary', 'tertiary',
                  'unclassified', 'residential', 'service', 'living_street',
                  'motorway_link', 'trunk_link', 'primary_link', 'secondary_link',
                  'road', 'track', 'path'
              )
              AND NOT EXISTS (
                  SELECT 1 FROM road_segments rs WHERE rs.osm_way_id = planet_osm_line.osm_id
              )
            ON CONFLICT (osm_way_id) DO NOTHING;
        $q$;
    ELSE
        insert_sql := $q$
            INSERT INTO road_segments (
                osm_way_id,
                geometry,
                h3_tile_index,
                length_meters,
                speed_limit_kmh,
                road_type,
                surface,
                curvature,
                elevation_change,
                last_updated
            )
            SELECT
                osm_id,
                ST_Transform(way, 4326),
                'h3_' || LPAD(((ST_X(ST_Centroid(ST_Transform(way, 4326)))::int + 180) * 100)::text, 5, '0') ||
                '_' || LPAD(((ST_Y(ST_Centroid(ST_Transform(way, 4326)))::int + 90) * 100)::text, 5, '0') AS h3_index,
                ST_Length(ST_Transform(way, 4326)::geography),
                50 AS speed_limit,
                highway AS road_type,
                COALESCE(surface, 'paved') AS surface,
                CASE
                    WHEN ST_NumPoints(way) > 2 AND ST_Distance(ST_StartPoint(ST_Transform(way, 4326))::geography, ST_EndPoint(ST_Transform(way, 4326))::geography) > 0 THEN
                        LEAST(
                            ST_Length(ST_Transform(way, 4326)::geography) /
                            ST_Distance(ST_StartPoint(ST_Transform(way, 4326))::geography, ST_EndPoint(ST_Transform(way, 4326))::geography),
                            3.0
                        ) / 3.0
                    ELSE 0.0
                END AS curvature,
                0.0 AS elevation_change,
                CURRENT_TIMESTAMP
            FROM planet_osm_line
                        WHERE highway IS NOT NULL
              AND way IS NOT NULL
              AND ST_IsValid(way)
              AND ST_Length(ST_Transform(way, 4326)::geography) > 50
              AND highway IN (
                  'motorway', 'trunk', 'primary', 'secondary', 'tertiary',
                  'unclassified', 'residential', 'service', 'living_street',
                  'motorway_link', 'trunk_link', 'primary_link', 'secondary_link',
                  'road', 'track', 'path'
              )
              AND NOT EXISTS (
                  SELECT 1 FROM road_segments rs WHERE rs.osm_way_id = planet_osm_line.osm_id
              )
            ON CONFLICT (osm_way_id) DO NOTHING;
        $q$;
    END IF;

    EXECUTE insert_sql;
END $$;

SELECT
    COUNT(*) AS total_segments,
    COUNT(DISTINCT road_type) AS unique_road_types,
    AVG(length_meters) AS avg_segment_length_m,
    AVG(curvature) AS avg_curvature
FROM road_segments;

