BEGIN;

DROP TABLE IF EXISTS water_tile_summary;
DROP TABLE IF EXISTS poi_tile_summary;
DROP TABLE IF EXISTS landuse_tile_summary;

CREATE TABLE water_tile_summary AS
SELECT
    t.h3_index,
    COUNT(DISTINCT p.osm_id) AS water_feature_count,
    COALESCE(SUM(ST_Area(p.way)), 0) AS water_area_sqm,
    COALESCE(
        MIN(
            ST_Distance(
                p.way,
                ST_Transform(t.geometry, 3857)
            )
        ),
        5000
    ) AS min_distance_to_water_m
FROM scenic_score_tiles t
LEFT JOIN planet_osm_polygon p
  ON ST_DWithin(
        p.way,
        ST_Transform(t.geometry, 3857),
        5000
     )
 AND (
        p.natural IN ('water', 'bay', 'wetland')
        OR p.water IS NOT NULL
        OR p.waterway IS NOT NULL
        OR p.landuse IN ('reservoir', 'basin')
     )
GROUP BY t.h3_index;

CREATE UNIQUE INDEX water_tile_summary_pk ON water_tile_summary (h3_index);

CREATE TABLE poi_tile_summary AS
SELECT
    t.h3_index,
    COUNT(DISTINCT p.osm_id) AS poi_count,
    COUNT(DISTINCT p.osm_id) FILTER (WHERE p.tourism IS NOT NULL) AS tourism_count,
    COUNT(DISTINCT p.osm_id) FILTER (WHERE p.amenity IS NOT NULL) AS amenity_count,
    COUNT(DISTINCT p.osm_id) FILTER (WHERE p.historic IS NOT NULL) AS historic_count
FROM scenic_score_tiles t
LEFT JOIN planet_osm_point p
  ON ST_DWithin(
        p.way,
        ST_Transform(t.geometry, 3857),
        250
     )
 AND (
        p.tourism IS NOT NULL
        OR p.leisure IS NOT NULL
        OR p.natural IN ('peak', 'viewpoint', 'spring')
        OR p.amenity IN ('park', 'camp_site')
     )
GROUP BY t.h3_index;

CREATE UNIQUE INDEX poi_tile_summary_pk ON poi_tile_summary (h3_index);

CREATE TABLE landuse_tile_summary AS
SELECT
    t.h3_index,
    COUNT(DISTINCT p.osm_id) FILTER (
        WHERE p.natural IN ('water', 'bay', 'wetland', 'wood', 'scrub', 'grassland', 'meadow', 'forest', 'sand')
    ) AS natural_count,
    COUNT(DISTINCT p.osm_id) FILTER (
        WHERE p.landuse IN ('residential', 'commercial', 'industrial', 'retail', 'construction', 'railway', 'military')
    ) AS urban_count,
    COUNT(DISTINCT p.osm_id) FILTER (
        WHERE p.landuse = 'residential'
    ) AS residential_count
FROM scenic_score_tiles t
LEFT JOIN planet_osm_polygon p
  ON ST_DWithin(
        p.way,
    ST_Transform(t.geometry, 3857),
        1000
     )
GROUP BY t.h3_index;

CREATE UNIQUE INDEX landuse_tile_summary_pk ON landuse_tile_summary (h3_index);

COMMIT;