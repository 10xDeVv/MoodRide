package com.moodride.routeworker.service;

import com.moodride.datamodels.ScenicScoreTile;
import com.moodride.routeworker.cache.CacheKeySchema;
import com.moodride.routeworker.cache.CacheNames;
import com.moodride.routeworker.cache.CachePolicy;
import com.moodride.routeworker.config.ScenicCacheConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
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

    private static final String REDIS_KEY_PREFIX = CacheNames.SCENIC_TILES + "::";

    private final JdbcTemplate jdbcTemplate;
    private final RedisTemplate<String, ScenicScoreTile> redisTemplate;
    private final String scoringVersion;
    private final Map<String, ScenicScoreTile> localTiles = new LinkedHashMap<>(1024, 0.75f, true);

    public ScenicTileLookupService(
        JdbcTemplate jdbcTemplate,
        @Qualifier("scenicTileRedisTemplate") RedisTemplate<String, ScenicScoreTile> redisTemplate,
        ScenicCacheConfiguration cacheConfiguration
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.scoringVersion = cacheConfiguration.getScenicScoringVersion();
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

        Map<String, ScenicScoreTile> orderedFound = new LinkedHashMap<>(orderedIndexes.size());
        List<String> cacheMisses = new ArrayList<>();
        for (String h3Index : orderedIndexes) {
            ScenicScoreTile localTile = getLocal(h3Index);
            orderedFound.put(h3Index, localTile);
            if (localTile == null) {
                cacheMisses.add(h3Index);
            }
        }

        if (!cacheMisses.isEmpty()) {
            List<String> redisMisses = getRedis(cacheMisses, orderedFound);
            if (!redisMisses.isEmpty()) {
                List<ScenicScoreTile> fetchedTiles = fetchTiles(redisMisses);
                boolean hasCacheWrites = false;
                for (ScenicScoreTile tile : fetchedTiles) {
                    if (!isActiveTile(tile)
                        || tile.getH3Index() == null
                        || !orderedFound.containsKey(tile.getH3Index())) {
                        continue;
                    }
                    orderedFound.put(tile.getH3Index(), tile);
                    putLocal(tile.getH3Index(), tile);
                    hasCacheWrites = true;
                }
                if (hasCacheWrites) {
                    putRedis(fetchedTiles, orderedFound.keySet());
                }
            }
        }

        orderedFound.values().removeIf(tile -> tile == null);
        return orderedFound;
    }

    public void evict(Collection<String> h3Indexes) {
        List<String> normalizedIndexes = normalizeIndexes(h3Indexes);
        if (normalizedIndexes.isEmpty()) {
            return;
        }

        for (String h3Index : normalizedIndexes) {
            evictLocal(h3Index);
        }

        try {
            redisTemplate.delete(redisKeys(normalizedIndexes));
        } catch (RuntimeException ex) {
            logger.debug("Failed to bulk-evict {} scenic tiles from Redis", normalizedIndexes.size(), ex);
        }
    }

    public void clearLocal() {
        synchronized (localTiles) {
            localTiles.clear();
        }
    }

    private List<String> getRedis(
        List<String> h3Indexes,
        Map<String, ScenicScoreTile> orderedFound
    ) {
        List<String> keys = redisKeys(h3Indexes);
        final List<?> cachedValues;
        try {
            cachedValues = redisTemplate.opsForValue().multiGet(keys);
        } catch (RuntimeException ex) {
            logger.debug("Failed to bulk-read {} scenic tiles from Redis", h3Indexes.size(), ex);
            return h3Indexes;
        }

        if (cachedValues == null || cachedValues.size() != h3Indexes.size()) {
            logger.debug(
                "Discarding misaligned scenic tile Redis response: expected {}, received {}",
                h3Indexes.size(),
                cachedValues == null ? null : cachedValues.size()
            );
            return h3Indexes;
        }

        List<String> cacheMisses = new ArrayList<>();
        for (int index = 0; index < h3Indexes.size(); index++) {
            String h3Index = h3Indexes.get(index);
            Object cachedValue = cachedValues.get(index);
            if (!(cachedValue instanceof ScenicScoreTile tile)
                || !h3Index.equals(tile.getH3Index())
                || !isActiveTile(tile)) {
                cacheMisses.add(h3Index);
                continue;
            }
            orderedFound.put(h3Index, tile);
            putLocal(h3Index, tile);
        }
        return cacheMisses;
    }

    private void putRedis(
        List<ScenicScoreTile> tiles,
        Collection<String> allowedIndexes
    ) {
        try {
            redisTemplate.executePipelined(new SessionCallback<Object>() {
                @Override
                @SuppressWarnings("unchecked")
                public <K, V> Object execute(RedisOperations<K, V> operations) {
                    RedisOperations<String, ScenicScoreTile> tileOperations =
                        (RedisOperations<String, ScenicScoreTile>) (RedisOperations<?, ?>) operations;
                    for (ScenicScoreTile tile : tiles) {
                        if (isActiveTile(tile)
                            && tile.getH3Index() != null
                            && allowedIndexes.contains(tile.getH3Index())) {
                            tileOperations.opsForValue().set(
                                redisKey(tile.getH3Index()),
                                tile,
                                CachePolicy.SCENIC_TILES_TTL
                            );
                        }
                    }
                    return null;
                }
            });
        } catch (RuntimeException ex) {
            logger.debug("Failed to bulk-write scenic tiles to Redis", ex);
        }
    }

    private List<String> redisKeys(List<String> h3Indexes) {
        List<String> keys = new ArrayList<>(h3Indexes.size());
        for (String h3Index : h3Indexes) {
            keys.add(redisKey(h3Index));
        }
        return keys;
    }

    private String redisKey(String h3Index) {
        return REDIS_KEY_PREFIX + CacheKeySchema.scenicTile(scoringVersion, h3Index);
    }

    private ScenicScoreTile getLocal(String h3Index) {
        synchronized (localTiles) {
            ScenicScoreTile tile = localTiles.get(h3Index);
            if (tile != null && !isActiveTile(tile)) {
                localTiles.remove(h3Index);
                return null;
            }
            return tile;
        }
    }

    private boolean isActiveTile(ScenicScoreTile tile) {
        return tile != null && scoringVersion.equals(tile.getScoringVersion());
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
              AND scoring_version = ?
            """.formatted(placeholders);
        Object[] parameters = new Object[h3Indexes.size() + 1];
        for (int index = 0; index < h3Indexes.size(); index++) {
            parameters[index] = h3Indexes.get(index);
        }
        parameters[h3Indexes.size()] = scoringVersion;
        return jdbcTemplate.query(sql, SCENIC_TILE_ROW_MAPPER, parameters);
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
