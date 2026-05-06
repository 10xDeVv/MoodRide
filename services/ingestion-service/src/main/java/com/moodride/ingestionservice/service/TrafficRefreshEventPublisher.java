package com.moodride.ingestionservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moodride.eventmodels.ScenicTilesRefreshedEvent;
import com.moodride.eventmodels.TrafficTilesUpdatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class TrafficRefreshEventPublisher {

    private static final Logger logger = LoggerFactory.getLogger(TrafficRefreshEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public TrafficRefreshEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public String publishTrafficTilesUpdated(String source, List<String> h3Indexes) {
        String eventId = UUID.randomUUID().toString();
        if (h3Indexes == null || h3Indexes.isEmpty()) {
            return eventId;
        }
        try {
            TrafficTilesUpdatedEvent event = new TrafficTilesUpdatedEvent(
                    eventId,
                    source,
                    h3Indexes,
                    Instant.now()
            );
            kafkaTemplate.send(TrafficTilesUpdatedEvent.TOPIC, source, objectMapper.writeValueAsString(event));
            logger.info("Published traffic tile refresh request eventId={} source={} tiles={}", eventId, source, h3Indexes.size());
            return eventId;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to publish traffic tile refresh event", ex);
        }
    }

    public String publishScenicTilesRefreshed(String source, List<String> h3Indexes) {
        String eventId = UUID.randomUUID().toString();
        if (h3Indexes == null || h3Indexes.isEmpty()) {
            return eventId;
        }
        try {
            ScenicTilesRefreshedEvent event = new ScenicTilesRefreshedEvent(
                    eventId,
                    source,
                    h3Indexes,
                    Instant.now()
            );
            kafkaTemplate.send(ScenicTilesRefreshedEvent.TOPIC, source, objectMapper.writeValueAsString(event));
            logger.info("Published scenic refresh completed eventId={} source={} tiles={}", eventId, source, h3Indexes.size());
            return eventId;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to publish scenic refresh completion event", ex);
        }
    }
}

