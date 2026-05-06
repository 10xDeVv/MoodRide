package com.moodride.cdcservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moodride.cdcservice.config.CdcProperties;
import com.moodride.eventmodels.CdcTileUpdateEvent;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class CdcInvalidationService {

    private final StringRedisTemplate redisTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final CdcProperties cdcProperties;

    public CdcInvalidationService(
            StringRedisTemplate redisTemplate,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            CdcProperties cdcProperties
    ) {
        this.redisTemplate = redisTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.cdcProperties = cdcProperties;
    }

    public int invalidateScenicTile(String h3Index, double oldScore, double newScore, String updateSource) {
        if (h3Index == null || h3Index.isBlank()) {
            return 0;
        }

        int invalidated = 0;
        invalidated += deleteIfPresent("scenic:tile:" + h3Index);
        invalidated += deleteIfPresent("popular:routes:" + parentPrefix(h3Index));

        publishTileUpdate(h3Index, oldScore, newScore, updateSource);
        kafkaTemplate.send(cdcProperties.getTopics().getRecomputeRequest(), h3Index, h3Index);

        return invalidated;
    }

    public int invalidateRoadSegmentTile(String h3Index) {
        if (h3Index == null || h3Index.isBlank()) {
            return 0;
        }
        int invalidated = deleteIfPresent("segment:meta:" + h3Index);
        kafkaTemplate.send(cdcProperties.getTopics().getRecomputeRequest(), h3Index, h3Index);
        return invalidated;
    }

    private int deleteIfPresent(String key) {
        Boolean deleted = redisTemplate.delete(key);
        return Boolean.TRUE.equals(deleted) ? 1 : 0;
    }

    private String parentPrefix(String h3Index) {
        return h3Index.length() <= 7 ? h3Index : h3Index.substring(0, 7);
    }

    private void publishTileUpdate(String h3Index, double oldScore, double newScore, String source) {
        try {
            CdcTileUpdateEvent event = new CdcTileUpdateEvent(
                    h3Index,
                    oldScore,
                    newScore,
                    source == null ? "cdc" : source,
                    Instant.now()
            );
            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(cdcProperties.getTopics().getForwardedTileUpdate(), h3Index, payload);
        } catch (Exception ignored) {
            // keep invalidation path resilient even when forwarding telemetry fails
        }
    }
}

