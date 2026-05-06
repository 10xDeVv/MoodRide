package com.moodride.eventmodels;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteCompletionEventTest {

    @Test
    void successReturnsTrueForCompletedStatus() {
        RouteCompletionEvent event = eventWithStatus("COMPLETED");

        assertTrue(event.success());
    }

    @Test
    void successReturnsTrueForSuccessAliasIgnoringCaseAndWhitespace() {
        RouteCompletionEvent event = eventWithStatus(" success ");

        assertTrue(event.success());
    }

    @Test
    void successReturnsFalseForFailedStatus() {
        RouteCompletionEvent event = eventWithStatus("FAILED");

        assertFalse(event.success());
    }

    @Test
    void successReturnsFalseWhenStatusIsNull() {
        RouteCompletionEvent event = eventWithStatus(null);

        assertFalse(event.success());
    }

    private static RouteCompletionEvent eventWithStatus(String status) {
        return new RouteCompletionEvent(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            status,
            List.of(),
            1.2,
            10,
            0.8,
            null,
            Instant.now()
        );
    }
}
