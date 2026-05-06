package com.moodride.eventmodels;

import java.time.Instant;
import java.util.List;

/**
 * Event emitted when one or more scenic tiles have been recomputed.
 */
public record ScenicTilesRefreshedEvent(
        String eventId,
        String source,
        List<String> h3Indexes,
        Instant refreshedAt
) {
    public static final String TOPIC = "scenic.tiles.refreshed";
}

