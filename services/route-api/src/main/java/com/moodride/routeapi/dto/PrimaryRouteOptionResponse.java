package com.moodride.routeapi.dto;

import java.io.Serializable;
import java.util.UUID;

public record PrimaryRouteOptionResponse(
        String profile,
        UUID routeId,
        String routeUrl,
        double scenicScore,
        double totalDistanceKm,
        int estimatedDurationMinutes
) implements Serializable {
}
