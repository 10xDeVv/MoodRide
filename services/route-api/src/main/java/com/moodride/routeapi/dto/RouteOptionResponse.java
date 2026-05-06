package com.moodride.routeapi.dto;

import java.util.UUID;

public record RouteOptionResponse(
        String profile,
        UUID routeId,
        String routeUrl,
        double scenicScore,
        double totalDistanceKm,
        int estimatedDurationMinutes
) {
}
