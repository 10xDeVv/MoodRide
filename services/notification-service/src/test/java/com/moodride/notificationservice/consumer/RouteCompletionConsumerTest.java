package com.moodride.notificationservice.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.moodride.eventmodels.RouteCompletionEvent;
import com.moodride.notificationservice.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RouteCompletionConsumerTest {

    @Mock
    private NotificationService notificationService;

    private RouteCompletionConsumer consumer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
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
            Instant.now()
        );

        String message = objectMapper.writeValueAsString(event);

        consumer.consumeRouteCompletion(message);

        verify(notificationService).sendRouteCompletion(event);
        verify(notificationService, never()).sendRouteFailure(any(), any(), anyString());
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

        verify(notificationService).sendRouteFailure(event.jobId(), event.userId(), event.errorMessage());
        verify(notificationService, never()).sendRouteCompletion(any());
    }
}

