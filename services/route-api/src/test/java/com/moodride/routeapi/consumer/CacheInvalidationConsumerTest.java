package com.moodride.routeapi.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moodride.eventmodels.ScenicTilesRefreshedEvent;
import com.moodride.routeapi.cache.CacheKeySchema;
import com.moodride.routeapi.cache.CacheMetricsService;
import com.moodride.routeapi.cache.CacheNames;
import com.moodride.routeapi.config.ScenicCacheConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CacheInvalidationConsumerTest {

    private static final String SCORING_VERSION = "scenic-v2";

    @Mock
    private CacheManager cacheManager;
    @Mock
    private Cache scenicCache;
    @Mock
    private Cache popularityCache;
    @Mock
    private CacheMetricsService metricsService;

    @Test
    void scenicRefreshInvalidatesOnlyTheConfiguredReleaseNamespace() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ScenicCacheConfiguration configuration = new ScenicCacheConfiguration();
        configuration.setScenicScoringVersion(SCORING_VERSION);
        when(cacheManager.getCache(CacheNames.SCENIC_TILES)).thenReturn(scenicCache);
        when(cacheManager.getCache(CacheNames.REGIONAL_POPULARITY)).thenReturn(popularityCache);
        CacheInvalidationConsumer consumer = new CacheInvalidationConsumer(
            cacheManager,
            objectMapper,
            metricsService,
            configuration
        );
        ScenicTilesRefreshedEvent event = new ScenicTilesRefreshedEvent(
            "event-1",
            "test",
            List.of("h3-one", "h3-two"),
            Instant.parse("2026-07-20T00:00:00Z")
        );

        consumer.handleScenicRefresh(objectMapper.writeValueAsString(event));

        verify(scenicCache).evict(CacheKeySchema.scenicTile(SCORING_VERSION, "h3-one"));
        verify(scenicCache).evict(CacheKeySchema.scenicTile(SCORING_VERSION, "h3-two"));
        verify(popularityCache).clear();
        verify(metricsService).invalidate(CacheNames.SCENIC_TILES, 2);
    }
}
