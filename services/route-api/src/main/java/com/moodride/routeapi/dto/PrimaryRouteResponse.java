package com.moodride.routeapi.dto;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PrimaryRouteResponse(
        UUID routeId,
        UUID jobId,
        String routeUrl,
        String profile,
        double scenicScore,
        double totalDistanceKm,
        int estimatedDurationMinutes,
        Integer timeBudgetMinutes,
        String routeMode,
        double startLat,
        double startLng,
        List<String> vibes,
        Map<String, Object> geometry,
        List<PrimaryRouteOptionResponse> routeOptions,
        String algorithmVersion,
        Integer computationTimeMs,
        long optionRevision,
        int optionCount,
        boolean optionsComplete,
        Instant createdAt,
        Instant expiresAt
) implements Serializable {
}
