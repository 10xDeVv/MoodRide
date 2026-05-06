package com.moodride.eventmodels;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Event published when a route generation job completes.
 * Consumed by notification-service for real-time delivery to frontend.
 */
public record RouteCompletionEvent(
    UUID jobId,
    UUID routeId,
    UUID userId,
    String status,  // "COMPLETED", "SUCCESS", "FAILED", "TIMEOUT"
    List<RouteWaypoint> waypoints,
    double totalDistanceKm,
    int estimatedDurationMinutes,
    double scenicScore,
    String errorMessage,  // null if status is COMPLETED/SUCCESS
    Instant completedAt
) {
    public static final String TOPIC = "route-completions";

    public boolean success() {
        if (status == null) {
            return false;
        }

        String normalizedStatus = status.trim().toUpperCase(Locale.ROOT);
        return "COMPLETED".equals(normalizedStatus) || "SUCCESS".equals(normalizedStatus);
    }

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