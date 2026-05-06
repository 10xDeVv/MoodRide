package com.moodride.routeworker.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moodride.eventmodels.CdcTileUpdateEvent;
import com.moodride.routeworker.service.CacheInvalidationHookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class CdcTileUpdateConsumer {

    private static final Logger logger = LoggerFactory.getLogger(CdcTileUpdateConsumer.class);

    private final ObjectMapper objectMapper;
    private final CacheInvalidationHookService invalidationHookService;

    public CdcTileUpdateConsumer(ObjectMapper objectMapper, CacheInvalidationHookService invalidationHookService) {
        this.objectMapper = objectMapper;
        this.invalidationHookService = invalidationHookService;
    }

    @KafkaListener(topics = CdcTileUpdateEvent.TOPIC, groupId = "route-worker-cache-hooks")
    public void onCdcTileUpdate(String payload) {
        try {
            CdcTileUpdateEvent event = objectMapper.readValue(payload, CdcTileUpdateEvent.class);
            invalidationHookService.invalidateTile(event.h3Index());
            logger.info("Processed CDC invalidation for tile={} source={}", event.h3Index(), event.updateSource());
        } catch (Exception ex) {
            logger.warn("Failed to process CDC tile update payload: {}", ex.getMessage());
        }
    }
}

