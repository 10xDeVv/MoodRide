package com.moodride.routeworker.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moodride.eventmodels.RouteJobEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class RouteJobDlqProducerTest {
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void preservesPayloadAndJobKeyAndLogsSuccessOnlyAfterBrokerAcknowledgment(
        CapturedOutput output
    ) throws Exception {
        UUID jobId = UUID.randomUUID();
        String reason = "Exceeded maximum retry attempts: 4";
        String originalPayload = jobId.toString();
        CompletableFuture<SendResult<String, String>> brokerAcknowledgment =
            new CompletableFuture<>();
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(brokerAcknowledgment);
        ObjectMapper objectMapper = new ObjectMapper();
        RouteJobDlqProducer producer =
            new RouteJobDlqProducer(kafkaTemplate, objectMapper, Duration.ofSeconds(2));
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> publication = executor.submit(
                () -> producer.publishToDlq(jobId, reason, originalPayload)
            );
            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(kafkaTemplate, timeout(1_000)).send(
                eq(RouteJobEvent.DLQ_TOPIC),
                eq(jobId.toString()),
                jsonCaptor.capture()
            );

            Map<String, Object> payload = objectMapper.readValue(
                jsonCaptor.getValue(),
                new TypeReference<>() {
                }
            );
            assertEquals(5, payload.size());
            assertNull(payload.get("eventId"));
            assertEquals(jobId.toString(), payload.get("jobId"));
            assertEquals(reason, payload.get("reason"));
            assertEquals(originalPayload, payload.get("originalPayload"));
            Instant.parse((String) payload.get("timestamp"));
            assertFalse(publication.isDone());
            assertFalse(output.getAll().contains(successMessage(jobId)));

            brokerAcknowledgment.complete(null);
            publication.get(1, TimeUnit.SECONDS);
            assertTrue(output.getAll().contains(successMessage(jobId)));
        } finally {
            executor.shutdownNow();
        }
    }
    @Test
    void durablePublicationSerializesStableEventIdentityAndOccurrenceTime() throws Exception {
        UUID jobId = UUID.randomUUID();
        String eventId = jobId + ":6:DLQ";
        Instant occurredAt = Instant.parse("2026-07-19T12:00:00Z");
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(null));
        ObjectMapper objectMapper = new ObjectMapper();
        RouteJobDlqProducer producer =
            new RouteJobDlqProducer(kafkaTemplate, objectMapper, Duration.ofSeconds(1));

        producer.publishToDlq(
            eventId,
            jobId,
            "terminal failure",
            jobId.toString(),
            occurredAt
        );

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(
            eq(RouteJobEvent.DLQ_TOPIC),
            eq(jobId.toString()),
            jsonCaptor.capture()
        );
        Map<String, Object> payload = objectMapper.readValue(
            jsonCaptor.getValue(),
            new TypeReference<>() {
            }
        );
        assertEquals(eventId, payload.get("eventId"));
        assertEquals(occurredAt.toString(), payload.get("timestamp"));
    }


    @Test
    void nullJobPreservesUnknownKeyAndNullJobField() throws Exception {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(null));
        ObjectMapper objectMapper = new ObjectMapper();
        RouteJobDlqProducer producer =
            new RouteJobDlqProducer(kafkaTemplate, objectMapper, Duration.ofSeconds(1));

        producer.publishToDlq(null, null, "not-a-uuid");

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(
            eq(RouteJobEvent.DLQ_TOPIC),
            eq("unknown"),
            jsonCaptor.capture()
        );
        Map<String, Object> payload = objectMapper.readValue(
            jsonCaptor.getValue(),
            new TypeReference<>() {
            }
        );
        assertNull(payload.get("jobId"));
        assertEquals("unknown", payload.get("reason"));
        assertEquals("not-a-uuid", payload.get("originalPayload"));
    }

    @Test
    void brokerFutureFailurePropagatesCauseWithoutLoggingSuccess(CapturedOutput output) {
        UUID jobId = UUID.randomUUID();
        IllegalStateException brokerFailure = new IllegalStateException("broker unavailable");
        CompletableFuture<SendResult<String, String>> brokerAcknowledgment =
            new CompletableFuture<>();
        brokerAcknowledgment.completeExceptionally(brokerFailure);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(brokerAcknowledgment);
        RouteJobDlqProducer producer = producer(Duration.ofSeconds(1));

        RouteJobDlqProducer.PublicationException exception = assertThrows(
            RouteJobDlqProducer.PublicationException.class,
            () -> producer.publishToDlq(jobId, "terminal", jobId.toString())
        );

        assertSame(brokerFailure, exception.getCause());
        assertFalse(output.getAll().contains(successMessage(jobId)));
    }

    @Test
    void unresolvedBrokerFutureTimesOutWithoutLoggingSuccess(CapturedOutput output) {
        UUID jobId = UUID.randomUUID();
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(new CompletableFuture<>());
        RouteJobDlqProducer producer = producer(Duration.ofMillis(20));

        RouteJobDlqProducer.PublicationException exception = assertThrows(
            RouteJobDlqProducer.PublicationException.class,
            () -> producer.publishToDlq(jobId, "terminal", jobId.toString())
        );

        assertTrue(exception.getMessage().contains("Timed out after PT0.02S"));
        assertFalse(output.getAll().contains(successMessage(jobId)));
    }

    @Test
    void interruptedWaitRestoresInterruptStatusAndPropagates() {
        UUID jobId = UUID.randomUUID();
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(new CompletableFuture<>());
        RouteJobDlqProducer producer = producer(Duration.ofSeconds(1));

        try {
            Thread.currentThread().interrupt();
            assertThrows(
                RouteJobDlqProducer.PublicationException.class,
                () -> producer.publishToDlq(jobId, "terminal", jobId.toString())
            );
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void cancelledBrokerFuturePropagatesTypedFailure() {
        UUID jobId = UUID.randomUUID();
        CompletableFuture<SendResult<String, String>> brokerAcknowledgment =
            new CompletableFuture<>();
        brokerAcknowledgment.cancel(false);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(brokerAcknowledgment);

        RouteJobDlqProducer.PublicationException exception = assertThrows(
            RouteJobDlqProducer.PublicationException.class,
            () -> producer(Duration.ofSeconds(1))
                .publishToDlq(jobId, "terminal", jobId.toString())
        );

        assertTrue(exception.getMessage().contains("cancelled"));
    }

    @Test
    void serializationFailurePropagatesWithoutSending() throws Exception {
        ObjectMapper failingObjectMapper = mock(ObjectMapper.class);
        JsonProcessingException serializationFailure = new JsonProcessingException("cannot serialize") {
        };
        when(failingObjectMapper.writeValueAsString(any(Map.class)))
            .thenThrow(serializationFailure);
        RouteJobDlqProducer producer = new RouteJobDlqProducer(
            kafkaTemplate,
            failingObjectMapper,
            Duration.ofSeconds(1)
        );

        RouteJobDlqProducer.PublicationException exception = assertThrows(
            RouteJobDlqProducer.PublicationException.class,
            () -> producer.publishToDlq(UUID.randomUUID(), "terminal", "payload")
        );

        assertSame(serializationFailure, exception.getCause());
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void synchronousSendFailurePropagatesTypedFailure() {
        UUID jobId = UUID.randomUUID();
        IllegalStateException sendFailure = new IllegalStateException("producer closed");
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenThrow(sendFailure);

        RouteJobDlqProducer.PublicationException exception = assertThrows(
            RouteJobDlqProducer.PublicationException.class,
            () -> producer(Duration.ofSeconds(1))
                .publishToDlq(jobId, "terminal", jobId.toString())
        );

        assertSame(sendFailure, exception.getCause());
    }

    private RouteJobDlqProducer producer(Duration acknowledgmentTimeout) {
        return new RouteJobDlqProducer(
            kafkaTemplate,
            new ObjectMapper(),
            acknowledgmentTimeout
        );
    }

    private String successMessage(UUID jobId) {
        return "Published route job to DLQ topic=" + RouteJobEvent.DLQ_TOPIC
            + " jobId=" + jobId;
    }
}
