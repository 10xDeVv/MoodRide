package com.moodride.routeworker.scheduler;

import com.moodride.routeworker.service.GraphService;
import com.moodride.routeworker.config.RouteWorkerSchedulingConfiguration;
import com.moodride.routeworker.service.WorkerCacheMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class GraphCacheWarmupScheduler {

    private static final Logger logger = LoggerFactory.getLogger(GraphCacheWarmupScheduler.class);

    private final GraphService graphService;
    private final WorkerCacheMetricsService metricsService;
    private final boolean enabled;

    public GraphCacheWarmupScheduler(
            GraphService graphService,
            WorkerCacheMetricsService metricsService,
            @Value("${moodride.cache.graph-warmup.enabled:false}") boolean enabled
    ) {
        this.graphService = graphService;
        this.metricsService = metricsService;
        this.enabled = enabled;
    }

    @Scheduled(
        fixedDelayString = "${moodride.cache.graph-warmup.interval-ms:900000}",
        initialDelayString = "${moodride.cache.graph-warmup.initial-delay-ms:20000}",
        scheduler = RouteWorkerSchedulingConfiguration.CACHE_WARMUP_TASK_SCHEDULER
    )
    public void warmGraphCache() {
        if (!enabled) {
            return;
        }
        graphService.getGraph();
        metricsService.warmSuccess("graph");
        logger.info("Route-worker graph cache warmup complete");
    }
}

