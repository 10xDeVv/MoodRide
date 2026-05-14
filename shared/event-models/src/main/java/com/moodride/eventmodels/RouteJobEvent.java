package com.moodride.eventmodels;

import java.time.Instant;
import java.util.UUID;

/**
 * Event published when a route generation job is submitted.
 * Consumed by route-worker service to begin processing.
 */
public record RouteJobEvent(
    UUID jobId,
    UUID userId,
    double startLatitude,
    double startLongitude,
    int timeBudgetMinutes,
    String routeMode,
    String vibe,  // "coastal", "mountain", "forest", "mixed"
    Instant submittedAt
) {
    public static final String TOPIC = "route-jobs";
    public static final String DLQ_TOPIC = "route.jobs.dlq";
}
