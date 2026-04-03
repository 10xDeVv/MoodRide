package com.moodride.routeapi.dto;

public record WaypointResponse(
    double latitude,
    double longitude,
    String instruction,
    double distanceToNext
) {}