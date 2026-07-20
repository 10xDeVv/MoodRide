package com.moodride.routeworker.service;

import com.moodride.routeworker.cache.CacheKeySchema;
import com.moodride.routeworker.cache.CacheNames;
import com.moodride.routeworker.config.ScenicCacheConfiguration;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CacheInvalidationHookService {

    private final CacheManager cacheManager;
    private final GraphService graphService;
    private final ScenicTileLookupService scenicTileLookupService;
    private final RoadSegmentAnchorService roadSegmentAnchorService;
    private final WorkerCacheMetricsService metricsService;
    private final String scenicScoringVersion;
    private final String roadDatasetRevision;
    private final String anchorCacheSchema;

    public CacheInvalidationHookService(
            CacheManager cacheManager,
            GraphService graphService,
            ScenicTileLookupService scenicTileLookupService,
            RoadSegmentAnchorService roadSegmentAnchorService,
            WorkerCacheMetricsService metricsService,
            ScenicCacheConfiguration cacheConfiguration
    ) {
        this.cacheManager = cacheManager;
        this.graphService = graphService;
        this.scenicTileLookupService = scenicTileLookupService;
        this.roadSegmentAnchorService = roadSegmentAnchorService;
        this.metricsService = metricsService;
        this.scenicScoringVersion = cacheConfiguration.getScenicScoringVersion();
        this.roadDatasetRevision = cacheConfiguration.getRoadDatasetRevision();
        this.anchorCacheSchema = cacheConfiguration.getRoadAnchorCacheSchema();
    }

    public void invalidateTiles(List<String> h3Indexes) {
        Cache scenic = cacheManager.getCache(CacheNames.SCENIC_TILES);
        Cache segments = cacheManager.getCache(CacheNames.ROAD_SEGMENTS);
        int count = 0;
        if (h3Indexes != null) {
            for (String h3 : h3Indexes) {
                if (scenic != null) {
                    scenic.evict(CacheKeySchema.scenicTile(scenicScoringVersion, h3));
                }
                if (segments != null) {
                    segments.evict(CacheKeySchema.segmentMeta(h3));
                    segments.evict(CacheKeySchema.roadAnchor(
                        scenicScoringVersion,
                        roadDatasetRevision,
                        anchorCacheSchema,
                        h3
                    ));
                }
                count++;
            }
            scenicTileLookupService.evict(h3Indexes);
            roadSegmentAnchorService.evict(h3Indexes);
        }
        graphService.invalidateCache();
        metricsService.invalidate(CacheNames.SCENIC_TILES, count);
    }

    public void invalidateTile(String h3Index) {
        invalidateTiles(List.of(h3Index));
    }
}

