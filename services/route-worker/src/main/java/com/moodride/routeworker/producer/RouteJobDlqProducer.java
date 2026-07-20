package com.moodride.routeworker.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moodride.eventmodels.RouteJobEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class RouteJobDlqProducer {

    private static final Logger logger = LoggerFactory.getLogger(RouteJobDlqProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Duration acknowledgmentTimeout;
    private final long acknowledgmentTimeoutNanos;

    public RouteJobDlqProducer(
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

    public void publishToDlq(UUID jobId, String reason, String originalPayload) {
        publishToDlq(null, jobId, reason, originalPayload, Instant.now());
    }

    public void publishToDlq(
        String eventId,
        UUID jobId,
        String reason,
        String originalPayload,
        Instant occurredAt
    ) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventId", eventId);
        payload.put("jobId", jobId == null ? null : jobId.toString());
        payload.put("reason", reason == null ? "unknown" : reason);
        payload.put("originalPayload", originalPayload);
        payload.put("timestamp", Objects.requireNonNull(occurredAt, "occurredAt").toString());

        final String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException | RuntimeException exception) {
            throw new PublicationException(
                "Failed to serialize route job DLQ record for job " + jobId,
                exception
            );
        }

        String key = jobId == null ? "unknown" : jobId.toString();
        try {
            kafkaTemplate.send(RouteJobEvent.DLQ_TOPIC, key, json)
                .get(acknowledgmentTimeoutNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PublicationException(
                "Interrupted while awaiting route job DLQ acknowledgment for job " + jobId,
                exception
            );
        } catch (TimeoutException exception) {
            throw new PublicationException(
                "Timed out after " + acknowledgmentTimeout
                    + " awaiting route job DLQ acknowledgment for job " + jobId,
                exception
            );
        } catch (CancellationException exception) {
            throw new PublicationException(
                "Route job DLQ publication was cancelled for job " + jobId,
                exception
            );
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new PublicationException(
                "Broker failed route job DLQ publication for job " + jobId,
                cause
            );
        } catch (RuntimeException exception) {
            throw new PublicationException(
                "Failed to send route job DLQ record for job " + jobId,
                exception
            );
        }

        logger.warn(
            "Published route job to DLQ topic={} jobId={} reason={} eventId={}",
            RouteJobEvent.DLQ_TOPIC,
            jobId,
            reason,
            eventId
        );
    }

    public static final class PublicationException extends IllegalStateException {
        public PublicationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}


