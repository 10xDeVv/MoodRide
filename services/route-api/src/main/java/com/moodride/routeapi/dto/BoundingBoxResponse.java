package com.moodride.routeapi.dto;

public record BoundingBoxResponse(
    double north,
    double south,
    double east,
    double west
) {}
