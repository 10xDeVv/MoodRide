package com.moodride.notificationservice.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moodride.eventmodels.RouteCompletionEvent;
import com.moodride.notificationservice.config.AppConfig;
import com.moodride.notificationservice.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class RouteCompletionConsumerTest {

    @Mock
    private NotificationService notificationService;

    private RouteCompletionConsumer consumer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new AppConfig().objectMapper();
        consumer = new RouteCompletionConsumer(notificationService, objectMapper);
    }

    @Test
    void consumesSuccessStatusAndSendsCompletionNotification() throws Exception {
        RouteCompletionEvent event = new RouteCompletionEvent(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "SUCCESS",
            List.of(),
            12.4,
            24,
            0.91,
            null,
            Instant.now(),
            7L,
            3L,
            3,
            true
        );

        String message = objectMapper.writeValueAsString(event);

        consumer.consumeRouteCompletion(message);

        verify(notificationService).sendRouteCompletion(event);
        verify(notificationService, never()).sendRouteFailure(any(RouteCompletionEvent.class));
    }

    @Test
    void consumesPrimaryReadyJsonAndPreservesLifecycleRevisions() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String message = """
            {
              "jobId": "%s",
              "routeId": "%s",
              "userId": "%s",
              "status": "PRIMARY_READY",
              "waypoints": [],
              "totalDistanceKm": 12.4,
              "estimatedDurationMinutes": 24,
              "scenicScore": 0.91,
              "errorMessage": null,
              "completedAt": null,
              "stateRevision": 5,
              "optionRevision": 2,
              "optionCount": 2,
              "optionsComplete": false,
              "futureWorkerField": "ignored"
            }
            """.formatted(jobId, routeId, userId);

        consumer.consumeRouteCompletion(message);

        ArgumentCaptor<RouteCompletionEvent> eventCaptor =
            ArgumentCaptor.forClass(RouteCompletionEvent.class);
        verify(notificationService).sendRouteCompletion(eventCaptor.capture());
        RouteCompletionEvent forwarded = eventCaptor.getValue();
        assertEquals(5L, forwarded.stateRevision());
        assertEquals(2L, forwarded.optionRevision());
        assertEquals(2, forwarded.optionCount());
        assertFalse(forwarded.optionsComplete());
        verify(notificationService, never()).sendRouteFailure(any(RouteCompletionEvent.class));
    }

    @Test
    void consumesFailedStatusAndSendsFailureNotification() throws Exception {
        RouteCompletionEvent event = new RouteCompletionEvent(
            UUID.randomUUID(),
            null,
            UUID.randomUUID(),
            "FAILED",
            List.of(),
            0,
            0,
            0,
            "Route generation failed",
            Instant.now()
        );

        String message = objectMapper.writeValueAsString(event);

        consumer.consumeRouteCompletion(message);

        verify(notificationService).sendRouteFailure(event);
        verify(notificationService, never()).sendRouteCompletion(any());
    }
    @Test
    void validEventForwardingFailureEscapesToKafkaErrorHandler() throws Exception {
        RouteCompletionEvent event = new RouteCompletionEvent(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "PRIMARY_READY",
            List.of(),
            12.4,
            24,
            0.91,
            null,
            Instant.now()
        );
        RuntimeException deliveryFailure = new IllegalStateException("STOMP broker unavailable");
        doThrow(deliveryFailure)
            .when(notificationService)
            .sendRouteCompletion(any(RouteCompletionEvent.class));
        String message = objectMapper.writeValueAsString(event);

        RuntimeException thrown = assertThrows(
            RuntimeException.class,
            () -> consumer.consumeRouteCompletion(message)
        );

        assertSame(deliveryFailure, thrown);
        verify(notificationService).sendRouteCompletion(event);
    }

    @Test
    void malformedJsonEscapesForNonRetryableDltRecovery() {
        assertThrows(
            JsonProcessingException.class,
            () -> consumer.consumeRouteCompletion("{not-json}")
        );

        verifyNoInteractions(notificationService);
    }

}

