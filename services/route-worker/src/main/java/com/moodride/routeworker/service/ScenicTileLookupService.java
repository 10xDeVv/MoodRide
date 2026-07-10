package com.moodride.routeworker.service;

import com.moodride.datamodels.ScenicScoreTile;
import com.moodride.routeworker.cache.CacheKeySchema;
import com.moodride.routeworker.cache.CacheNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ScenicTileLookupService {

    private static final Logger logger = LoggerFactory.getLogger(ScenicTileLookupService.class);
    private static final int MAX_LOCAL_TILES = 25_000;

    private static final RowMapper<ScenicScoreTile> SCENIC_TILE_ROW_MAPPER = ScenicTileLookupService::mapScenicTile;

    private final JdbcTemplate jdbcTemplate;
    private final CacheManager cacheManager;
    private final Map<String, ScenicScoreTile> localTiles = new LinkedHashMap<>(1024, 0.75f, true);

    public ScenicTileLookupService(JdbcTemplate jdbcTemplate,
                                   CacheManager cacheManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.cacheManager = cacheManager;
    }

    @Transactional(readOnly = true)
    public List<ScenicScoreTile> findByH3Indexes(Collection<String> h3Indexes) {
        return new ArrayList<>(findMapByH3Indexes(h3Indexes).values());
    }

    @Transactional(readOnly = true)
    public Map<String, ScenicScoreTile> findMapByH3Indexes(Collection<String> h3Indexes) {
        List<String> orderedIndexes = normalizeIndexes(h3Indexes);
        if (orderedIndexes.isEmpty()) {
            return Map.of();
        }

        Map<String, ScenicScoreTile> found = new LinkedHashMap<>();
        List<String> cacheMisses = new ArrayList<>();
        Cache redisCache = scenicCache();

        for (String h3Index : orderedIndexes) {
            ScenicScoreTile localTile = getLocal(h3Index);
            if (localTile != null) {
                found.put(h3Index, localTile);
                continue;
            }

            ScenicScoreTile cachedTile = getRedis(redisCache, h3Index);
            if (cachedTile != null) {
                putLocal(h3Index, cachedTile);
                found.put(h3Index, cachedTile);
                continue;
            }

            cacheMisses.add(h3Index);
        }

        if (!cacheMisses.isEmpty()) {
            List<ScenicScoreTile> fetchedTiles = fetchTiles(cacheMisses);
            for (ScenicScoreTile tile : fetchedTiles) {
                if (tile == null || tile.getH3Index() == null) {
                    continue;
                }
                found.put(tile.getH3Index(), tile);
                putLocal(tile.getH3Index(), tile);
                putRedis(redisCache, tile.getH3Index(), tile);
            }
        }

        Map<String, ScenicScoreTile> orderedFound = new LinkedHashMap<>();
        for (String h3Index : orderedIndexes) {
            ScenicScoreTile tile = found.get(h3Index);
            if (tile != null) {
                orderedFound.put(h3Index, tile);
            }
        }
        return orderedFound;
    }

    public void evict(Collection<String> h3Indexes) {
        if (h3Indexes == null || h3Indexes.isEmpty()) {
            return;
        }
        Cache redisCache = scenicCache();
        for (String h3Index : normalizeIndexes(h3Indexes)) {
            evictLocal(h3Index);
            if (redisCache != null) {
                try {
                    redisCache.evict(CacheKeySchema.scenicTile(h3Index));
                } catch (RuntimeException ex) {
                    logger.debug("Failed to evict scenic tile {} from Redis cache", h3Index, ex);
                }
            }
        }
    }

    public void clearLocal() {
        synchronized (localTiles) {
            localTiles.clear();
        }
    }

    private Cache scenicCache() {
        try {
            return cacheManager.getCache(CacheNames.SCENIC_TILES);
        } catch (RuntimeException ex) {
            logger.debug("Scenic tile Redis cache unavailable", ex);
            return null;
        }
    }

    private ScenicScoreTile getRedis(Cache cache, String h3Index) {
        if (cache == null) {
            return null;
        }
        try {
            return cache.get(CacheKeySchema.scenicTile(h3Index), ScenicScoreTile.class);
        } catch (RuntimeException ex) {
            logger.debug("Failed to read scenic tile {} from Redis cache", h3Index, ex);
            return null;
        }
    }

    private void putRedis(Cache cache, String h3Index, ScenicScoreTile tile) {
        if (cache == null || tile == null) {
            return;
        }
        try {
            cache.put(CacheKeySchema.scenicTile(h3Index), tile);
        } catch (RuntimeException ex) {
            logger.debug("Failed to write scenic tile {} to Redis cache", h3Index, ex);
        }
    }

    private ScenicScoreTile getLocal(String h3Index) {
        synchronized (localTiles) {
            return localTiles.get(h3Index);
        }
    }

    private void putLocal(String h3Index, ScenicScoreTile tile) {
        synchronized (localTiles) {
            localTiles.put(h3Index, tile);
            while (localTiles.size() > MAX_LOCAL_TILES) {
                String eldest = localTiles.keySet().iterator().next();
                localTiles.remove(eldest);
            }
        }
    }

    private void evictLocal(String h3Index) {
        synchronized (localTiles) {
            localTiles.remove(h3Index);
        }
    }

    private List<ScenicScoreTile> fetchTiles(List<String> h3Indexes) {
        String placeholders = h3Indexes.stream()
            .map(ignored -> "?")
            .collect(Collectors.joining(","));
        String sql = """
            SELECT
                h3_index,
                scenic_score,
                water_proximity,
                elevation_variance,
                natural_land_use,
                road_density,
                traffic_signal_score,
                poi_density,
                visual_complexity,
                water_score,
                green_score,
                elevation_score,
                solitude_score,
                curve_score,
                poi_score,
                park_score,
                overture_poi_score,
                building_density_score,
                darkness_score,
                urban_penalty_score,
                road_stress_score,
                water_visibility_score,
                water_crossing_score,
                coastal_road_score,
                tree_canopy_score,
                scenic_poi_score,
                viewpoint_score,
                bridge_coastal_score,
                last_scored,
                scoring_version
            FROM scenic_score_tiles
            WHERE h3_index IN (%s)
            """.formatted(placeholders);
        return jdbcTemplate.query(sql, SCENIC_TILE_ROW_MAPPER, h3Indexes.toArray());
    }

    private static ScenicScoreTile mapScenicTile(ResultSet rs, int rowNum) throws SQLException {
        ScenicScoreTile tile = new ScenicScoreTile();
        tile.setH3Index(rs.getString("h3_index"));
        tile.setScenicScore(rs.getDouble("scenic_score"));
        tile.setWaterProximity(rs.getDouble("water_proximity"));
        tile.setElevationVariance(rs.getDouble("elevation_variance"));
        tile.setNaturalLandUse(rs.getDouble("natural_land_use"));
        tile.setRoadDensity(rs.getDouble("road_density"));
        tile.setTrafficSignalScore(rs.getDouble("traffic_signal_score"));
        tile.setPoiDensity(rs.getDouble("poi_density"));
        tile.setVisualComplexity(rs.getDouble("visual_complexity"));
        tile.setWaterScore(rs.getDouble("water_score"));
        tile.setGreenScore(rs.getDouble("green_score"));
        tile.setElevationScore(rs.getDouble("elevation_score"));
        tile.setSolitudeScore(rs.getDouble("solitude_score"));
        tile.setCurveScore(rs.getDouble("curve_score"));
        tile.setPoiScore(rs.getDouble("poi_score"));
        tile.setParkScore(rs.getDouble("park_score"));
        tile.setOverturePoiScore(rs.getDouble("overture_poi_score"));
        tile.setBuildingDensityScore(rs.getDouble("building_density_score"));
        tile.setDarknessScore(rs.getDouble("darkness_score"));
        tile.setUrbanPenaltyScore(rs.getDouble("urban_penalty_score"));
        tile.setRoadStressScore(rs.getDouble("road_stress_score"));
        tile.setWaterVisibilityScore(rs.getDouble("water_visibility_score"));
        tile.setWaterCrossingScore(rs.getDouble("water_crossing_score"));
        tile.setCoastalRoadScore(rs.getDouble("coastal_road_score"));
        tile.setTreeCanopyScore(rs.getDouble("tree_canopy_score"));
        tile.setScenicPoiScore(rs.getDouble("scenic_poi_score"));
        tile.setViewpointScore(rs.getDouble("viewpoint_score"));
        tile.setBridgeCoastalScore(rs.getDouble("bridge_coastal_score"));
        Timestamp lastScored = rs.getTimestamp("last_scored");
        if (lastScored != null) {
            tile.setLastScored(lastScored.toInstant());
        }
        tile.setScoringVersion(rs.getString("scoring_version"));
        return tile;
    }

    private List<String> normalizeIndexes(Collection<String> h3Indexes) {
        if (h3Indexes == null || h3Indexes.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String h3Index : h3Indexes) {
            if (h3Index == null) {
                continue;
            }
            String trimmed = h3Index.trim();
            if (!trimmed.isBlank()) {
                normalized.add(trimmed);
            }
        }
        return List.copyOf(normalized);
    }
}
