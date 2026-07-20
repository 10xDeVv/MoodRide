package com.moodride.eventmodels;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Event published when a route generation job completes.
 * Consumed by notification-service for real-time delivery to frontend.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RouteCompletionEvent(
    UUID jobId,
    UUID routeId,
    UUID userId,
    String status,  // "PRIMARY_READY", "COMPLETED", "SUCCESS", "FAILED", "TIMEOUT"
    List<RouteWaypoint> waypoints,
    double totalDistanceKm,
    int estimatedDurationMinutes,
    double scenicScore,
    String errorMessage,  // null if status is COMPLETED/SUCCESS
    Instant completedAt,
    long stateRevision,
    long optionRevision,
    int optionCount,
    boolean optionsComplete,
    String eventId
) {
    public static final String TOPIC = "route-completions";

    public RouteCompletionEvent(
        UUID jobId,
        UUID routeId,
        UUID userId,
        String status,
        List<RouteWaypoint> waypoints,
        double totalDistanceKm,
        int estimatedDurationMinutes,
        double scenicScore,
        String errorMessage,
        Instant completedAt,
        long stateRevision,
        long optionRevision,
        int optionCount,
        boolean optionsComplete
    ) {
        this(
            jobId,
            routeId,
            userId,
            status,
            waypoints,
            totalDistanceKm,
            estimatedDurationMinutes,
            scenicScore,
            errorMessage,
            completedAt,
            stateRevision,
            optionRevision,
            optionCount,
            optionsComplete,
            null
        );
    }

    public RouteCompletionEvent(
        UUID jobId,
        UUID routeId,
        UUID userId,
        String status,
        List<RouteWaypoint> waypoints,
        double totalDistanceKm,
        int estimatedDurationMinutes,
        double scenicScore,
        String errorMessage,
        Instant completedAt
    ) {
        this(
            jobId,
            routeId,
            userId,
            status,
            waypoints,
            totalDistanceKm,
            estimatedDurationMinutes,
            scenicScore,
            errorMessage,
            completedAt,
            0L,
            0L,
            0,
            false,
            null
        );
    }

    public boolean success() {
        if (status == null) {
            return false;
        }

        String normalizedStatus = status.trim().toUpperCase(Locale.ROOT);
        return "PRIMARY_READY".equals(normalizedStatus)
            || "COMPLETED".equals(normalizedStatus)
            || "SUCCESS".equals(normalizedStatus);
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