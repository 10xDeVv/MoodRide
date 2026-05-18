package com.moodride.routeapi.dto;

import java.util.List;
import java.util.Map;

public record RouteOptionExplanationResponse(
        Map<String, Double> componentAverages,
        List<String> leadingComponents,
        String summary,
        int sampleTileCount
) {
}
