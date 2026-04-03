package com.moodride.routeapi.dto;

import java.util.UUID;

public record RouteRequest(
    UUID userId,
    double startLatitude,
    double startLongitude,
    int timeBudgetMinutes,
    String vibe  // "coastal", "mountain", "forest", "mixed"
) {}
