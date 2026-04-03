package com.moodride.routeapi.dto;

import java.util.List;
import java.util.UUID;

public record RouteResponse(
    UUID routeId,
    UUID jobId,
    double totalDistanceKm,
    int estimatedDurationMinutes,
    double scenicScore,
    List<WaypointResponse> waypoints,
    String status
) {}
