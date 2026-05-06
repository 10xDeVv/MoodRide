package com.moodride.cdcservice.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moodride.cdcservice.config.CdcProperties;
import com.moodride.cdcservice.service.CdcControlService;
import com.moodride.cdcservice.service.CdcInvalidationService;
import com.moodride.cdcservice.service.CdcMetricsService;
import com.moodride.cdcservice.service.RedisIdempotencyService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class DebeziumCdcConsumer {

    private static final Logger logger = LoggerFactory.getLogger(DebeziumCdcConsumer.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CdcProperties cdcProperties;
    private final CdcControlService cdcControlService;
    private final RedisIdempotencyService idempotencyService;
    private final CdcInvalidationService invalidationService;
    private final CdcMetricsService metricsService;

    public DebeziumCdcConsumer(
            CdcProperties cdcProperties,
            CdcControlService cdcControlService,
            RedisIdempotencyService idempotencyService,
            CdcInvalidationService invalidationService,
            CdcMetricsService metricsService
    ) {
        this.cdcProperties = cdcProperties;
        this.cdcControlService = cdcControlService;
        this.idempotencyService = idempotencyService;
        this.invalidationService = invalidationService;
        this.metricsService = metricsService;
    }

    @KafkaListener(topics = "${moodride.cdc.topics.scenic-tile}", groupId = "cdc-service-group")
    public void handleScenicTileCdc(ConsumerRecord<String, String> record) {
        processRecord(record, true);
    }

    @KafkaListener(topics = "${moodride.cdc.topics.road-segment}", groupId = "cdc-service-group")
    public void handleRoadSegmentCdc(ConsumerRecord<String, String> record) {
        processRecord(record, false);
    }

    private void processRecord(ConsumerRecord<String, String> record, boolean scenic) {
        String topic = record.topic();
        if (cdcControlService.isPaused()) {
            return;
        }

        String dedupeKey = "cdc:dedupe:" + topic + ":" + record.partition() + ":" + record.offset();
        if (!idempotencyService.firstSeen(dedupeKey, cdcProperties.getIdempotencyTtlSeconds())) {
            metricsService.duplicate(topic);
            return;
        }

        try {
            JsonNode root = objectMapper.readTree(record.value());
            JsonNode payload = root.path("payload");
            JsonNode source = payload.path("source");
            JsonNode after = payload.path("after");
            JsonNode before = payload.path("before");

            long sourceTs = source.path("ts_ms").asLong(0L);
            if (sourceTs > 0) {
                metricsService.updateLag(System.currentTimeMillis() - sourceTs);
            }

            int invalidated;
            if (scenic) {
                String h3Index = readText(after, before, "h3_index");
                double oldScore = before.path("scenic_score").asDouble(0.0);
                double newScore = after.path("scenic_score").asDouble(oldScore);
                invalidated = invalidationService.invalidateScenicTile(h3Index, oldScore, newScore, "debezium");
            } else {
                String h3Index = readText(after, before, "h3_tile_index");
                invalidated = invalidationService.invalidateRoadSegmentTile(h3Index);
            }

            metricsService.processed(topic);
            metricsService.invalidated(scenic ? "scenicTiles" : "roadSegments", invalidated);
            cdcControlService.markProcessed();
        } catch (Exception ex) {
            metricsService.error(topic);
            logger.warn("CDC processing failed topic={} partition={} offset={} message={}",
                    topic, record.partition(), record.offset(), ex.getMessage());
        }
    }

    private String readText(JsonNode primary, JsonNode fallback, String field) {
        String value = primary.path(field).asText(null);
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) {
            value = fallback.path(field).asText(null);
        }
        return value;
    }
}

