package com.moodride.routeapi.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CacheWarmupScheduler {

    private static final Logger logger = LoggerFactory.getLogger(CacheWarmupScheduler.class);

    private final CacheWarmupService warmupService;
    private final boolean enabled;
    private final int limit;

    public CacheWarmupScheduler(
            CacheWarmupService warmupService,
            @Value("${moodride.cache.warmup.enabled:true}") boolean enabled,
            @Value("${moodride.cache.warmup.limit:200}") int limit
    ) {
        this.warmupService = warmupService;
        this.enabled = enabled;
        this.limit = limit;
    }

    @Scheduled(fixedDelayString = "${moodride.cache.warmup.interval-ms:3600000}", initialDelayString = "${moodride.cache.warmup.initial-delay-ms:30000}")
    public void warmCachesOnSchedule() {
        if (!enabled) {
            return;
        }
        CacheWarmupService.WarmupReport report = warmupService.warmAll(limit);
        logger.info("Cache warmup complete scenicTiles={} segmentMetadata={} regionalPopularity={} routeResults={}",
                report.scenicTiles(),
                report.segmentMetadata(),
                report.regionalPopularity(),
                report.routeResults());
    }
}

