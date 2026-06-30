package com.moodride.routeapi.dto;

import java.time.Instant;
import java.util.UUID;

public record AnalyticsEventResponse(
    UUID eventId,
    String eventName,
    Instant recordedAt
) {
}
