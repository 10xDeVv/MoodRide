package com.moodride.routeapi.dto;

public record ScenicRegionResponse(
    String h3Index,
    double centerLat,
    double centerLng,
    double compositeScore,
    String dominantFeature,
    double confidence
) {}
