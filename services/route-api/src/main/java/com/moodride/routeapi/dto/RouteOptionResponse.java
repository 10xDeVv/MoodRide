package com.moodride.routeapi.dto;

import java.io.Serializable;
import java.util.Map;
import java.util.UUID;

public record RouteOptionResponse(
        String profile,
        UUID routeId,
        String routeUrl,
        double scenicScore,
        Map<String, Double> scoreBreakdown,
        double totalDistanceKm,
        int estimatedDurationMinutes,
        RouteOptionExplanationResponse explanation
) implements Serializable {
}
