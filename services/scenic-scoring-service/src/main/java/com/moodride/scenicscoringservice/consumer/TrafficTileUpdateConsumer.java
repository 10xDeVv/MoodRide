package com.moodride.scenicscoringservice.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moodride.eventmodels.TrafficTilesUpdatedEvent;
import com.moodride.scenicscoringservice.service.TrafficRefreshQueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class TrafficTileUpdateConsumer {

    private static final Logger logger = LoggerFactory.getLogger(TrafficTileUpdateConsumer.class);

    private final ObjectMapper objectMapper;
    private final TrafficRefreshQueueService queueService;

    public TrafficTileUpdateConsumer(ObjectMapper objectMapper, TrafficRefreshQueueService queueService) {
        this.objectMapper = objectMapper;
        this.queueService = queueService;
    }

    @KafkaListener(topics = TrafficTilesUpdatedEvent.TOPIC, groupId = "scenic-refresh-consumers")
    public void handleTrafficTileUpdate(String message) {
        try {
            TrafficTilesUpdatedEvent event = objectMapper.readValue(message, TrafficTilesUpdatedEvent.class);
            int queued = queueService.enqueue(event.eventId(), event.source(), event.h3Indexes());
            logger.info("Traffic update consumed eventId={} source={} queued={}", event.eventId(), event.source(), queued);
        } catch (Exception ex) {
            logger.error("Failed to process traffic tile update event payload={}", message, ex);
            throw new IllegalStateException("Traffic tile update event processing failed", ex);
        }
    }
}

