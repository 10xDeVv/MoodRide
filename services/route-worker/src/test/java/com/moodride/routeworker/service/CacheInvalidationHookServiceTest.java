package com.moodride.routeworker.service;

import com.moodride.routeworker.cache.CacheKeySchema;
import com.moodride.routeworker.cache.CacheNames;
import com.moodride.routeworker.config.ScenicCacheConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CacheInvalidationHookServiceTest {

    private static final String SCENIC_VERSION = "scenic-v2";
    private static final String ROAD_REVISION = "roads-v2";
    private static final String ANCHOR_SCHEMA = "v1";

    @Mock
    private CacheManager cacheManager;
    @Mock
    private Cache scenicCache;
    @Mock
    private Cache roadSegmentCache;
    @Mock
    private GraphService graphService;
    @Mock
    private ScenicTileLookupService scenicTileLookupService;
    @Mock
    private RoadSegmentAnchorService roadSegmentAnchorService;
    @Mock
    private WorkerCacheMetricsService metricsService;

    @Test
    void invalidatesTheConfiguredScenicAndCompositeAnchorNamespaces() {
        ScenicCacheConfiguration configuration = new ScenicCacheConfiguration();
        configuration.setScenicScoringVersion(SCENIC_VERSION);
        configuration.setRoadDatasetRevision(ROAD_REVISION);
        configuration.setRoadAnchorCacheSchema(ANCHOR_SCHEMA);
        when(cacheManager.getCache(CacheNames.SCENIC_TILES)).thenReturn(scenicCache);
        when(cacheManager.getCache(CacheNames.ROAD_SEGMENTS)).thenReturn(roadSegmentCache);
        CacheInvalidationHookService service = new CacheInvalidationHookService(
            cacheManager,
            graphService,
            scenicTileLookupService,
            roadSegmentAnchorService,
            metricsService,
            configuration
        );

        service.invalidateTile("h3-active");

        verify(scenicCache).evict(CacheKeySchema.scenicTile(SCENIC_VERSION, "h3-active"));
        verify(roadSegmentCache).evict(CacheKeySchema.segmentMeta("h3-active"));
        verify(roadSegmentCache).evict(CacheKeySchema.roadAnchor(
            SCENIC_VERSION,
            ROAD_REVISION,
            ANCHOR_SCHEMA,
            "h3-active"
        ));
        verify(scenicTileLookupService).evict(List.of("h3-active"));
        verify(roadSegmentAnchorService).evict(List.of("h3-active"));
        verify(graphService).invalidateCache();
        verify(metricsService).invalidate(CacheNames.SCENIC_TILES, 1);
    }
}
