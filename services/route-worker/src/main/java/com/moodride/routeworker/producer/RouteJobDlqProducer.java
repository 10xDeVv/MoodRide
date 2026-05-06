package com.moodride.routeworker.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moodride.eventmodels.RouteJobEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class RouteJobDlqProducer {

    private static final Logger logger = LoggerFactory.getLogger(RouteJobDlqProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public RouteJobDlqProducer(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void publishToDlq(UUID jobId, String reason, String originalPayload) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("jobId", jobId == null ? null : jobId.toString());
            payload.put("reason", reason == null ? "unknown" : reason);
            payload.put("originalPayload", originalPayload);
            payload.put("timestamp", Instant.now().toString());
            kafkaTemplate.send(RouteJobEvent.DLQ_TOPIC, jobId == null ? "unknown" : jobId.toString(), objectMapper.writeValueAsString(payload));
            logger.warn("Published route job to DLQ topic={} jobId={} reason={}", RouteJobEvent.DLQ_TOPIC, jobId, reason);
        } catch (Exception ex) {
            logger.error("Failed to publish route job to DLQ jobId={} reason={}", jobId, reason, ex);
        }
    }
}


