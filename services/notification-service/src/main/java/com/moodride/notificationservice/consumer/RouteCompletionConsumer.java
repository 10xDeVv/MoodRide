package com.moodride.notificationservice.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moodride.eventmodels.RouteCompletionEvent;
import com.moodride.notificationservice.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * Kafka consumer that listens for route completion events and
 * triggers WebSocket notifications to users.
 */
@Service
public class RouteCompletionConsumer {
    
    private static final Logger logger = LoggerFactory.getLogger(RouteCompletionConsumer.class);
    
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    
    public RouteCompletionConsumer(NotificationService notificationService,
                                   ObjectMapper objectMapper) {
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }
    
    /**
     * Consumes route completion events from Kafka and sends WebSocket notifications.
     */
    @KafkaListener(topics = RouteCompletionEvent.TOPIC, groupId = "notification-service")
    public void consumeRouteCompletion(String message) {
        try {
            RouteCompletionEvent event = objectMapper.readValue(message, RouteCompletionEvent.class);
            
            if (event.success() && event.routeId() != null) {
                notificationService.sendRouteCompletion(event);
                logger.info("Processed route completion for job {}", event.jobId());
            } else {
                notificationService.sendRouteFailure(
                    event.jobId(),
                    event.userId(),
                    event.errorMessage()
                );
                logger.info("Processed route failure for job {}", event.jobId());
            }
        } catch (Exception e) {
            logger.error("Failed to process route completion message: {}", e.getMessage(), e);
        }
    }
}
