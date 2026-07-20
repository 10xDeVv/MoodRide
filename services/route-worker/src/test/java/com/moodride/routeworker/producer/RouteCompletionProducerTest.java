package com.moodride.routeworker.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moodride.datamodels.RouteJob;
import com.moodride.eventmodels.RouteCompletionEvent;
import com.moodride.routeworker.service.RouteJobLifecycleService;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
class RouteCompletionProducerTest {
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void publishesCommittedLifecycleRevisionFieldsOnlyAfterBrokerAcknowledgment() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        RouteJobLifecycleService.LifecycleSnapshot committed =
            snapshot(jobId, userId, routeId, RouteJob.JobStatus.COMPLETED);
        String eventId = jobId + ":9:COMPLETION";
        ObjectMapper objectMapper = objectMapper();
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(null));
        RouteCompletionProducer producer =
            new RouteCompletionProducer(kafkaTemplate, objectMapper, Duration.ofSeconds(1));

        publishCompletion(producer, committed, eventId);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(
            eq(RouteCompletionEvent.TOPIC),
            eq(jobId.toString()),
            jsonCaptor.capture()
        );
        RouteCompletionEvent event = objectMapper.readValue(
            jsonCaptor.getValue(),
            RouteCompletionEvent.class
        );
        assertEquals("COMPLETED", event.status());
        assertEquals(9L, event.stateRevision());
        assertEquals(3L, event.optionRevision());
        assertEquals(3, event.optionCount());
        assertTrue(event.optionsComplete());
        assertEquals(committed.completedAt(), event.completedAt());
        assertEquals(eventId, event.eventId());
    }

    @Test
    void waitsWhileBrokerAcknowledgmentIsUnresolved() throws Exception {
        UUID jobId = UUID.randomUUID();
        RouteJobLifecycleService.LifecycleSnapshot committed = snapshot(
            jobId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            RouteJob.JobStatus.COMPLETED
        );
        CompletableFuture<SendResult<String, String>> brokerAcknowledgment = new CompletableFuture<>();
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(brokerAcknowledgment);
        RouteCompletionProducer producer =
            new RouteCompletionProducer(kafkaTemplate, objectMapper(), Duration.ofSeconds(2));
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> publication = executor.submit(() -> publishCompletion(producer, committed));
            verify(kafkaTemplate, timeout(1_000)).send(
                eq(RouteCompletionEvent.TOPIC),
                eq(jobId.toString()),
                anyString()
            );

            assertFalse(publication.isDone());
            brokerAcknowledgment.complete(null);
            publication.get(1, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void brokerFailurePropagatesItsCauseWithoutLoggingSuccess(CapturedOutput output) {
        RouteJobLifecycleService.LifecycleSnapshot committed = completedSnapshot();
        IllegalStateException brokerFailure = new IllegalStateException("broker unavailable");
        CompletableFuture<SendResult<String, String>> brokerAcknowledgment = new CompletableFuture<>();
        brokerAcknowledgment.completeExceptionally(brokerFailure);
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(brokerAcknowledgment);
        RouteCompletionProducer producer =
            new RouteCompletionProducer(kafkaTemplate, objectMapper(), Duration.ofSeconds(1));

        RouteCompletionProducer.PublicationException exception = assertThrows(
            RouteCompletionProducer.PublicationException.class,
            () -> publishCompletion(producer, committed)
        );

        assertSame(brokerFailure, exception.getCause());
        assertFalse(output.getAll().contains(
            "Published route completed event for job " + committed.jobId()
        ));
    }

    @Test
    void unresolvedBrokerAcknowledgmentTimesOutWithoutLoggingSuccess(CapturedOutput output) {
        RouteJobLifecycleService.LifecycleSnapshot committed = completedSnapshot();
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(new CompletableFuture<>());
        RouteCompletionProducer producer =
            new RouteCompletionProducer(kafkaTemplate, objectMapper(), Duration.ofMillis(20));

        RouteCompletionProducer.PublicationException exception = assertThrows(
            RouteCompletionProducer.PublicationException.class,
            () -> publishCompletion(producer, committed)
        );

        assertTrue(exception.getMessage().contains("Timed out"));
        assertFalse(output.getAll().contains(
            "Published route completed event for job " + committed.jobId()
        ));
    }

    @Test
    void serializationFailurePropagatesWithoutSending() throws Exception {
        ObjectMapper failingObjectMapper = mock(ObjectMapper.class);
        JsonProcessingException serializationFailure = new JsonProcessingException("cannot serialize") {
        };
        when(failingObjectMapper.writeValueAsString(any(RouteCompletionEvent.class)))
            .thenThrow(serializationFailure);
        RouteCompletionProducer producer =
            new RouteCompletionProducer(kafkaTemplate, failingObjectMapper, Duration.ofSeconds(1));

        RouteCompletionProducer.PublicationException exception = assertThrows(
            RouteCompletionProducer.PublicationException.class,
            () -> publishCompletion(producer, completedSnapshot())
        );

        assertSame(serializationFailure, exception.getCause());
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void synchronousSendFailurePropagates() {
        IllegalStateException sendFailure = new IllegalStateException("producer closed");
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenThrow(sendFailure);
        RouteCompletionProducer producer =
            new RouteCompletionProducer(kafkaTemplate, objectMapper(), Duration.ofSeconds(1));

        RouteCompletionProducer.PublicationException exception = assertThrows(
            RouteCompletionProducer.PublicationException.class,
            () -> publishCompletion(producer, completedSnapshot())
        );

        assertSame(sendFailure, exception.getCause());
    }

    @Test
    void interruptedWaitRestoresInterruptStatusAndPropagates() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(new CompletableFuture<>());
        RouteCompletionProducer producer =
            new RouteCompletionProducer(kafkaTemplate, objectMapper(), Duration.ofSeconds(1));

        try {
            Thread.currentThread().interrupt();
            assertThrows(
                RouteCompletionProducer.PublicationException.class,
                () -> publishCompletion(producer, completedSnapshot())
            );
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void timeoutFailureEventRetainsTerminalStatusAndWaitsForAcknowledgment() throws Exception {
        UUID jobId = UUID.randomUUID();
        RouteJobLifecycleService.LifecycleSnapshot timedOut = new RouteJobLifecycleService.LifecycleSnapshot(
            jobId,
            UUID.randomUUID(),
            null,
            RouteJob.JobStatus.TIMEOUT,
            7L,
            0L,
            0,
            false,
            "timed out",
            3,
            Instant.now()
        );
        CompletableFuture<SendResult<String, String>> brokerAcknowledgment = new CompletableFuture<>();
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(brokerAcknowledgment);
        ObjectMapper objectMapper = objectMapper();
        RouteCompletionProducer producer =
            new RouteCompletionProducer(kafkaTemplate, objectMapper, Duration.ofSeconds(2));
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> publication = executor.submit(() -> producer.publishFailure(timedOut));
            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(kafkaTemplate, timeout(1_000)).send(
                eq(RouteCompletionEvent.TOPIC),
                eq(jobId.toString()),
                jsonCaptor.capture()
            );
            assertFalse(publication.isDone());
            RouteCompletionEvent event = objectMapper.readValue(
                jsonCaptor.getValue(),
                RouteCompletionEvent.class
            );
            assertEquals("TIMEOUT", event.status());
            assertEquals(7L, event.stateRevision());

            brokerAcknowledgment.complete(null);
            publication.get(1, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private void publishCompletion(
        RouteCompletionProducer producer,
        RouteJobLifecycleService.LifecycleSnapshot state
    ) {
        publishCompletion(producer, state, null);
    }

    private void publishCompletion(
        RouteCompletionProducer producer,
        RouteJobLifecycleService.LifecycleSnapshot state,
        String eventId
    ) {
        producer.publishCompletion(
            state.jobId(),
            state.userId(),
            18.2,
            state.routeId(),
            31,
            0.88,
            List.of(),
            state,
            eventId
        );
    }

    private RouteJobLifecycleService.LifecycleSnapshot completedSnapshot() {
        return snapshot(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            RouteJob.JobStatus.COMPLETED
        );
    }

    private RouteJobLifecycleService.LifecycleSnapshot snapshot(
        UUID jobId,
        UUID userId,
        UUID routeId,
        RouteJob.JobStatus status
    ) {
        return new RouteJobLifecycleService.LifecycleSnapshot(
            jobId,
            userId,
            routeId,
            status,
            9L,
            3L,
            3,
            true,
            null,
            1,
            Instant.now()
        );
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
