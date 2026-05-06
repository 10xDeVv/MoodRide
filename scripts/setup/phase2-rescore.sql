WITH tile_base AS (
  SELECT rs.h3_tile_index AS h3_index,
         ST_Envelope(ST_Collect(rs.geometry)) AS tile_geom,
         COUNT(*)::double precision AS road_count,
         GREATEST(ST_Area(ST_Envelope(ST_Collect(rs.geometry))::geography) / 1000000.0, 0.001) AS area_km2,
         COALESCE(MIN(rs.elevation_change), 0.0) AS min_elev,
         COALESCE(MAX(rs.elevation_change), 0.0) AS max_elev
  FROM road_segments rs
  GROUP BY rs.h3_tile_index
),
water_component AS (
  SELECT tb.h3_index,
         CASE WHEN EXISTS (SELECT 1 FROM natural_earth_water_bodies w WHERE ST_Intersects(tb.tile_geom, w.geometry))
              THEN 1.0
              ELSE GREATEST(0.0, 1.0 - (COALESCE((SELECT MIN(ST_Distance(tb.tile_geom::geography, w.geometry::geography)) FROM natural_earth_water_bodies w), 5000.0) / 5000.0))
         END AS water_proximity
  FROM tile_base tb
),
nlcd_component AS (
  SELECT tb.h3_index,
         COALESCE(
           SUM(CASE WHEN n.nlcd_class IN (41,42,43,52,71,90,95)
                    THEN ST_Area(ST_Intersection(n.geometry, tb.tile_geom)::geography)
                    ELSE 0 END) / NULLIF(SUM(ST_Area(ST_Intersection(n.geometry, tb.tile_geom)::geography)), 0),
           0.5
         ) AS natural_land_use
  FROM tile_base tb
  LEFT JOIN nlcd_land_cover_cells n ON ST_Intersects(n.geometry, tb.tile_geom)
  GROUP BY tb.h3_index
),
poi_component AS (
  SELECT tb.h3_index,
         LEAST(COALESCE(COUNT(p.*),0)::double precision / NULLIF(tb.area_km2,0) / 10.0, 1.0) AS poi_density
  FROM tile_base tb
  LEFT JOIN planet_osm_point p
    ON ST_Intersects(p.way, tb.tile_geom)
   AND (p.tourism IS NOT NULL OR p.leisure IS NOT NULL OR p.natural IN ('peak','viewpoint','spring') OR p.amenity IN ('park','camp_site'))
  GROUP BY tb.h3_index, tb.area_km2
),
traffic_component AS (
  SELECT tb.h3_index, COALESCE(AVG(t.traffic_score),0.5) AS traffic_signal_score
  FROM tile_base tb
  LEFT JOIN traffic_tile_signals t ON t.h3_index = tb.h3_index
  GROUP BY tb.h3_index
),
assembled AS (
  SELECT tb.h3_index,
         tb.tile_geom,
         wc.water_proximity,
         LEAST((tb.max_elev - tb.min_elev) / 200.0, 1.0) AS elevation_variance,
         nc.natural_land_use,
         LEAST((tb.road_count / tb.area_km2) / 100.0, 1.0) AS road_density,
         pc.poi_density,
         tc.traffic_signal_score
  FROM tile_base tb
  JOIN water_component wc ON wc.h3_index = tb.h3_index
  JOIN nlcd_component nc ON nc.h3_index = tb.h3_index
  JOIN poi_component pc ON pc.h3_index = tb.h3_index
  JOIN traffic_component tc ON tc.h3_index = tb.h3_index
),
scored AS (
  SELECT a.*,
         LEAST((a.elevation_variance * 0.6) + (a.natural_land_use * 0.4), 1.0) AS visual_complexity,
         (
            a.water_proximity * 0.25 +
            a.elevation_variance * 0.20 +
            a.natural_land_use * 0.20 +
            LEAST((a.road_density * 0.75) + (a.traffic_signal_score * 0.25), 1.0) * 0.10 +
            a.poi_density * 0.15 +
            LEAST((a.elevation_variance * 0.6) + (a.natural_land_use * 0.4), 1.0) * 0.10
         ) AS raw_score
  FROM assembled a
),
normalized AS (
  SELECT s.*,
         CASE WHEN MAX(raw_score) OVER () = MIN(raw_score) OVER ()
              THEN 0.5
              ELSE (raw_score - MIN(raw_score) OVER ()) / NULLIF(MAX(raw_score) OVER () - MIN(raw_score) OVER (), 0)
         END AS scenic_score
  FROM scored s
)
INSERT INTO scenic_score_tiles (
  h3_index, geometry, scenic_score, water_proximity, elevation_variance, natural_land_use,
  road_density, poi_density, traffic_signal_score, visual_complexity, last_scored, scoring_version
)
SELECT h3_index, tile_geom, scenic_score, water_proximity, elevation_variance, natural_land_use,
       road_density, poi_density, traffic_signal_score, visual_complexity, CURRENT_TIMESTAMP, '2.1-traffic-signals'
FROM normalized
ON CONFLICT (h3_index) DO UPDATE SET
  geometry = EXCLUDED.geometry,
  scenic_score = EXCLUDED.scenic_score,
  water_proximity = EXCLUDED.water_proximity,
  elevation_variance = EXCLUDED.elevation_variance,
  natural_land_use = EXCLUDED.natural_land_use,
  road_density = EXCLUDED.road_density,
  poi_density = EXCLUDED.poi_density,
  traffic_signal_score = EXCLUDED.traffic_signal_score,
  visual_complexity = EXCLUDED.visual_complexity,
  last_scored = EXCLUDED.last_scored,
  scoring_version = EXCLUDED.scoring_version;
