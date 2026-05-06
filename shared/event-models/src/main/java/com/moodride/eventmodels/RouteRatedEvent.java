package com.moodride.eventmodels;

import java.time.Instant;
import java.util.UUID;

/**
 * Event emitted when a user rates a completed route.
 */
public record RouteRatedEvent(
        UUID routeId,
        UUID jobId,
        UUID userId,
        int rating,
        Instant ratedAt
) {
    public static final String TOPIC = "user.events.route_rated";
}
