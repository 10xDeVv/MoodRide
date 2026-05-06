package com.moodride.routeworker.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moodride.eventmodels.ScenicTilesRefreshedEvent;
import com.moodride.routeworker.service.CacheInvalidationHookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class ScenicTilesRefreshedConsumer {

    private static final Logger logger = LoggerFactory.getLogger(ScenicTilesRefreshedConsumer.class);

    private final ObjectMapper objectMapper;
    private final CacheInvalidationHookService invalidationHookService;

    public ScenicTilesRefreshedConsumer(ObjectMapper objectMapper, CacheInvalidationHookService invalidationHookService) {
        this.objectMapper = objectMapper;
        this.invalidationHookService = invalidationHookService;
    }

    @KafkaListener(topics = ScenicTilesRefreshedEvent.TOPIC, groupId = "route-workers")
    public void onScenicTilesRefreshed(String message) {
        try {
            ScenicTilesRefreshedEvent event = objectMapper.readValue(message, ScenicTilesRefreshedEvent.class);
            invalidationHookService.invalidateTiles(event.h3Indexes());
            logger.info("Invalidated graph cache after scenic refresh eventId={} tileCount={} source={}",
                    event.eventId(),
                    event.h3Indexes() == null ? 0 : event.h3Indexes().size(),
                    event.source());
        } catch (Exception ex) {
            logger.error("Failed to process scenic refresh event payload={}", message, ex);
            throw new IllegalStateException("Scenic refresh event processing failed", ex);
        }
    }
}

