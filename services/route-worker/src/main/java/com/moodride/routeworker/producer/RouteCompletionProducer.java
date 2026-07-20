package com.moodride.routeworker.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moodride.eventmodels.RouteCompletionEvent;
import com.moodride.routeworker.service.RouteJobLifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class RouteCompletionProducer {
    private static final Logger logger = LoggerFactory.getLogger(RouteCompletionProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Duration acknowledgmentTimeout;
    private final long acknowledgmentTimeoutNanos;

    public RouteCompletionProducer(
        KafkaTemplate<String, String> kafkaTemplate,
        ObjectMapper objectMapper,
        @Value("${moodride.kafka.producer.ack-timeout:10s}") Duration acknowledgmentTimeout
    ) {
        if (acknowledgmentTimeout == null
            || acknowledgmentTimeout.isZero()
            || acknowledgmentTimeout.isNegative()) {
            throw new IllegalArgumentException("Kafka acknowledgment timeout must be positive");
        }
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.acknowledgmentTimeout = acknowledgmentTimeout;
        try {
            this.acknowledgmentTimeoutNanos = acknowledgmentTimeout.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Kafka acknowledgment timeout is too large", exception);
        }
    }

    public void publishPrimaryReady(
        UUID jobId,
        UUID userId,
        double distanceKm,
        UUID routeId,
        int durationMinutes,
        double scenicScore,
        List<RouteCompletionEvent.RouteWaypoint> waypoints,
        RouteJobLifecycleService.LifecycleSnapshot state
    ) {
        publishRouteEvent(
            jobId, userId, distanceKm, routeId, durationMinutes, scenicScore,
            waypoints, "PRIMARY_READY", state, null
        );
    }

    public void publishCompletion(
        UUID jobId,
        UUID userId,
        double distanceKm,
        UUID routeId,
        int durationMinutes,
        double scenicScore,
        List<RouteCompletionEvent.RouteWaypoint> waypoints,
        RouteJobLifecycleService.LifecycleSnapshot state
    ) {
        publishCompletion(
            jobId, userId, distanceKm, routeId, durationMinutes, scenicScore,
            waypoints, state, null
        );
    }

    public void publishCompletion(
        UUID jobId,
        UUID userId,
        double distanceKm,
        UUID routeId,
        int durationMinutes,
        double scenicScore,
        List<RouteCompletionEvent.RouteWaypoint> waypoints,
        RouteJobLifecycleService.LifecycleSnapshot state,
        String eventId
    ) {
        publishRouteEvent(
            jobId, userId, distanceKm, routeId, durationMinutes, scenicScore,
            waypoints, "COMPLETED", state, eventId
        );
    }

    private void publishRouteEvent(
        UUID jobId,
        UUID userId,
        double distanceKm,
        UUID routeId,
        int durationMinutes,
        double scenicScore,
        List<RouteCompletionEvent.RouteWaypoint> waypoints,
        String status,
        RouteJobLifecycleService.LifecycleSnapshot state,
        String eventId
    ) {
        Instant eventTime = state.completedAt() == null ? Instant.now() : state.completedAt();
        RouteCompletionEvent event = new RouteCompletionEvent(
            jobId,
            routeId,
            userId,
            status,
            waypoints,
            distanceKm,
            durationMinutes,
            scenicScore,
            null,
            eventTime,
            state.stateRevision(),
            state.optionRevision(),
            state.optionCount(),
            state.optionsComplete(),
            eventId
        );
        publish(event, jobId, status);
    }

    public void publishFailure(RouteJobLifecycleService.LifecycleSnapshot state) {
        publishFailure(state, null);
    }

    public void publishFailure(
        RouteJobLifecycleService.LifecycleSnapshot state,
        String eventId
    ) {
        String status = state.status() == null ? "FAILED" : state.status().name();
        Instant eventTime = state.completedAt() == null ? Instant.now() : state.completedAt();
        RouteCompletionEvent event = new RouteCompletionEvent(
            state.jobId(),
            null,
            state.userId(),
            status,
            List.of(),
            0,
            0,
            0,
            state.failureReason(),
            eventTime,
            state.stateRevision(),
            state.optionRevision(),
            state.optionCount(),
            state.optionsComplete(),
            eventId
        );
        publish(event, state.jobId(), status);
    }

    private void publish(RouteCompletionEvent event, UUID jobId, String status) {
        final String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new PublicationException(
                "Failed to serialize route " + status.toLowerCase() + " event for job " + jobId,
                exception
            );
        }

        try {
            kafkaTemplate.send(RouteCompletionEvent.TOPIC, jobId.toString(), json)
                .get(acknowledgmentTimeoutNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PublicationException(
                "Interrupted while awaiting route " + status.toLowerCase()
                    + " event acknowledgment for job " + jobId,
                exception
            );
        } catch (TimeoutException exception) {
            throw new PublicationException(
                "Timed out after " + acknowledgmentTimeout + " awaiting route "
                    + status.toLowerCase() + " event acknowledgment for job " + jobId,
                exception
            );
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new PublicationException(
                "Broker failed route " + status.toLowerCase() + " event for job " + jobId,
                cause
            );
        } catch (RuntimeException exception) {
            throw new PublicationException(
                "Failed to send route " + status.toLowerCase() + " event for job " + jobId,
                exception
            );
        }

        logger.info("Published route {} event for job {}", status.toLowerCase(), jobId);
    }

    public static final class PublicationException extends IllegalStateException {
        public PublicationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
