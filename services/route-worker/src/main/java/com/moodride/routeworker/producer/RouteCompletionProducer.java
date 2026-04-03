package com.moodride.routeworker.producer;

import com.moodride.eventmodels.RouteCompletionEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class RouteCompletionProducer {
    
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    
    public RouteCompletionProducer(KafkaTemplate<String, String> kafkaTemplate,
                                   ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }
    
    public void publishCompletion(UUID jobId, UUID userId, double distanceKm, 
                                   int durationMinutes, double scenicScore,
                                   List<RouteCompletionEvent.RouteWaypoint> waypoints) {
        try {
            RouteCompletionEvent event = new RouteCompletionEvent(
                jobId,
                userId,
                "SUCCESS",
                waypoints,
                distanceKm,
                durationMinutes,
                scenicScore,
                null,
                Instant.now()
            );
            
            String json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(RouteCompletionEvent.TOPIC, jobId.toString(), json);
        } catch (Exception e) {
            System.err.println("Error publishing route completion: " + e.getMessage());
        }
    }
    
    public void publishFailure(UUID jobId, UUID userId, String reason) {
        try {
            RouteCompletionEvent event = new RouteCompletionEvent(
                jobId,
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
        } catch (Exception e) {
            System.err.println("Error publishing route failure: " + e.getMessage());
        }
    }
}
