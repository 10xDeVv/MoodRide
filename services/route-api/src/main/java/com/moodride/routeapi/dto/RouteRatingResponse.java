package com.moodride.routeapi.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RouteRatingResponse(
        UUID routeId,
        int rating,
        Instant ratedAt,
        List<String> feedbackTags
) {
    public RouteRatingResponse(UUID routeId, int rating, Instant ratedAt) {
        this(routeId, rating, ratedAt, List.of());
    }
}
