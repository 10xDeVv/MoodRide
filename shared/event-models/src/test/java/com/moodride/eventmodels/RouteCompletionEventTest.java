package com.moodride.eventmodels;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RouteCompletionEventTest {

    @Test
    void successReturnsTrueForCompletedStatus() {
        RouteCompletionEvent event = eventWithStatus("COMPLETED");

        assertTrue(event.success());
    }

    @Test
    void successReturnsTrueForPrimaryReadyStatus() {
        RouteCompletionEvent event = eventWithStatus("PRIMARY_READY");

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

    @Test
    void legacyConstructorDefaultsAdditiveLifecycleFields() {
        RouteCompletionEvent event = eventWithStatus("PRIMARY_READY");

        assertEquals(0L, event.stateRevision());
        assertEquals(0L, event.optionRevision());
        assertEquals(0, event.optionCount());
        assertFalse(event.optionsComplete());
        assertNull(event.eventId());
    }

    @Test
    void deserializesLegacyJsonAndIgnoresUnknownRollingDeployFields() throws Exception {
        UUID jobId = UUID.randomUUID();
        String json = """
            {
              "jobId": "%s",
              "routeId": null,
              "userId": null,
              "status": "FAILED",
              "waypoints": [],
              "totalDistanceKm": 0.0,
              "estimatedDurationMinutes": 0,
              "scenicScore": 0.0,
              "errorMessage": "failed",
              "completedAt": null,
              "futureProducerField": "ignored"
            }
            """.formatted(jobId);

        RouteCompletionEvent event = new ObjectMapper().readValue(json, RouteCompletionEvent.class);

        assertEquals(jobId, event.jobId());
        assertEquals(0L, event.stateRevision());
        assertEquals(0L, event.optionRevision());
        assertEquals(0, event.optionCount());
        assertFalse(event.optionsComplete());
        assertNull(event.eventId());
    }

    @Test
    void retainsCommittedLifecycleFields() {
        RouteCompletionEvent event = new RouteCompletionEvent(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "COMPLETED",
            List.of(),
            12.3,
            24,
            0.91,
            null,
            Instant.now(),
            7L,
            3L,
            3,
            true
        );

        assertEquals(7L, event.stateRevision());
        assertEquals(3L, event.optionRevision());
        assertEquals(3, event.optionCount());
        assertTrue(event.optionsComplete());
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
