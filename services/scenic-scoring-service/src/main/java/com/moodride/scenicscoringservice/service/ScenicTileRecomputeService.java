package com.moodride.scenicscoringservice.service;

import com.moodride.datamodels.ScenicScoreTile;
import com.moodride.scenicscoringservice.elevation.RoadSegmentElevationEnrichmentService;
import com.moodride.scenicscoringservice.processor.ScenicScoringProcessor;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.WKBReader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class ScenicTileRecomputeService {

    private static final double H3_RES7_TILE_AREA_SQ_KM = 5.16;
    private static final double WATER_SEARCH_RADIUS_METERS = 5000.0;
    private static final double POI_LINK_RADIUS_METERS = 250.0;

    private final JdbcTemplate jdbcTemplate;
    private final ScenicScoringProcessor scoringProcessor;
    private final RoadSegmentElevationEnrichmentService elevationEnrichmentService;

    public ScenicTileRecomputeService(
            JdbcTemplate jdbcTemplate,
            ScenicScoringProcessor scoringProcessor,
            RoadSegmentElevationEnrichmentService elevationEnrichmentService) {
        this.jdbcTemplate = jdbcTemplate;
        this.scoringProcessor = scoringProcessor;
        this.elevationEnrichmentService = elevationEnrichmentService;
    }

    @Transactional
    public int recomputeTiles(List<String> h3Indexes) {
        if (h3Indexes == null || h3Indexes.isEmpty()) {
            return 0;
        }

        elevationEnrichmentService.enrichMissingElevation();
        int updated = 0;
        for (String h3Index : normalize(h3Indexes)) {
            H3TileData tileData = loadTile(h3Index);
            if (tileData == null || tileData.geometry == null) {
                continue;
            }

            ScenicScoreTile tile = computeTile(tileData);
            upsertTile(tile);
            updated++;
        }
        return updated;
    }

    private H3TileData loadTile(String h3Index) {
        String sql = """
                SELECT h3_tile_index,
                       ST_AsBinary(ST_Envelope(ST_Collect(geometry))) AS tile_geometry
                FROM road_segments
                WHERE h3_tile_index = ?
                GROUP BY h3_tile_index
                """;

        List<H3TileData> results = jdbcTemplate.query(sql, (rs, rowNum) -> {
            H3TileData data = new H3TileData();
            data.h3Index = rs.getString("h3_tile_index");
            byte[] wkb = rs.getBytes("tile_geometry");
            if (wkb != null) {
                try {
                    Geometry geometry = new WKBReader().read(wkb);
                    if (geometry instanceof Polygon polygon) {
                        data.geometry = polygon;
                    }
                } catch (Exception ignored) {
                    data.geometry = null;
                }
            }
            return data;
        }, h3Index);

        return results.isEmpty() ? null : results.get(0);
    }

    private ScenicScoreTile computeTile(H3TileData tileData) {
        double waterScore = computeWaterProximity(tileData.h3Index);
        double elevationScore = computeElevationVariance(tileData.h3Index);
        double landUseScore = computeNaturalLandUse(tileData.h3Index);
        double roadDensityScore = computeRoadDensity(tileData.h3Index);
        double trafficScore = computeTrafficScore(tileData.h3Index);
        double blendedRoadScore = blendRoadDensityWithTraffic(roadDensityScore, trafficScore);
        double poiScore = computePoiDensity(tileData.h3Index);
        double visualScore = scoringProcessor.scoreVisualComplexity(elevationScore, landUseScore);

        ScenicScoreTile tile = scoringProcessor.computeScenicScore(
                tileData.h3Index,
                tileData.geometry,
                waterScore,
                elevationScore,
                landUseScore,
                blendedRoadScore,
                trafficScore,
                poiScore,
                visualScore
        );
        tile.setScoringVersion("2.1-traffic-signals");
        tile.setLastScored(Instant.now());
        return tile;
    }

    private void upsertTile(ScenicScoreTile tile) {
        String upsertSql = """
                INSERT INTO scenic_score_tiles (
                    h3_index,
                    geometry,
                    scenic_score,
                    water_proximity,
                    water_score,
                    elevation_variance,
                    elevation_score,
                    natural_land_use,
                    green_score,
                    road_density,
                    solitude_score,
                    poi_density,
                    poi_score,
                    traffic_signal_score,
                    visual_complexity,
                    curve_score,
                    last_scored,
                    scoring_version
                )
                VALUES (?, ST_GeomFromText(?, 4326), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (h3_index) DO UPDATE SET
                    geometry = EXCLUDED.geometry,
                    scenic_score = EXCLUDED.scenic_score,
                    water_proximity = EXCLUDED.water_proximity,
                    water_score = EXCLUDED.water_score,
                    elevation_variance = EXCLUDED.elevation_variance,
                    elevation_score = EXCLUDED.elevation_score,
                    natural_land_use = EXCLUDED.natural_land_use,
                    green_score = EXCLUDED.green_score,
                    road_density = EXCLUDED.road_density,
                    solitude_score = EXCLUDED.solitude_score,
                    poi_density = EXCLUDED.poi_density,
                    poi_score = EXCLUDED.poi_score,
                    traffic_signal_score = EXCLUDED.traffic_signal_score,
                    visual_complexity = EXCLUDED.visual_complexity,
                    curve_score = EXCLUDED.curve_score,
                    last_scored = EXCLUDED.last_scored,
                    scoring_version = EXCLUDED.scoring_version
                """;

        jdbcTemplate.update(
                upsertSql,
                tile.getH3Index(),
                tile.getGeometry().toText(),
                tile.getScenicScore(),
                tile.getWaterProximity(),
                tile.getWaterScore(),
                tile.getElevationVariance(),
                tile.getElevationScore(),
                tile.getNaturalLandUse(),
                tile.getGreenScore(),
                tile.getRoadDensity(),
                tile.getSolitudeScore(),
                tile.getPoiDensity(),
                tile.getPoiScore(),
                tile.getTrafficSignalScore(),
                tile.getVisualComplexity(),
                tile.getCurveScore(),
                Timestamp.from(tile.getLastScored()),
                tile.getScoringVersion()
        );
    }

    private double computeWaterProximity(String h3Index) {
        String sql = """
                SELECT COALESCE(
                    MIN(distance_m),
                    ?
                )
                FROM (
                    SELECT MIN(ST_Distance(rs.geometry::geography, w.geometry::geography)) AS distance_m
                    FROM road_segments rs
                    JOIN "natural_earth_Water_Bodies" w
                      ON ST_DWithin(rs.geometry::geography, w.geometry::geography, ?)
                    WHERE rs.h3_tile_index = ?
                    UNION ALL
                    SELECT MIN(ST_Distance(rs.geometry::geography, ST_Transform(p.way, 4326)::geography)) AS distance_m
                    FROM road_segments rs
                    JOIN planet_osm_polygon p
                      ON ST_DWithin(rs.geometry::geography, ST_Transform(p.way, 4326)::geography, ?)
                    WHERE rs.h3_tile_index = ?
                      AND (
                        (p.tags ? 'natural' AND p.tags->'natural' IN ('water', 'bay', 'wetland'))
                        OR (p.tags ? 'water')
                        OR (p.tags ? 'waterway')
                        OR (p.tags ? 'landuse' AND p.tags->'landuse' IN ('reservoir', 'basin'))
                      )
                ) distances
                """;

        try {
            Double minDistance = jdbcTemplate.queryForObject(
                    sql,
                    Double.class,
                    WATER_SEARCH_RADIUS_METERS,
                    WATER_SEARCH_RADIUS_METERS,
                    h3Index,
                    WATER_SEARCH_RADIUS_METERS,
                    h3Index
            );
            return scoringProcessor.scoreWaterProximity(null, minDistance == null ? WATER_SEARCH_RADIUS_METERS : minDistance);
        } catch (Exception ex) {
            return 0.5;
        }
    }

    private double computeElevationVariance(String h3Index) {
        String sql = """
                SELECT COALESCE(MIN(elevation_change), 0.0) AS min_elev,
                       COALESCE(MAX(elevation_change), 0.0) AS max_elev
                FROM road_segments
                WHERE h3_tile_index = ?
                """;
        try {
            return jdbcTemplate.query(sql, rs -> {
                if (!rs.next()) {
                    return 0.0;
                }
                return scoringProcessor.scoreElevationVariance(rs.getDouble("min_elev"), rs.getDouble("max_elev"));
            }, h3Index);
        } catch (Exception ex) {
            return 0.0;
        }
    }

    private double computeNaturalLandUse(String h3Index) {
        String sql = """
                SELECT COUNT(*) FILTER (WHERE road_type IN ('residential', 'service', 'living_street')) AS local_roads,
                       COUNT(*) FILTER (WHERE road_type IN ('motorway', 'trunk', 'primary')) AS major_roads,
                       COUNT(*) AS total
                FROM road_segments
                WHERE h3_tile_index = ?
                """;

        return jdbcTemplate.query(sql, rs -> {
            if (!rs.next()) {
                return 0.5;
            }
            int total = rs.getInt("total");
            if (total == 0) {
                return 0.5;
            }
            double localRatio = (double) rs.getInt("local_roads") / total;
            double majorRatio = (double) rs.getInt("major_roads") / total;
            return clamp01(0.5 + (localRatio - majorRatio) * 0.5);
        }, h3Index);
    }

    private double computeRoadDensity(String h3Index) {
        try {
            Integer roadCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM road_segments WHERE h3_tile_index = ?",
                    Integer.class,
                    h3Index
            );
            return scoringProcessor.scoreRoadDensity(roadCount == null ? 0 : roadCount, H3_RES7_TILE_AREA_SQ_KM);
        } catch (Exception ex) {
            return 0.0;
        }
    }

    private double computeTrafficScore(String h3Index) {
        try {
            Double score = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(AVG(traffic_score), 0.5) FROM traffic_tile_signals WHERE h3_index = ?",
                    Double.class,
                    h3Index
            );
            return clamp01(score == null ? 0.5 : score);
        } catch (Exception ex) {
            return 0.5;
        }
    }

    private double blendRoadDensityWithTraffic(double roadDensityScore, double trafficScore) {
        return clamp01((roadDensityScore * 0.75) + (trafficScore * 0.25));
    }

    private double computePoiDensity(String h3Index) {
        String sql = """
                SELECT COUNT(*)
                FROM planet_osm_point p
                WHERE (
                    (p.tags ? 'tourism')
                    OR (p.tags ? 'leisure')
                    OR (p.tags ? 'natural' AND p.tags->'natural' IN ('peak', 'viewpoint', 'spring'))
                    OR (p.tags ? 'amenity' AND p.tags->'amenity' IN ('park', 'camp_site'))
                )
                AND EXISTS (
                    SELECT 1
                    FROM road_segments rs
                    WHERE rs.h3_tile_index = ?
                      AND ST_DWithin(
                          ST_Transform(p.way, 4326)::geography,
                          rs.geometry::geography,
                          ?
                      )
                )
                """;

        try {
            Integer poiCount = jdbcTemplate.queryForObject(sql, Integer.class, h3Index, POI_LINK_RADIUS_METERS);
            return scoringProcessor.scorePoiDensity(poiCount == null ? 0 : poiCount, H3_RES7_TILE_AREA_SQ_KM);
        } catch (Exception ex) {
            return 0.5;
        }
    }

    private List<String> normalize(List<String> values) {
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed);
            }
        }
        return normalized;
    }

    private double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static class H3TileData {
        String h3Index;
        Polygon geometry;
    }
}

