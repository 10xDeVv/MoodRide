package com.moodride.routeapi.dto;

import java.time.Instant;
import java.util.UUID;

public record RouteRatingResponse(
        UUID routeId,
        int rating,
        Instant ratedAt
) {
}
