package com.moodride.routeworker.service;

import com.moodride.datamodels.RoadSegment;
import com.moodride.datamodels.ScenicScoreTile;
import com.moodride.routeworker.cache.CacheKeySchema;
import com.moodride.routeworker.cache.CacheNames;
import com.moodride.routeworker.config.ScenicCacheConfiguration;
import com.moodride.routeworker.graph.RoadNode;
import com.moodride.routeworker.repository.RoadSegmentRepository;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

@Service
public class RoadSegmentAnchorService {

    private static final Logger logger = LoggerFactory.getLogger(RoadSegmentAnchorService.class);
    private static final int MAX_LOCAL_ANCHORS = 25_000;
    private static final int MAX_LOCAL_FALLBACKS = 25_000;
    private static final long FALLBACK_CACHE_TTL_NANOS = Duration.ofSeconds(30).toNanos();
    private static final int ANCHOR_SEARCH_LIMIT = 24;
    private static final double ANCHOR_SEARCH_RADIUS_METERS = 900.0;

    private final RoadSegmentRepository roadSegmentRepository;
    private final CacheManager cacheManager;
    private final RouteGenerationMetricsService routeGenerationMetricsService;
    private final String scenicScoringVersion;
    private final String roadDatasetRevision;
    private final String anchorCacheSchema;
    private final Map<String, RoadAnchor> localAnchors = new LinkedHashMap<>(1024, 0.75f, true);
    private final Map<String, FallbackCacheEntry> localFallbacks = new LinkedHashMap<>(1024, 0.75f, true);
    private final LongSupplier nanoTime;

    @Autowired
    public RoadSegmentAnchorService(
        RoadSegmentRepository roadSegmentRepository,
        CacheManager cacheManager,
        RouteGenerationMetricsService routeGenerationMetricsService,
        ScenicCacheConfiguration cacheConfiguration
    ) {
        this(
            roadSegmentRepository,
            cacheManager,
            routeGenerationMetricsService,
            cacheConfiguration,
            System::nanoTime
        );
    }

    RoadSegmentAnchorService(
        RoadSegmentRepository roadSegmentRepository,
        CacheManager cacheManager,
        RouteGenerationMetricsService routeGenerationMetricsService,
        ScenicCacheConfiguration cacheConfiguration,
        LongSupplier nanoTime
    ) {
        this.roadSegmentRepository = roadSegmentRepository;
        this.cacheManager = cacheManager;
        this.routeGenerationMetricsService = routeGenerationMetricsService;
        this.scenicScoringVersion = cacheConfiguration.getScenicScoringVersion();
        this.roadDatasetRevision = cacheConfiguration.getRoadDatasetRevision();
        this.anchorCacheSchema = cacheConfiguration.getRoadAnchorCacheSchema();
        this.nanoTime = nanoTime;
    }

    @Transactional(readOnly = true)
    public RoadNode anchorFor(ScenicScoreTile tile, RoadNode fallbackCenter) {
        long startedNanos = System.nanoTime();
        if (tile == null
            || tile.getH3Index() == null
            || fallbackCenter == null
            || !scenicScoringVersion.equals(tile.getScoringVersion())) {
            recordLookup("fallback_invalid", startedNanos);
            return fallbackCenter;
        }

        String h3Index = tile.getH3Index();
        String cacheKey = cacheKey(h3Index);
        RoadAnchor localAnchor = getLocal(cacheKey);
        if (localAnchor != null) {
            recordLookup("local", startedNanos);
            return localAnchor.toRoadNode();
        }

        Cache redisCache = roadSegmentCache();
        RoadAnchor cachedAnchor = getRedis(redisCache, cacheKey);
        if (cachedAnchor != null) {
            putLocal(cacheKey, cachedAnchor);
            recordLookup("redis", startedNanos);
            return cachedAnchor.toRoadNode();
        }

        FallbackCacheEntry cachedFallback = getFallback(cacheKey);
        if (cachedFallback != null) {
            recordLookup(cachedFallback.source(), startedNanos);
            return fallbackCenter;
        }

        RoadAnchorResolution resolution = findBestAnchor(tile, fallbackCenter);
        if ("postgres".equals(resolution.source())) {
            putLocal(cacheKey, resolution.anchor());
            putRedis(redisCache, cacheKey, resolution.anchor());
        } else {
            putFallback(cacheKey, resolution.source());
        }
        recordLookup(resolution.source(), startedNanos);
        return resolution.anchor().toRoadNode();
    }

