package com.moodride.routeapi.dto;

import java.util.List;

public record ScenicRegionsResponse(
    List<ScenicRegionResponse> regions,
    int totalRegions,
    BoundingBoxResponse boundingBox
) {}
