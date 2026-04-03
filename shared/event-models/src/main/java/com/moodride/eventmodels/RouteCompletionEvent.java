package com.moodride.eventmodels;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Event published when a route generation job completes.
 * Consumed by notification-service for real-time delivery to frontend.
 */
public record RouteCompletionEvent(
    UUID jobId,
    UUID userId,
    String status,  // "SUCCESS", "FAILED", "TIMEOUT"
    List<RouteWaypoint> waypoints,
    double totalDistanceKm,
    int estimatedDurationMinutes,
    double scenicScore,
    String failureReason,  // null if status is SUCCESS
    Instant completedAt
) {
    public static final String TOPIC = "route-completions";

    /**
     * Individual waypoint in the generated route
     */
    public record RouteWaypoint(
        double latitude,
        double longitude,
        String instruction,  // "Turn left", "Continue straight", etc.
        double distanceToNext  // kilometers to next waypoint
    ) {}
}