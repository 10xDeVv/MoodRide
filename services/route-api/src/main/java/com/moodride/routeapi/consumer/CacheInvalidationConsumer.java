package com.moodride.routeapi.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moodride.eventmodels.CdcTileUpdateEvent;
import com.moodride.eventmodels.ScenicTilesRefreshedEvent;
import com.moodride.routeapi.cache.CacheKeySchema;
import com.moodride.routeapi.cache.CacheMetricsService;
import com.moodride.routeapi.cache.CacheNames;
import com.moodride.routeapi.config.ScenicCacheConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class CacheInvalidationConsumer {

    private static final Logger logger = LoggerFactory.getLogger(CacheInvalidationConsumer.class);

    private final CacheManager cacheManager;
    private final ObjectMapper objectMapper;
    private final CacheMetricsService metricsService;
    private final String scenicScoringVersion;

    public CacheInvalidationConsumer(
        CacheManager cacheManager,
        ObjectMapper objectMapper,
        CacheMetricsService metricsService,
        ScenicCacheConfiguration scenicCacheConfiguration
    ) {
        this.cacheManager = cacheManager;
        this.objectMapper = objectMapper;
        this.metricsService = metricsService;
        this.scenicScoringVersion = scenicCacheConfiguration.getScenicScoringVersion();
    }

    @KafkaListener(topics = ScenicTilesRefreshedEvent.TOPIC, groupId = "route-api-cache-hooks")
    public void handleScenicRefresh(String payload) {
        try {
            ScenicTilesRefreshedEvent event = objectMapper.readValue(payload, ScenicTilesRefreshedEvent.class);
            Cache scenicCache = cacheManager.getCache(CacheNames.SCENIC_TILES);
            Cache popularityCache = cacheManager.getCache(CacheNames.REGIONAL_POPULARITY);
            int count = 0;
            if (scenicCache != null && event.h3Indexes() != null) {
                for (String h3 : event.h3Indexes()) {
                    scenicCache.evict(CacheKeySchema.scenicTile(scenicScoringVersion, h3));
                    count++;
                }
            }
            if (popularityCache != null) {
                popularityCache.clear();
            }
            metricsService.invalidate(CacheNames.SCENIC_TILES, count);
            logger.info("Route API cache invalidation complete source={} tiles={}", event.source(), count);
        } catch (Exception ex) {
            logger.warn("Failed to process scenic refresh invalidation payload: {}", ex.getMessage());
        }
    }

    @KafkaListener(topics = CdcTileUpdateEvent.TOPIC, groupId = "route-api-cache-hooks")
    public void handleCdcTileUpdate(String payload) {
        try {
            CdcTileUpdateEvent event = objectMapper.readValue(payload, CdcTileUpdateEvent.class);
            Cache scenicCache = cacheManager.getCache(CacheNames.SCENIC_TILES);
            if (scenicCache != null) {
                scenicCache.evict(CacheKeySchema.scenicTile(scenicScoringVersion, event.h3Index()));
                metricsService.invalidate(CacheNames.SCENIC_TILES, 1);
            }
            Cache popularityCache = cacheManager.getCache(CacheNames.REGIONAL_POPULARITY);
            if (popularityCache != null) {
                popularityCache.clear();
            }
        } catch (Exception ex) {
            logger.warn("Failed to process CDC invalidation payload: {}", ex.getMessage());
        }
    }
}

