package com.moodride.routeapi.cache;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.moodride.datamodels.RoadSegment;
import com.moodride.datamodels.Route;
import com.moodride.datamodels.ScenicScoreTile;
import com.moodride.routeapi.repository.RoadSegmentRepository;
import com.moodride.routeapi.repository.RouteRepository;
import com.moodride.routeapi.repository.ScenicScoreTileRepository;
import com.moodride.routeapi.service.RouteService;

@Service
public class CacheWarmupService {

    private static final Logger logger = LoggerFactory.getLogger(CacheWarmupService.class);

    private final CacheManager cacheManager;
    private final ScenicScoreTileRepository scenicScoreTileRepository;
    private final RoadSegmentRepository roadSegmentRepository;
    private final RouteRepository routeRepository;
    private final RouteService routeService;
    private final CacheMetricsService cacheMetricsService;

    public CacheWarmupService(
            CacheManager cacheManager,
            ScenicScoreTileRepository scenicScoreTileRepository,
            RoadSegmentRepository roadSegmentRepository,
            RouteRepository routeRepository,
            RouteService routeService,
            CacheMetricsService cacheMetricsService
    ) {
        this.cacheManager = cacheManager;
        this.scenicScoreTileRepository = scenicScoreTileRepository;
        this.roadSegmentRepository = roadSegmentRepository;
        this.routeRepository = routeRepository;
        this.routeService = routeService;
        this.cacheMetricsService = cacheMetricsService;
    }

    public WarmupReport warmAll(int limit) {
        int sanitizedLimit = Math.max(1, Math.min(limit, 1000));
        int scenicCount = warmScenicTiles(sanitizedLimit);
        int segmentCount = warmSegmentMetadata(sanitizedLimit);
        int popularityCount = warmRegionalPopularity(sanitizedLimit);
        int routeCount = warmRouteResults(Math.min(sanitizedLimit, 250));
        return new WarmupReport(scenicCount, segmentCount, popularityCount, routeCount);
    }

    public int warmScenicTiles(int limit) {
        Cache cache = cacheManager.getCache(CacheNames.SCENIC_TILES);
        if (cache == null) {
            return 0;
        }
        try {
            List<ScenicScoreTile> tiles = scenicScoreTileRepository.findTopByScenicScore(limit);
            tiles.forEach(tile -> cache.put(CacheKeySchema.scenicTile(tile.getH3Index()), tile));
            cacheMetricsService.warmSuccess(CacheNames.SCENIC_TILES, tiles.size());
            return tiles.size();
        } catch (Exception ex) {
            cacheMetricsService.warmFailure(CacheNames.SCENIC_TILES);
            logger.warn("Scenic tile warmup failed: {}", ex.getMessage());
            return 0;
        }
    }

    public int warmSegmentMetadata(int limit) {
        Cache cache = cacheManager.getCache(CacheNames.ROAD_SEGMENTS);
        if (cache == null) {
            return 0;
        }
        try {
            List<RoadSegment> segments = roadSegmentRepository.findAll(PageRequest.of(0, limit)).getContent();
            segments.stream()
                    .collect(Collectors.groupingBy(RoadSegment::getH3TileIndex))
                    .forEach((h3, grouped) -> cache.put(CacheKeySchema.segmentMeta(h3), grouped));
            int warmed = (int) segments.stream().map(RoadSegment::getH3TileIndex).distinct().count();
            cacheMetricsService.warmSuccess(CacheNames.ROAD_SEGMENTS, warmed);
            return warmed;
        } catch (Exception ex) {
            cacheMetricsService.warmFailure(CacheNames.ROAD_SEGMENTS);
            logger.warn("Segment metadata warmup failed: {}", ex.getMessage());
            return 0;
        }
    }

    public int warmRegionalPopularity(int limit) {
        Cache cache = cacheManager.getCache(CacheNames.REGIONAL_POPULARITY);
        if (cache == null) {
            return 0;
        }
        try {
            Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
            Map<String, RegionAccumulator> byRegion = new HashMap<>();

            List<Route> recentRoutes = routeRepository.findTop1000ByOrderByGeneratedAtDesc();
            for (Route route : recentRoutes) {
                if (route.getGeneratedAt() == null || route.getGeneratedAt().isBefore(cutoff)) {
                    continue;
                }
                String regionKey = toRegionKey(route.getGeometry());
                if (regionKey == null) {
                    continue;
                }
                byRegion.computeIfAbsent(regionKey, ignored -> new RegionAccumulator()).add(route.getScenicScore());
            }

            List<Map.Entry<String, RegionAccumulator>> ranked = byRegion.entrySet().stream()
                    .sorted(Comparator.comparingDouble((Map.Entry<String, RegionAccumulator> entry) -> entry.getValue().rankScore()).reversed())
                    .limit(limit)
                    .toList();

            ranked.forEach(region -> cache.put(
                    CacheKeySchema.regionalPopularity(region.getKey()),
                    new RegionalPopularityEntry(
                            region.getKey(),
                            region.getValue().avgScenicScore(),
                            region.getValue().count,
                            region.getValue().rankScore()
                    )
            ));

            cacheMetricsService.warmSuccess(CacheNames.REGIONAL_POPULARITY, ranked.size());
            return ranked.size();
        } catch (Exception ex) {
            cacheMetricsService.warmFailure(CacheNames.REGIONAL_POPULARITY);
            logger.warn("Regional popularity warmup failed: {}", ex.getMessage());
            return 0;
        }
    }

    public int warmRouteResults(int limit) {
        try {
            List<Route> routes = routeRepository.findAll(PageRequest.of(0, limit)).getContent();
            for (Route route : routes) {
                routeService.getRoute(route.getId());
            }
            cacheMetricsService.warmSuccess(CacheNames.ROUTE_RESULTS, routes.size());
            return routes.size();
        } catch (Exception ex) {
            cacheMetricsService.warmFailure(CacheNames.ROUTE_RESULTS);
            logger.warn("Route cache warmup failed: {}", ex.getMessage());
            return 0;
        }
    }

    public record WarmupReport(int scenicTiles, int segmentMetadata, int regionalPopularity, int routeResults) {
    }

    public record RegionalPopularityEntry(String regionKey, double avgScenicScore, long routeCount, double rankScore) {
    }

    private String toRegionKey(LineString geometry) {
        if (geometry == null || geometry.isEmpty() || geometry.getNumPoints() == 0) {
            return null;
        }
        Coordinate first = geometry.getCoordinateN(0);
        int latBucket = (int) Math.floor(first.getY() * 10.0);
        int lngBucket = (int) Math.floor(first.getX() * 10.0);
        return "r3:" + latBucket + ":" + lngBucket;
    }

    private static final class RegionAccumulator {
        private double scenicSum;
        private long count;

        private void add(double scenicScore) {
            scenicSum += scenicScore;
            count++;
        }

        private double avgScenicScore() {
            return count == 0 ? 0.0 : scenicSum / count;
        }

        private double rankScore() {
            return avgScenicScore() * Math.log(count + 1.0);
        }
    }
}

