package com.moodride.notificationservice.service;

import com.moodride.eventmodels.RouteCompletionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Service for sending real-time notifications to users via WebSocket.
 * Notifies users when their route generation jobs complete or fail.
 */
@Service
public class NotificationService {
    
    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);
    
    private final SimpMessagingTemplate messagingTemplate;

    public NotificationService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }
    
    /**
     * Sends route completion notification to a specific user via WebSocket.
     * Message is sent to /topic/routes/{userId}
     */
    public void sendRouteCompletion(RouteCompletionEvent event) {
        try {
            String destination = "/topic/job/" + event.jobId();
            RouteReadyNotification payload = new RouteReadyNotification(
                event.jobId(),
                event.routeId(),
                event.status(),
                event.scenicScore(),
                Instant.now().toString()
            );
            messagingTemplate.convertAndSend(destination, payload);
            logger.info("Sent route completion notification for job {} to channel {}", event.jobId(), destination);
        } catch (Exception e) {
            logger.error("Failed to send route completion notification for job {}: {}", event.jobId(), e.getMessage(), e);
        }
    }
    
    /**
     * Sends route failure notification to a specific user.
     */
    public void sendRouteFailure(UUID jobId, UUID userId, String errorMessage) {
        try {
            String destination = "/topic/job/" + jobId;
            var failureEvent = new RouteFailureNotification(
                    jobId,
                    userId,
                    errorMessage,
                    true,
                    "/routes/" + jobId,
                    Instant.now().toString()
            );
            messagingTemplate.convertAndSend(destination, failureEvent);
            logger.info("Sent route failure notification for job {} to channel {}", jobId, destination);
        } catch (Exception e) {
            logger.error("Failed to send route failure notification for job {}: {}", jobId, e.getMessage(), e);
        }
    }
    
    /**
     * DTO for route failure notifications.
     */
    public record RouteFailureNotification(
        UUID jobId,
        UUID userId,
        String reason,
        boolean retryable,
        String pollUrl,
        String timestamp
    ) {}

    public record RouteReadyNotification(
        UUID jobId,
        UUID routeId,
        String status,
        double scenicScore,
        String timestamp
    ) {}
}
