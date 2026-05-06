package com.moodride.eventmodels;

import java.time.Instant;
import java.util.List;

/**
 * Event emitted when traffic signals are updated and scenic refresh is required.
 */
public record TrafficTilesUpdatedEvent(
        String eventId,
        String source,
        List<String> h3Indexes,
        Instant emittedAt
) {
    public static final String TOPIC = "traffic.tiles.updated";
}

