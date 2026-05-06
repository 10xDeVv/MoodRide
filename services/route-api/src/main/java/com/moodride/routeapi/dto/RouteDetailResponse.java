package com.moodride.routeapi.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RouteDetailResponse(
        UUID routeId,
        UUID jobId,
        String routeUrl,
        double scenicScore,
        String qualityTier,
        double totalDistanceKm,
        int estimatedDurationMinutes,
        Integer timeBudgetMinutes,
        double startLat,
        double startLng,
        List<String> vibes,
        Map<String, Object> geometry,
        List<Map<String, Object>> scenicHighlights,
        List<RouteOptionResponse> routeOptions,
        String algorithmVersion,
        Integer beamCandidates,
        Integer computationTimeMs,
        Integer userRating,
        Instant ratedAt,
        Instant createdAt,
        Instant expiresAt
) {
}

