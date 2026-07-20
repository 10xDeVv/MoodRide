package com.moodride.notificationservice.service;

import com.moodride.eventmodels.RouteCompletionEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Test
    void forwardsCommittedLifecycleFieldsToStompPayload() {
        UUID jobId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        RouteCompletionEvent event = new RouteCompletionEvent(
            jobId,
            routeId,
            UUID.randomUUID(),
            "PRIMARY_READY",
            List.of(),
            18.2,
            31,
            0.88,
            null,
            Instant.now(),
            6L,
            2L,
            2,
            false
        );
        NotificationService service = new NotificationService(messagingTemplate);

        service.sendRouteCompletion(event);

        ArgumentCaptor<NotificationService.RouteReadyNotification> payloadCaptor =
            ArgumentCaptor.forClass(NotificationService.RouteReadyNotification.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/job/" + jobId), payloadCaptor.capture());
        NotificationService.RouteReadyNotification payload = payloadCaptor.getValue();
        assertEquals(jobId, payload.jobId());
        assertEquals(routeId, payload.routeId());
        assertEquals("PRIMARY_READY", payload.status());
        assertEquals(0.88, payload.scenicScore());
        assertEquals(6L, payload.stateRevision());
        assertEquals(2L, payload.optionRevision());
        assertEquals(2, payload.optionCount());
        assertFalse(payload.optionsComplete());
        assertNotNull(payload.timestamp());
    }
    @Test
    void forwardsFailureLifecycleFieldsToStompPayload() {
        UUID jobId = UUID.randomUUID();
        RouteCompletionEvent event = new RouteCompletionEvent(
            jobId,
            null,
            UUID.randomUUID(),
            "FAILED",
            List.of(),
            0.0,
            0,
            0.0,
            "failed",
            Instant.now(),
            8L,
            1L,
            1,
            false
        );
        NotificationService service = new NotificationService(messagingTemplate);

        service.sendRouteFailure(event);

        ArgumentCaptor<NotificationService.RouteFailureNotification> payloadCaptor =
            ArgumentCaptor.forClass(NotificationService.RouteFailureNotification.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/job/" + jobId), payloadCaptor.capture());
        NotificationService.RouteFailureNotification payload = payloadCaptor.getValue();
        assertEquals("FAILED", payload.status());
        assertEquals(8L, payload.stateRevision());
        assertEquals(1L, payload.optionRevision());
        assertEquals(1, payload.optionCount());
        assertFalse(payload.optionsComplete());
        assertNotNull(payload.timestamp());
    }
    @Test
    void completionDeliveryFailureEscapesToKafkaListener() {
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
            .when(messagingTemplate)
            .convertAndSend(anyString(), any(NotificationService.RouteReadyNotification.class));
        NotificationService service = new NotificationService(messagingTemplate);

        RuntimeException thrown = assertThrows(
            RuntimeException.class,
            () -> service.sendRouteCompletion(event)
        );

        assertSame(deliveryFailure, thrown);
    }

    @Test
    void failureDeliveryFailureEscapesToKafkaListener() {
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
        RuntimeException deliveryFailure = new IllegalStateException("STOMP broker unavailable");
        doThrow(deliveryFailure)
            .when(messagingTemplate)
            .convertAndSend(anyString(), any(NotificationService.RouteFailureNotification.class));
        NotificationService service = new NotificationService(messagingTemplate);

        RuntimeException thrown = assertThrows(
            RuntimeException.class,
            () -> service.sendRouteFailure(event)
        );

        assertSame(deliveryFailure, thrown);
    }

}
