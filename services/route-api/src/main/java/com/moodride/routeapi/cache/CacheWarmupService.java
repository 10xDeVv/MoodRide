package com.moodride.routeapi.cache;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.moodride.datamodels.Route;
import com.moodride.datamodels.RouteJob;
import com.moodride.datamodels.ScenicScoreTile;
import com.moodride.routeapi.config.ScenicCacheConfiguration;
import com.moodride.routeapi.dto.RouteDetailResponse;
import com.moodride.routeapi.repository.RouteJobRepository;
import com.moodride.routeapi.repository.RouteRepository;
import com.moodride.routeapi.repository.ScenicScoreTileRepository;
import com.moodride.routeapi.service.RouteService;

@Service
public class CacheWarmupService {

    private static final Logger logger = LoggerFactory.getLogger(CacheWarmupService.class);

    private final CacheManager cacheManager;
    private final ScenicScoreTileRepository scenicScoreTileRepository;
    private final RouteRepository routeRepository;
    private final RouteJobRepository routeJobRepository;
    private final RouteService routeService;
    private final CacheMetricsService cacheMetricsService;
    private final String scenicScoringVersion;

    public CacheWarmupService(
            CacheManager cacheManager,
            ScenicScoreTileRepository scenicScoreTileRepository,
            RouteRepository routeRepository,
            RouteJobRepository routeJobRepository,
            RouteService routeService,
            CacheMetricsService cacheMetricsService,
            ScenicCacheConfiguration scenicCacheConfiguration
    ) {
        this.cacheManager = cacheManager;
        this.scenicScoreTileRepository = scenicScoreTileRepository;
        this.routeRepository = routeRepository;
        this.routeJobRepository = routeJobRepository;
        this.routeService = routeService;
        this.cacheMetricsService = cacheMetricsService;
        this.scenicScoringVersion = scenicCacheConfiguration.getScenicScoringVersion();
    }

    public WarmupReport warmAll(int limit) {
        int sanitizedLimit = Math.max(1, Math.min(limit, 1000));
        int scenicCount = warmScenicTiles(sanitizedLimit);
        int routeDetailCount = warmRouteDetails(Math.min(sanitizedLimit, 250));
        return new WarmupReport(scenicCount, 0, 0, routeDetailCount);
    }

    public int warmScenicTiles(int limit) {
        Cache cache = cacheManager.getCache(CacheNames.SCENIC_TILES);
        if (cache == null) {
            return 0;
        }
        try {
            List<ScenicScoreTile> tiles = scenicScoreTileRepository.findTopByScenicScore(
                scenicScoringVersion,
                limit
            );
            int warmed = 0;
            for (ScenicScoreTile tile : tiles) {
                if (tile == null
                    || tile.getH3Index() == null
                    || !scenicScoringVersion.equals(tile.getScoringVersion())) {
                    continue;
                }
                cache.put(
                    CacheKeySchema.scenicTile(scenicScoringVersion, tile.getH3Index()),
                    tile.detachedCacheValue()
                );
                warmed++;
            }
            cacheMetricsService.warmSuccess(CacheNames.SCENIC_TILES, warmed);
            return warmed;
        } catch (Exception ex) {
            cacheMetricsService.warmFailure(CacheNames.SCENIC_TILES);
            logger.warn("Scenic tile warmup failed: {}", ex.getMessage());
            return 0;
        }
    }

    public int warmRouteDetails(int limit) {
        try {
            List<Route> routes = routeRepository.findAll(PageRequest.of(0, limit)).getContent();
            Set<UUID> routeJobIds = routes.stream()
                    .map(Route::getJobId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            Set<UUID> completeJobIds = routeJobRepository.findAllById(routeJobIds).stream()
                    .filter(RouteJob::isOptionsComplete)
                    .map(RouteJob::getId)
                    .collect(Collectors.toSet());

            int warmed = 0;
            for (Route route : routes) {
                if (!completeJobIds.contains(route.getJobId())) {
                    continue;
                }
                RouteDetailResponse detail = routeService.getRoute(route.getId());
                if (detail.optionsComplete() && detail.routeOptions().size() == detail.optionCount()) {
                    warmed++;
                }
            }
            cacheMetricsService.warmSuccess(CacheNames.ROUTE_DETAILS_V2, warmed);
            return warmed;
        } catch (Exception ex) {
            cacheMetricsService.warmFailure(CacheNames.ROUTE_DETAILS_V2);
            logger.warn("Rich route detail cache warmup failed: {}", ex.getMessage());
            return 0;
        }
    }

    public record WarmupReport(int scenicTiles, int segmentMetadata, int regionalPopularity, int routeDetailsV2) {
    }

}

