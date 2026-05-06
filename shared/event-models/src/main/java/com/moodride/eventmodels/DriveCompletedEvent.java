package com.moodride.eventmodels;

import java.time.Instant;
import java.util.UUID;

/**
 * Event emitted when a completed route is marked as driven (or implicitly completed by rating in v1).
 */
public record DriveCompletedEvent(
        UUID routeId,
        UUID jobId,
        UUID userId,
        Instant completedAt
) {
    public static final String TOPIC = "user.events.drive_completed";
}
