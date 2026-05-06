package com.moodride.routeworker.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moodride.eventmodels.RouteCompletionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class RouteCompletionProducer {
    
    private static final Logger logger = LoggerFactory.getLogger(RouteCompletionProducer.class);
    
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    
    public RouteCompletionProducer(KafkaTemplate<String, String> kafkaTemplate,
                                   ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }
    
    public void publishCompletion(UUID jobId, UUID userId, double distanceKm, 
                                   UUID routeId,
                                   int durationMinutes, double scenicScore,
                                   List<RouteCompletionEvent.RouteWaypoint> waypoints) {
        try {
            RouteCompletionEvent event = new RouteCompletionEvent(
                jobId,
                routeId,
                userId,
                "COMPLETED",
                waypoints,
                distanceKm,
                durationMinutes,
                scenicScore,
                null,
                Instant.now()
            );
            
            String json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(RouteCompletionEvent.TOPIC, jobId.toString(), json);
            logger.info("Published route completion event for job {}", jobId);
        } catch (Exception e) {
            logger.error("Error publishing route completion for job {}: {}", jobId, e.getMessage(), e);
        }
    }
    
    public void publishFailure(UUID jobId, UUID userId, String reason) {
        try {
            RouteCompletionEvent event = new RouteCompletionEvent(
                jobId,
                null,
                userId,
                "FAILED",
                List.of(),
                0,
                0,
                0,
                reason,
                Instant.now()
            );
            
            String json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(RouteCompletionEvent.TOPIC, jobId.toString(), json);
            logger.info("Published route failure event for job {}", jobId);
        } catch (Exception e) {
            logger.error("Error publishing route failure for job {}: {}", jobId, e.getMessage(), e);
        }
    }
}