    public void evict(List<String> h3Indexes) {
        if (h3Indexes == null || h3Indexes.isEmpty()) {
            return;
        }
        Cache redisCache = roadSegmentCache();
        for (String h3Index : h3Indexes) {
            if (h3Index == null || h3Index.isBlank()) {
                continue;
            }
            String cacheKey = cacheKey(h3Index);
            synchronized (localAnchors) {
                localAnchors.remove(cacheKey);
                localFallbacks.remove(cacheKey);
            }
            if (redisCache != null) {
                try {
                    redisCache.evict(cacheKey);
                } catch (RuntimeException ex) {
                    logger.debug("Failed to evict road anchor {} from Redis cache", h3Index, ex);
                }
            }
        }
    }

    public void clearLocal() {
        synchronized (localAnchors) {
            localAnchors.clear();
            localFallbacks.clear();
        }
    }

    private RoadAnchorResolution findBestAnchor(ScenicScoreTile tile, RoadNode fallbackCenter) {
        List<RoadSegment> candidates;
        try {
            candidates = roadSegmentRepository.findAnchorCandidatesNear(
                fallbackCenter.getLatitude(),
                fallbackCenter.getLongitude(),
                ANCHOR_SEARCH_RADIUS_METERS,
                ANCHOR_SEARCH_LIMIT
            );
        } catch (RuntimeException ex) {
            logger.debug("Failed to fetch road anchor candidates for scenic tile {}", tile.getH3Index(), ex);
            return new RoadAnchorResolution(RoadAnchor.from(fallbackCenter), "fallback_error");
        }

        return candidates.stream()
            .filter(segment -> segment.getGeometry() != null && !segment.getGeometry().isEmpty())
            .max((left, right) -> Double.compare(anchorScore(left, tile), anchorScore(right, tile)))
            .map(this::midpointAnchor)
            .map(anchor -> new RoadAnchorResolution(anchor, "postgres"))
            .orElseGet(() -> new RoadAnchorResolution(RoadAnchor.from(fallbackCenter), "fallback_empty"));
    }

    private double anchorScore(RoadSegment segment, ScenicScoreTile tile) {
        double roadClass = roadClassComfort(segment.getRoadType());
        double speedComfort = speedComfort(segment.getSpeedLimitKmh());
        double segmentShape = clamp01((segment.getCurvature() * 0.65)
            + (Math.min(Math.abs(segment.getElevationChange()), 80.0) / 80.0 * 0.20)
            + (Math.min(segment.getLengthMeters(), 1200.0) / 1200.0 * 0.15));
        double lowStressTile = 1.0 - clamp01(tile.getRoadStressScore());
        double scenicContext = clamp01(
            tile.getScenicScore() * 0.35
                + tile.getWaterVisibilityScore() * 0.18
                + tile.getGreenScore() * 0.16
                + tile.getSolitudeScore() * 0.16
                + tile.getCurveScore() * 0.15
        );

        return roadClass * 0.28
            + speedComfort * 0.18
            + lowStressTile * 0.20
            + segmentShape * 0.17
            + scenicContext * 0.17;
    }

