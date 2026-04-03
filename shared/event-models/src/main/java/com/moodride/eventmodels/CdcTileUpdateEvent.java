package com.moodride.eventmodels;

import java.time.Instant;

/**
 * Event published by cdc-service when scenic tile scores are updated.
 * Triggers cache invalidation in route-worker Redis cache.
 */
public record CdcTileUpdateEvent(
    String h3Index,        // H3 hex index (resolution 9: ~105m average)
    double oldScenicScore, // Previous scenic score (0.0 - 1.0)
    double newScenicScore, // Updated scenic score (0.0 - 1.0)
    String updateSource,   // "weekly-batch" or "manual-override"
    Instant updatedAt
) {
    public static final String TOPIC = "scenic-tile-updates";
}