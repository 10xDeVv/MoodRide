package com.moodride.scenicscoringservice.scheduler;

import com.moodride.scenicscoringservice.service.TrafficRefreshEventPublisher;
import com.moodride.scenicscoringservice.service.TrafficRefreshQueueService;
import com.moodride.scenicscoringservice.service.ScenicTileRecomputeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TrafficRefreshScheduler {

    private static final Logger logger = LoggerFactory.getLogger(TrafficRefreshScheduler.class);

    private final TrafficRefreshQueueService queueService;
    private final TrafficRefreshEventPublisher eventPublisher;
    private final ScenicTileRecomputeService recomputeService;
    private final int batchSize;

    public TrafficRefreshScheduler(
            TrafficRefreshQueueService queueService,
            TrafficRefreshEventPublisher eventPublisher,
            ScenicTileRecomputeService recomputeService,
            @Value("${moodride.traffic.refresh.batch-size:200}") int batchSize
    ) {
        this.queueService = queueService;
        this.eventPublisher = eventPublisher;
        this.recomputeService = recomputeService;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${moodride.traffic.refresh.scheduler-delay-ms:10000}", initialDelay = 15000)
    public void processPendingRefreshes() {
        List<String> readyTiles = queueService.claimReadyTiles(batchSize);
        if (readyTiles.isEmpty()) {
            return;
        }

        String csv = String.join(",", readyTiles);
        try {
            int recomputed = recomputeService.recomputeTiles(readyTiles);
            if (recomputed <= 0) {
                queueService.releaseForRetry(readyTiles);
                logger.warn("Traffic scenic refresh recompute produced no updates tiles={} csv={}", readyTiles.size(), csv);
                return;
            }

            queueService.markProcessed(readyTiles);
            eventPublisher.publishScenicTilesRefreshed("traffic-refresh", readyTiles);
            logger.info("Traffic scenic refresh recompute completed requestedTiles={} recomputedTiles={}", readyTiles.size(), recomputed);
        } catch (Exception ex) {
            queueService.releaseForRetry(readyTiles);
            logger.error("Traffic scenic refresh scheduler failed tiles={}", readyTiles.size(), ex);
        }
    }
}