    private double roadClassComfort(String roadType) {
        String normalized = roadType == null ? "unknown" : roadType.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "residential", "living_street", "unclassified", "road" -> 0.95;
            case "tertiary", "tertiary_link" -> 0.88;
            case "secondary", "secondary_link" -> 0.76;
            case "primary", "primary_link" -> 0.48;
            case "trunk", "trunk_link", "motorway", "motorway_link" -> 0.12;
            case "service" -> 0.42;
            default -> 0.65;
        };
    }

    private double speedComfort(int speedLimitKmh) {
        if (speedLimitKmh <= 0) {
            return 0.62;
        }
        if (speedLimitKmh <= 40) {
            return 0.88;
        }
        if (speedLimitKmh <= 60) {
            return 0.74;
        }
        if (speedLimitKmh <= 80) {
            return 0.48;
        }
        return 0.20;
    }

    private RoadAnchor midpointAnchor(RoadSegment segment) {
        LineString geometry = segment.getGeometry();
        Coordinate coordinate = geometry.getCoordinateN(Math.max(0, geometry.getNumPoints() / 2));
        return new RoadAnchor(coordinate.y, coordinate.x);
    }

    private Cache roadSegmentCache() {
        try {
            return cacheManager.getCache(CacheNames.ROAD_SEGMENTS);
        } catch (RuntimeException ex) {
            logger.debug("Road segment Redis cache unavailable", ex);
            return null;
        }
    }

    private RoadAnchor getRedis(Cache cache, String cacheKey) {
        if (cache == null) {
            return null;
        }
        try {
            return cache.get(cacheKey, RoadAnchor.class);
        } catch (RuntimeException ex) {
            logger.debug("Failed to read road anchor {} from Redis cache", cacheKey, ex);
            return null;
        }
    }

    private void putRedis(Cache cache, String cacheKey, RoadAnchor anchor) {
        if (cache == null || anchor == null) {
            return;
        }
        try {
            cache.put(cacheKey, anchor);
        } catch (RuntimeException ex) {
            logger.debug("Failed to write road anchor {} to Redis cache", cacheKey, ex);
        }
    }

    private String cacheKey(String h3Index) {
        return CacheKeySchema.roadAnchor(
            scenicScoringVersion,
            roadDatasetRevision,
            anchorCacheSchema,
            h3Index
        );
    }

    private RoadAnchor getLocal(String cacheKey) {
        synchronized (localAnchors) {
            return localAnchors.get(cacheKey);
        }
    }

    private void putLocal(String cacheKey, RoadAnchor anchor) {
        synchronized (localAnchors) {
            localFallbacks.remove(cacheKey);
            localAnchors.put(cacheKey, anchor);
            while (localAnchors.size() > MAX_LOCAL_ANCHORS) {
                String eldest = localAnchors.keySet().iterator().next();
                localAnchors.remove(eldest);
            }
        }
    }

    private FallbackCacheEntry getFallback(String cacheKey) {
        synchronized (localAnchors) {
            FallbackCacheEntry fallback = localFallbacks.get(cacheKey);
            if (fallback == null) {
                return null;
            }
            if (fallback.isExpired(nanoTime.getAsLong())) {
                localFallbacks.remove(cacheKey);
                return null;
            }
            return fallback;
        }
    }

    private void putFallback(String cacheKey, String source) {
        synchronized (localAnchors) {
            if (localAnchors.containsKey(cacheKey)) {
                localFallbacks.remove(cacheKey);
                return;
            }
            localFallbacks.put(
                cacheKey,
                new FallbackCacheEntry(source, nanoTime.getAsLong() + FALLBACK_CACHE_TTL_NANOS)
            );
            while (localFallbacks.size() > MAX_LOCAL_FALLBACKS) {
                String eldest = localFallbacks.keySet().iterator().next();
                localFallbacks.remove(eldest);
            }
        }
    }

    private void recordLookup(String source, long startedNanos) {
        routeGenerationMetricsService.recordRoadAnchorLookup(source, elapsedMillis(startedNanos));
    }

    private long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private record FallbackCacheEntry(String source, long expiresAtNanos) {
        private boolean isExpired(long nowNanos) {
            return nowNanos - expiresAtNanos >= 0;
        }
    }

    private record RoadAnchorResolution(RoadAnchor anchor, String source) {
    }

    public record RoadAnchor(double latitude, double longitude) implements Serializable {
        private static RoadAnchor from(RoadNode node) {
            return new RoadAnchor(node.getLatitude(), node.getLongitude());
        }

        private RoadNode toRoadNode() {
            return new RoadNode(latitude, longitude);
        }
    }
}
